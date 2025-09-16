package com.example.onlyone.domain.notification.event;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.sse.service.SseEmittersService;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import jakarta.annotation.PreDestroy;

/**
 * 알림 이벤트 전용 핸들러
 * NotificationService에서 이벤트 처리 로직 분리
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationEventHandler {

    private final SseEmittersService sseEmittersService;
    private final NotificationRepository notificationRepository;
    private volatile boolean shuttingDown = false;

    /**
     * 알림 생성 후 SSE 전송 처리
     * 대량 트래픽 대응: 불필요한 이벤트 처리 최소화
     */
    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("NotificationEventHandler 종료 시작");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async  // 기본 스레드 풀 사용으로 단순화
    public void handleNotificationCreated(NotificationCreatedEvent event) {
        // 종료 중인 경우 처리 스킵
        if (shuttingDown) {
            log.debug("애플리케이션 종료 중, 알림 이벤트 스킵");
            return;
        }
        
        Notification notification = event.getNotification();

        log.debug("알림 이벤트 처리 시작: id={}, type={}, userId={}",
                notification.getId(),
                notification.getNotificationType().getType(),
                notification.getUser().getUserId());

        try {
            // DB 쿼리 제거: 연결 상태 확인 없이 바로 전송 시도
            // SSE 전송 실패 시 자동으로 스킵됨
            sendNotification(notification);
            
        } catch (Exception e) {
            log.warn("알림 이벤트 처리 실패: id={}, userId={}", 
                    notification.getId(), notification.getUser().getUserId(), e);
        }
    }

    /**
     * SSE를 통한 실시간 알림 전송 (비동기 + 병렬 최적화)
     * SSE 전송과 상태 업데이트를 병렬로 처리하여 성능 최적화
     */
    private void sendNotification(Notification notification) {
        Long userId = notification.getUser().getUserId();
        Long notificationId = notification.getId();

        log.debug("비동기 SSE 전송 시작: userId={}, notificationId={}", userId, notificationId);

        // 비동기 SSE 전송과 상태 업데이트를 병렬로 처리
        sseEmittersService.sendEvent(userId, "notification", notification)
            .thenCompose(success -> {
                if (success) {
                    log.info("SSE 알림 전송 성공: userId={}, notificationId={}", userId, notificationId);
                    
                    // SSE 상태 업데이트 제거 - 성능 최적화
                    // 필요시 배치 업데이트나 별도 스케줄러로 처리
                    
                    // 상태 업데이트 완료를 기다리지 않고 즉시 성공 반환 (병렬 효과)
                    return CompletableFuture.completedFuture(success);
                } else {
                    log.debug("사용자 미연결: userId={}", userId);
                    return CompletableFuture.completedFuture(false);
                }
            })
            .exceptionally(ex -> {
                log.debug("SSE 전송 실패: userId={}, error={}", userId, ex.getMessage());
                return null;
            });
    }
}