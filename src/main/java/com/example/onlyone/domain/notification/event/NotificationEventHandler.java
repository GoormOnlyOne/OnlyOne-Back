package com.example.onlyone.domain.notification.event;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.sse.service.SseEmittersService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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

    /**
     * 알림 생성 후 SSE 전송 처리
     * 단순한 @Async 방식으로 처리
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("notificationExecutor")
    public void handleNotificationCreated(NotificationCreatedEvent event) {
        Notification notification = event.getNotification();
        Long userId = notification.getUser().getUserId();

        log.info("알림 전송 시작: id={}, type={}, userId={}, thread={}",
                notification.getId(),
                notification.getNotificationType().getType(),
                userId,
                Thread.currentThread().getName());

        try {
            sendNotification(notification);
        } catch (Exception e) {
            log.error("알림 전송 중 오류 발생: id={}, userId={}", 
                    notification.getId(), userId, e);
        }
    }

    /**
     * SSE를 통한 실시간 알림 전송
     * 비동기로 처리하여 이벤트 핸들러 블로킹 방지
     */
    private void sendNotification(Notification notification) {
        Long userId = notification.getUser().getUserId();

        log.debug("SSE 알림 전송 시도: userId={}, notificationId={}", userId, notification.getId());

        // 비동기로 SSE 전송 처리 (이벤트 핸들러 블로킹 방지)
        sseEmittersService.sendEvent(userId, "notification", notification)
            .thenAccept(success -> {
                if (success) {
                    updateSseSentStatus(notification, true);
                    log.debug("SSE 알림 전송 성공: userId={}, notificationId={}", userId, notification.getId());
                } else {
                    log.debug("사용자 미연결, DB만 저장됨: userId={}", userId);
                }
            })
            .exceptionally(ex -> {
                log.debug("SSE 전송 실패, 재연결 시 전송됨: userId={}, error={}", userId, ex.getMessage());
                    return null;
            });
    }

    /**
     * SSE 전송 상태 업데이트 (별도 트랜잭션에서 처리)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void updateSseSentStatus(Notification notification, boolean sent) {
        if (sent) {
            try {
                long updated = notificationRepository.updateSseSentStatus(notification.getId(), true);
                if (updated > 0) {
                    log.debug("SSE 전송 상태 업데이트: notificationId={}", notification.getId());
                }
            } catch (Exception e) {
                log.warn("SSE 상태 업데이트 실패: notificationId={}", notification.getId(), e);
            }
        }
    }
}