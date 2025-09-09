package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.sse.SseEmittersService;
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
 * 알림 생성 전용 서비스
 * 단일 책임 원칙에 따라 NotificationService에서 분리
 */
@Service
@Slf4j
public class NotificationCreationService {

    private final NotificationRepository notificationRepository;
    private final NotificationTypeCacheService notificationTypeCacheService;
    private final ApplicationEventPublisher eventPublisher;
    private final SseEmittersService sseEmittersService;
    private final Executor dbTaskExecutor;
    private final Executor sseEventExecutor;

    public NotificationCreationService(NotificationRepository notificationRepository,
                                     NotificationTypeCacheService notificationTypeCacheService, 
                                     ApplicationEventPublisher eventPublisher,
                                     SseEmittersService sseEmittersService,
                                     @Qualifier("dbTaskExecutor") Executor dbTaskExecutor,
                                     @Qualifier("sseEventExecutor") Executor sseEventExecutor) {
        this.notificationRepository = notificationRepository;
        this.notificationTypeCacheService = notificationTypeCacheService;
        this.eventPublisher = eventPublisher;
        this.sseEmittersService = sseEmittersService;
        this.dbTaskExecutor = dbTaskExecutor;
        this.sseEventExecutor = sseEventExecutor;
    }

    /**
     * 알림 생성 (동기 처리) - 즉시 응답이 필요한 경우
     * 
     * @param user 알림 받을 사용자
     * @param type 알림 타입
     * @param args 알림 템플릿 인자
     * @return 생성된 알림
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification createNotification(User user, Type type, String... args) {
        try {
            // 1. 알림 타입 조회
            NotificationType notificationType = notificationTypeCacheService.findByType(type);
            
            // 2. 알림 엔티티 생성 및 저장
            Notification notification = createAndSaveNotification(user, notificationType, args);
            
            // 3. 이벤트 발행 (비동기 처리를 위해)
            publishNotificationCreatedEvent(notification);
            
            log.debug("알림 생성 완료: userId={}, type={}, id={}", 
                    user.getUserId(), type, notification.getId());
            
            return notification;
            
        } catch (Exception e) {
            log.error("알림 생성 실패: userId={}, type={}", user.getUserId(), type, e);
            throw new CustomException(ErrorCode.NOTIFICATION_PROCESSING_FAILED);
        }
    }

    /**
     * 알림 생성 (비동기 처리) - 성능 최적화
     * 
     * @param user 알림 받을 사용자
     * @param type 알림 타입
     * @param args 알림 템플릿 인자
     * @return CompletableFuture<Notification>
     */
    @Async("notificationExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompletableFuture<Notification> createNotificationAsync(User user, Type type, String... args) {
        try {
            // 1. 알림 타입 조회
            NotificationType notificationType = notificationTypeCacheService.findByType(type);
            
            // 2. 알림 엔티티 생성 및 저장
            Notification notification = createAndSaveNotification(user, notificationType, args);
            
            // 3. 이벤트 발행 (SSE 전송을 위해)
            publishNotificationCreatedEvent(notification);
            
            log.debug("알림 생성 완료 (비동기): userId={}, type={}, id={}", 
                    user.getUserId(), type, notification.getId());
            
            return CompletableFuture.completedFuture(notification);
            
        } catch (Exception e) {
            log.error("알림 생성 실패 (비동기): userId={}, type={}", user.getUserId(), type, e);
            return CompletableFuture.failedFuture(
                new CustomException(ErrorCode.NOTIFICATION_PROCESSING_FAILED)
            );
        }
    }

    /**
     * 알림 생성 (최적화된 병렬 처리) - DB저장과 SSE전송 병렬 실행
     * 
     * @param user 알림 받을 사용자
     * @param type 알림 타입  
     * @param args 알림 템플릿 인자
     * @return CompletableFuture<Notification> DB 저장 완료 시점에 응답
     */
    public CompletableFuture<Notification> createNotificationOptimized(User user, Type type, String... args) {
        try {
            // 1. 알림 타입 조회 (로컬 캐시)
            NotificationType notificationType = notificationTypeCacheService.findByType(type);
            
            // 2. DB 저장 (비동기) - 트랜잭션은 Repository 레벨에서 처리됨
            CompletableFuture<Notification> dbFuture = CompletableFuture
                .supplyAsync(() -> {
                    Notification notification = Notification.create(user, notificationType, args);
                    return notificationRepository.save(notification);
                }, dbTaskExecutor);
            
            // 3. DB 저장 완료 시 SSE 전송 (병렬)
            dbFuture.thenComposeAsync(notification -> 
                    sseEmittersService.sendEvent(user.getUserId(), "notification", notification)
                        .thenAccept(success -> {
                            if (success) {
                                log.debug("SSE 알림 전송 성공 (병렬): userId={}, id={}", user.getUserId(), notification.getId());
                            }
                        }), sseEventExecutor)
                .exceptionally(ex -> {
                    log.warn("SSE 전송 실패, 재연결 시 전송됨: userId={}", user.getUserId());
                    return null;
                });
                
            log.debug("병렬 알림 생성 시작: userId={}, type={}", user.getUserId(), type);
            
            // DB 저장 완료되면 즉시 응답 (SSE는 백그라운드에서 계속 처리)
            return dbFuture;
            
        } catch (Exception e) {
            log.error("병렬 알림 생성 실패: userId={}, type={}", user.getUserId(), type, e);
            return CompletableFuture.failedFuture(new CustomException(ErrorCode.NOTIFICATION_PROCESSING_FAILED));
        }
    }

    /**
     * 대량 알림 생성 (배치 처리)
     * 
     * @param users 알림 받을 사용자 목록
     * @param type 알림 타입
     * @param args 알림 템플릿 인자
     * @return 생성된 알림 개수
     */
    @Async("notificationExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CompletableFuture<Integer> createBulkNotifications(Iterable<User> users, Type type, String... args) {
        try {
            NotificationType notificationType = notificationTypeCacheService.findByType(type);
            int count = 0;
            
            for (User user : users) {
                Notification notification = createAndSaveNotification(user, notificationType, args);
                publishNotificationCreatedEvent(notification);
                count++;
                
                // 배치 크기 제한 (메모리 보호)
                if (count % 100 == 0) {
                    log.debug("배치 알림 생성 진행: {}개 처리", count);
                }
            }
            
            log.info("대량 알림 생성 완료: type={}, count={}", type, count);
            return CompletableFuture.completedFuture(count);
            
        } catch (Exception e) {
            log.error("대량 알림 생성 실패: type={}", type, e);
            return CompletableFuture.failedFuture(
                new CustomException(ErrorCode.NOTIFICATION_PROCESSING_FAILED)
            );
        }
    }

    /**
     * 알림 엔티티 생성 및 저장
     * Note: 트랜잭션이 이미 활성화된 컨텍스트에서 호출되어야 함
     */
    private Notification createAndSaveNotification(User user, NotificationType type, String... args) {
        try {
            Notification notification = Notification.create(user, type, args);
            return notificationRepository.save(notification);
        } catch (Exception e) {
            log.error("알림 저장 실패: userId={}, type={}", user.getUserId(), type.getType(), e);
            throw new CustomException(ErrorCode.DATABASE_OPERATION_FAILED);
        }
    }

    /**
     * 알림 생성 이벤트 발행
     */
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