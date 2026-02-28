package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.response.NotificationSseDto;
import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.port.NotificationDeliveryPort;
import com.example.onlyone.domain.notification.port.NotificationStoragePort;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 알림 배치 전송 처리기
 *
 * 알림 생성 후 커밋 시점에 큐에 적재하고,
 * 주기적으로 큐를 비워 전송 채널로 전송한 뒤 delivered 플래그를 갱신한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationBatchProcessor {

    private final NotificationStoragePort storagePort;
    private final NotificationDeliveryPort deliveryPort;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.notification.batch-size:10}")
    private int batchSize;

    @Value("${app.notification.max-queue-size-per-user:100}")
    private int maxQueueSizePerUser;

    @Value("${app.notification.batch-timeout-seconds:5}")
    private int batchTimeoutSeconds;

    private final Map<Long, BlockingQueue<NotificationCreatedEvent>> pendingQueues = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown = false;
    private volatile CompletableFuture<Void> currentBatchFuture = CompletableFuture.completedFuture(null);

    // ========== 이벤트 수신 ==========

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        if (shuttingDown) return;

        Long userId = event.userId();

        if (!deliveryPort.isUserReachable(userId)) {
            log.debug("오프라인 사용자 스킵: userId={}", userId);
            return;
        }

        enqueueNotification(userId, event);
    }

    // ========== 주기적 배치 처리 ==========

    @Scheduled(fixedDelayString = "${app.notification.batch-processing-interval:100}")
    public void processBatch() {
        if (shuttingDown || pendingQueues.isEmpty()) return;
        if (!currentBatchFuture.isDone()) {
            log.debug("이전 배치 진행 중, 스킵");
            return;
        }

        List<CompletableFuture<Void>> sendFutures = new ArrayList<>();

        for (var entry : new ArrayList<>(pendingQueues.entrySet())) {
            Long userId = entry.getKey();
            BlockingQueue<NotificationCreatedEvent> queue = entry.getValue();

            if (!deliveryPort.isUserReachable(userId)) {
                pendingQueues.remove(userId);
                continue;
            }

            List<NotificationCreatedEvent> batch = drainQueue(queue);
            if (!batch.isEmpty()) {
                sendFutures.add(sendBatchToUser(userId, batch));
            }
            if (queue.isEmpty()) {
                pendingQueues.remove(userId);
            }
        }

        if (!sendFutures.isEmpty()) {
            currentBatchFuture = CompletableFuture.allOf(sendFutures.toArray(CompletableFuture[]::new))
                    .orTimeout(batchTimeoutSeconds, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        log.warn("배치 타임아웃 또는 오류: {}", ex.getMessage());
                        return null;
                    });
        }
    }

    // ========== 종료 처리 ==========

    @PreDestroy
    public void shutdown() {
        log.info("NotificationBatchProcessor 종료 시작");
        shuttingDown = true;

        try {
            if (!currentBatchFuture.isDone()) {
                log.info("진행 중인 배치 완료 대기...");
                currentBatchFuture.get(batchTimeoutSeconds, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("배치 완료 대기 중 오류: {}", e.getMessage());
        }

        int remaining = pendingQueues.values().stream().mapToInt(BlockingQueue::size).sum();
        if (remaining > 0) {
            log.warn("미처리 알림 {}개 폐기", remaining);
        }
        pendingQueues.clear();
        log.info("NotificationBatchProcessor 종료 완료");
    }

    // ========== 내부 메서드 ==========

    private void enqueueNotification(Long userId, NotificationCreatedEvent event) {
        BlockingQueue<NotificationCreatedEvent> queue = pendingQueues.computeIfAbsent(
                userId, k -> new LinkedBlockingQueue<>(maxQueueSizePerUser));

        if (!queue.offer(event)) {
            log.warn("큐 포화 - 오래된 알림 제거: userId={}", userId);
            queue.poll();
            queue.offer(event);
        }
    }

    private List<NotificationCreatedEvent> drainQueue(BlockingQueue<NotificationCreatedEvent> queue) {
        List<NotificationCreatedEvent> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        return batch;
    }

    private CompletableFuture<Void> sendBatchToUser(Long userId, List<NotificationCreatedEvent> events) {
        List<CompletableFuture<Long>> sendResults = events.stream()
                .map(e -> sendSingleNotification(userId, e))
                .toList();

        return CompletableFuture.allOf(sendResults.toArray(CompletableFuture[]::new))
                .thenRun(() -> markSentNotifications(userId, sendResults));
    }

    private CompletableFuture<Long> sendSingleNotification(Long userId, NotificationCreatedEvent event) {
        NotificationSseDto dto = NotificationSseDto.from(event);
        return deliveryPort.deliver(userId, "notification", dto)
                .thenApply(success -> success ? event.notificationId() : null)
                .exceptionally(ex -> {
                    log.debug("알림 전송 실패: notificationId={}", event.notificationId());
                    return null;
                });
    }

    private void markSentNotifications(Long userId, List<CompletableFuture<Long>> sendResults) {
        List<Long> sentIds = sendResults.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();

        if (sentIds.isEmpty()) return;

        try {
            transactionTemplate.executeWithoutResult(status ->
                    storagePort.markDeliveredByIds(sentIds));
            log.debug("알림 전송 완료: userId={}, count={}", userId, sentIds.size());
        } catch (Exception e) {
            log.warn("알림 전송 후 DB 반영 실패: userId={}, count={}", userId, sentIds.size(), e);
        }
    }
}
