package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.sse.service.SseEventSender;
import jakarta.annotation.PreDestroy;
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
 * 알림 SSE 배치 전송 처리기
 *
 * 알림 생성 후 커밋 시점에 큐에 적재하고,
 * 주기적으로 큐를 비워 SSE로 전송한 뒤 sse_sent 플래그를 갱신한다.
 */
@Slf4j
@Component
public class NotificationBatchProcessor {

    private final NotificationRepository notificationRepository;
    private final SseEventSender sseEventSender;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.notification.batch-size:10}")
    private int batchSize;

    @Value("${app.notification.max-queue-size-per-user:100}")
    private int maxQueueSizePerUser;

    @Value("${app.notification.batch-timeout-seconds:5}")
    private int batchTimeoutSeconds;

    // 사용자별 전송 대기 큐
    private final Map<Long, BlockingQueue<Notification>> pendingQueues = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown = false;
    private volatile CompletableFuture<Void> currentBatchFuture = CompletableFuture.completedFuture(null);

    public NotificationBatchProcessor(NotificationRepository notificationRepository,
                                      SseEventSender sseEventSender,
                                      TransactionTemplate transactionTemplate) {
        this.notificationRepository = notificationRepository;
        this.sseEventSender = sseEventSender;
        this.transactionTemplate = transactionTemplate;
    }

    // ========== 이벤트 수신 ==========

    /**
     * 알림 생성 트랜잭션 커밋 후, 온라인 사용자의 큐에 알림을 적재한다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        if (shuttingDown) {
            return;
        }

        Notification notification = event.notification();
        Long userId = notification.getUser().getUserId();

        // 오프라인 사용자는 SSE 전송 불필요
        if (!sseEventSender.isUserConnected(userId)) {
            log.debug("오프라인 사용자 스킵: userId={}", userId);
            return;
        }

        enqueueNotification(userId, notification);
    }

    // ========== 주기적 배치 처리 ==========

    /**
     * 일정 주기로 큐를 확인하여 SSE 전송을 수행한다.
     */
    @Scheduled(fixedDelayString = "${app.notification.batch-processing-interval:100}")
    public void processBatch() {
        if (shuttingDown || pendingQueues.isEmpty()) {
            return;
        }

        // 이전 배치가 아직 진행 중이면 스킵
        if (!currentBatchFuture.isDone()) {
            log.debug("이전 배치 진행 중, 스킵");
            return;
        }

        List<CompletableFuture<Void>> sendFutures = new ArrayList<>();
        var snapshot = new ArrayList<>(pendingQueues.entrySet());

        for (var entry : snapshot) {
            Long userId = entry.getKey();
            BlockingQueue<Notification> queue = entry.getValue();

            if (!sseEventSender.isUserConnected(userId)) {
                pendingQueues.remove(userId);
                continue;
            }

            List<Notification> batch = drainQueue(queue);
            if (!batch.isEmpty()) {
                sendFutures.add(sendBatchToUser(userId, batch));
            }

            if (queue.isEmpty()) {
                pendingQueues.remove(userId);
            }
        }

        // 모든 사용자 전송 완료 대기 (타임아웃 적용)
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

    /**
     * 큐에 알림을 추가한다. 큐가 가득 차면 가장 오래된 알림을 제거한다.
     */
    private void enqueueNotification(Long userId, Notification notification) {
        BlockingQueue<Notification> queue = pendingQueues.computeIfAbsent(
                userId, k -> new LinkedBlockingQueue<>(maxQueueSizePerUser)
        );

        if (!queue.offer(notification)) {
            log.warn("큐 포화 - 오래된 알림 제거: userId={}", userId);
            queue.poll();
            queue.offer(notification);
        }
    }

    private List<Notification> drainQueue(BlockingQueue<Notification> queue) {
        List<Notification> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        return batch;
    }

    private CompletableFuture<Void> sendBatchToUser(Long userId, List<Notification> notifications) {
        try {
            List<CompletableFuture<Long>> sendResults = notifications.stream()
                    .map(n -> sendSingleNotification(userId, n))
                    .toList();

            return CompletableFuture.allOf(sendResults.toArray(CompletableFuture[]::new))
                    .thenRun(() -> markSentNotifications(userId, sendResults));
        } catch (Exception e) {
            log.warn("배치 전송 실패: userId={}, count={}", userId, notifications.size(), e);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 단건 SSE 전송. 성공 시 알림 ID, 실패 시 null 반환 */
    private CompletableFuture<Long> sendSingleNotification(Long userId, Notification notification) {
        return sseEventSender.sendEvent(userId, "notification", notification)
                .thenApply(success -> success ? notification.getId() : null)
                .exceptionally(ex -> {
                    log.debug("SSE 전송 실패: notificationId={}", notification.getId());
                    return null;
                });
    }

    /**
     * 전송 성공한 알림들의 sse_sent를 DB에 반영한다.
     */
    private void markSentNotifications(Long userId, List<CompletableFuture<Long>> sendResults) {
        List<Long> sentIds = sendResults.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();

        if (sentIds.isEmpty()) {
            return;
        }

        transactionTemplate.executeWithoutResult(status ->
                notificationRepository.markSseSentByIds(sentIds));
        log.debug("SSE 전송 완료: userId={}, count={}", userId, sentIds.size());
    }
}
