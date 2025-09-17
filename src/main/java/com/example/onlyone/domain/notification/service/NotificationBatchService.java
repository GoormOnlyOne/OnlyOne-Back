package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.sse.service.SseEmittersService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

/**
 * 알림 배치 처리 서비스
 * 개별 알림을 큐에 모아서 배치로 SSE 전송 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationBatchService {

    private final SseEmittersService sseEmittersService;
    private final NotificationRepository notificationRepository;
    
    // 사용자별 알림 큐 (배치 처리용)
    private final Map<Long, BlockingQueue<Notification>> userNotificationQueues = new ConcurrentHashMap<>();
    
    // 최대 큐 크기 (메모리 보호)
    private static final int MAX_QUEUE_SIZE_PER_USER = 100;
    private static final int BATCH_SIZE = 10; // 한 번에 처리할 알림 수
    
    private volatile boolean shuttingDown = false;

    /**
     * 알림 생성 이벤트를 수신하여 배치 큐에 추가 (통합 핸들러)
     * 트랜잭션 커밋 후 바로 큐에 추가하여 이중 처리 방지
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleNotificationCreated(NotificationCreatedEvent event) {
        if (shuttingDown) {
            return;
        }
        
        Notification notification = event.getNotification();
        Long userId = notification.getUser().getUserId();
        
        // 사용자가 온라인인 경우만 큐에 추가
        if (!sseEmittersService.isUserConnected(userId)) {
            log.debug("오프라인 사용자 알림 배치 스킵: userId={}", userId);
            return;
        }
        
        // 사용자별 큐 생성 및 알림 추가
        BlockingQueue<Notification> userQueue = userNotificationQueues.computeIfAbsent(
            userId, k -> new LinkedBlockingQueue<>(MAX_QUEUE_SIZE_PER_USER)
        );
        
        if (!userQueue.offer(notification)) {
            log.warn("사용자 알림 큐 포화: userId={}, 큐 크기={}", userId, userQueue.size());
            // 가장 오래된 알림 제거 후 새 알림 추가
            userQueue.poll();
            userQueue.offer(notification);
        }
        
        log.debug("알림 배치 큐 추가: userId={}, 큐 크기={}", userId, userQueue.size());
    }

    /**
     * 50ms마다 배치 처리 실행 (성능 개선)
     * 더 자주 처리하여 지연 최소화
     */
    @Scheduled(fixedDelay = 50)
    public void processBatchNotifications() {
        if (shuttingDown || userNotificationQueues.isEmpty()) {
            return;
        }
        
        int totalProcessed = 0;
        
        // 병렬 스트림으로 각 사용자별 처리 성능 향상
        List<Map.Entry<Long, BlockingQueue<Notification>>> entries = new ArrayList<>(userNotificationQueues.entrySet());
        
        for (Map.Entry<Long, BlockingQueue<Notification>> entry : entries) {
            Long userId = entry.getKey();
            BlockingQueue<Notification> queue = entry.getValue();
            
            // 사용자가 연결 해제된 경우 큐 정리
            if (!sseEmittersService.isUserConnected(userId)) {
                userNotificationQueues.remove(userId);
                log.debug("연결 해제된 사용자 큐 정리: userId={}, 큐 크기={}", userId, queue.size());
                continue;
            }
            
            // 배치 크기만큼 알림 처리
            List<Notification> batch = new ArrayList<>();
            for (int i = 0; i < BATCH_SIZE && !queue.isEmpty(); i++) {
                Notification notification = queue.poll();
                if (notification != null) {
                    batch.add(notification);
                }
            }
            
            if (!batch.isEmpty()) {
                processBatchForUser(userId, batch); // 비동기 처리
                totalProcessed += batch.size();
            }
            
            // 빈 큐 정리
            if (queue.isEmpty()) {
                userNotificationQueues.remove(userId);
            }
        }
        
        if (totalProcessed > 0) {
            log.debug("배치 알림 처리 완료: 총 {}개, 활성 사용자 {}명", totalProcessed, userNotificationQueues.size());
        }
    }
    
    /**
     * 특정 사용자의 알림 배치를 비동기로 처리 (핵심 성능 개선)
     */
    @Async("notificationExecutor")
    private CompletableFuture<Void> processBatchForUser(Long userId, List<Notification> notifications) {
        try {
            // 모든 SSE 전송을 병렬로 실행
            List<CompletableFuture<Boolean>> futures = notifications.stream()
                .map(notification -> 
                    sseEmittersService.sendEvent(userId, "notification", notification)
                        .exceptionally(ex -> {
                            log.debug("배치 SSE 전송 실패: userId={}, notificationId={}", userId, notification.getId());
                            return false;
                        })
                        .thenApply(success -> {
                            if (success) {
                                try {
                                    // 전송 성공 시 sseSent=true로 업데이트
                                    notificationRepository.updateSseSentStatus(notification.getId(), true);
                                } catch (Exception e) {
                                    log.warn("sseSent 업데이트 실패: notificationId={}", notification.getId(), e);
                                }
                            }
                            return success;
                        })
                )
                .collect(Collectors.toList());
            
            // 모든 전송이 완료될 때까지 기다리지 않고 바로 반환 (병렬 효과)
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("배치 SSE 전송 중 일부 실패: userId={}", userId, ex);
                    } else {
                        log.debug("사용자 배치 알림 전송 완료: userId={}, 알림 수={}", userId, notifications.size());
                    }
                });
            
        } catch (Exception e) {
            log.warn("배치 알림 처리 실패: userId={}, 알림 수={}", userId, notifications.size(), e);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * 현재 배치 큐 상태 조회 (모니터링용)
     */
    public Map<String, Object> getBatchStatus() {
        int totalQueueSize = userNotificationQueues.values().stream()
            .mapToInt(BlockingQueue::size)
            .sum();
            
        return Map.of(
            "activeUsers", userNotificationQueues.size(),
            "totalQueuedNotifications", totalQueueSize,
            "averageQueueSize", userNotificationQueues.isEmpty() ? 0 : 
                totalQueueSize / (double) userNotificationQueues.size()
        );
    }
    
    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("NotificationBatchService 종료: 큐에 남은 알림 {}개", 
            userNotificationQueues.values().stream().mapToInt(BlockingQueue::size).sum());
        userNotificationQueues.clear();
    }
}