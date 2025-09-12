package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.sse.service.SseEmittersService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 생성 서비스
 */
@Service
@Slf4j
public class  NotificationCreationService {

    private final NotificationRepository notificationRepository;
    private final NotificationTypeRepository notificationTypeRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SseEmittersService sseEmittersService;
    private final Executor notificationExecutor;

    public NotificationCreationService(NotificationRepository notificationRepository,
                                     NotificationTypeRepository notificationTypeRepository, 
                                     ApplicationEventPublisher eventPublisher,
                                     SseEmittersService sseEmittersService,
                                     @Qualifier("notificationVirtualThreadExecutor") Executor notificationExecutor) {
        this.notificationRepository = notificationRepository;
        this.notificationTypeRepository = notificationTypeRepository;
        this.eventPublisher = eventPublisher;
        this.sseEmittersService = sseEmittersService;
        this.notificationExecutor = notificationExecutor;
    }

    /**
     * 알림 생성 (비동기 - DB저장과 SSE전송을 독립적으로 처리)
     */
    public CompletableFuture<Notification> createNotification(User user, Type type, String... args) {
        try {
            // 1. 알림 타입 조회
            NotificationType notificationType = notificationTypeRepository.findByType(type)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND));
            
            // 2. 알림 엔티티 생성
            Notification notification = Notification.create(user, notificationType, args);
            
            // 3. DB 저장 (비동기)
            CompletableFuture<Notification> dbFuture = CompletableFuture
                .supplyAsync(() -> {
                    Notification saved = notificationRepository.save(notification);
                    log.debug("알림 DB 저장 완료: userId={}, id={}", user.getUserId(), saved.getId());
                    return saved;
                }, notificationExecutor);
            
            // 4. SSE 전송 (DB 저장과 독립적으로 비동기 처리)
            CompletableFuture.runAsync(() -> {
                try {
                    sseEmittersService.sendEvent(user.getUserId(), "notification", notification);
                    log.debug("SSE 알림 전송 완료: userId={}, id={}", user.getUserId(), notification.getId());
                } catch (Exception e) {
                    log.warn("SSE 전송 실패 (재연결시 전송됨): userId={}, error={}", user.getUserId(), e.getMessage());
                }
            }, notificationExecutor);
            
            // DB 저장 Future 반환 (SSE는 독립적으로 처리)
            return dbFuture;
            
        } catch (Exception e) {
            log.error("알림 생성 실패: userId={}, type={}", user.getUserId(), type, e);
            return CompletableFuture.failedFuture(new CustomException(ErrorCode.NOTIFICATION_PROCESSING_FAILED));
        }
    }

    /**
     * 알림 생성 (동기 처리가 필요한 경우)
     */
    @Transactional
    public Notification createNotificationSync(User user, Type type, String... args) {
        try {
            // 1. 알림 타입 조회
            NotificationType notificationType = notificationTypeRepository.findByType(type)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND));
            
            // 2. 알림 엔티티 생성 및 저장
            Notification notification = createAndSaveNotification(user, notificationType, args);
            
            // 3. 이벤트 발행 (비동기 처리를 위해)
            publishNotificationCreatedEvent(notification);
            
            log.debug("알림 생성 완료 (동기): userId={}, type={}, id={}", 
                    user.getUserId(), type, notification.getId());
            
            return notification;
            
        } catch (Exception e) {
            log.error("알림 생성 실패 (동기): userId={}, type={}", user.getUserId(), type, e);
            throw new CustomException(ErrorCode.NOTIFICATION_PROCESSING_FAILED);
        }
    }



    private Notification createAndSaveNotification(User user, NotificationType type, String... args) {
        try {
            Notification notification = Notification.create(user, type, args);
            return notificationRepository.save(notification);
        } catch (Exception e) {
            log.error("알림 저장 실패: userId={}, type={}", user.getUserId(), type.getType(), e);
            throw new CustomException(ErrorCode.DATABASE_OPERATION_FAILED);
        }
    }

    private void publishNotificationCreatedEvent(Notification notification) {
        try {
            NotificationCreatedEvent event = new NotificationCreatedEvent(notification);
            eventPublisher.publishEvent(event);
            
            log.debug("알림 생성 이벤트 발행: id={}", notification.getId());
        } catch (Exception e) {
            log.warn("알림 이벤트 발행 실패 (알림은 생성됨): id={}", notification.getId(), e);
            // 이벤트 발행 실패는 알림 생성을 방해하지 않음
        }
    }
}