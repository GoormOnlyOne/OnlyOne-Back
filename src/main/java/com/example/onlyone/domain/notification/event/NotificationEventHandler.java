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
 * 알림 이벤트 전용 핸들러 - DEPRECATED
 * NotificationBatchService에서 직접 처리하므로 비활성화
 */
// @Component  // 비활성화: 이중 처리 방지
@RequiredArgsConstructor
@Slf4j
public class NotificationEventHandler {

    private volatile boolean shuttingDown = false;

    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        log.info("NotificationEventHandler 종료 시작");
    }

    /**
     * 알림 생성 이벤트 수신 - 배치 서비스로 위임
     * 개별 처리 대신 배치로 모아서 효율적으로 처리
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleNotificationCreated(NotificationCreatedEvent event) {
        if (shuttingDown) {
            log.debug("애플리케이션 종료 중, 알림 이벤트 스킵");
            return;
        }
        
        Notification notification = event.getNotification();
        log.debug("알림 이벤트 수신 (배치 처리 대기): id={}, userId={}", 
                notification.getId(), notification.getUser().getUserId());
        
        // 실제 처리는 NotificationBatchService의 @EventListener에서 담당
        // 이 핸들러는 트랜잭션 커밋 후 이벤트 재발행 역할만 수행
    }
}