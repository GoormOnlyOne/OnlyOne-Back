package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.DeliveryMethod;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;

/**
 * 알림 서비스 - QueryDSL 기반 성능 최적화 및 JWT 인증 적용
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

  private final UserRepository userRepository;
  private final NotificationTypeRepository notificationTypeRepository;
  private final NotificationRepository notificationRepository;
  private final SseEmittersService sseEmittersService;
  private final FcmService fcmService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * 알림 생성 및 전송 (이벤트 발행으로 SSE/FCM 처리)
   */
  @Transactional
  public NotificationCreateResponseDto createNotification(NotificationCreateRequestDto requestDto) {
    User user = findUser(requestDto.getUserId());
    NotificationType type = findNotificationType(requestDto.getType());

    AppNotification appNotification = createAndSaveNotification(user, type, requestDto.getArgs());
    
    // 트랜잭션 커밋 후 실시간 알림 전송을 위한 이벤트 발행
    eventPublisher.publishEvent(new NotificationCreatedEvent(appNotification));

    return NotificationCreateResponseDto.from(appNotification);
  }

  /**
   * 알림 생성 편의 메서드 (다른 서비스에서 사용)
   */
  @Transactional
  public NotificationCreateResponseDto createNotification(User user, Type type, String... args) {
    NotificationType notificationType = findNotificationType(type);
    AppNotification appNotification = createAndSaveNotification(user, notificationType, args);
    
    // 트랜잭션 커밋 후 실시간 알림 전송을 위한 이벤트 발행
    eventPublisher.publishEvent(new NotificationCreatedEvent(appNotification));

    return NotificationCreateResponseDto.from(appNotification);
  }

  /**
   * 타겟 정보를 포함한 알림 생성 (클릭 시 이동 가능)
   * 타겟 타입은 알림 Type에서 자동으로 결정됨
   */
  @Transactional
  public NotificationCreateResponseDto createNotificationWithTarget(User user, Type type, 
                                                                    Long targetId, 
                                                                    String... args) {
    NotificationType notificationType = findNotificationType(type);
    String targetType = type.getTargetType(); // Type enum에서 타겟 타입 가져오기
    AppNotification appNotification = createAndSaveNotificationWithTarget(
        user, notificationType, targetType, targetId, args);
    
    // 트랜잭션 커밋 후 실시간 알림 전송을 위한 이벤트 발행
    eventPublisher.publishEvent(new NotificationCreatedEvent(appNotification));

    return NotificationCreateResponseDto.from(appNotification);
  }

  /**
   * 트랜잭션 커밋 후 알림 타입별 전송 방식 적용
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Async
  public void handleNotificationCreated(NotificationCreatedEvent event) {
    AppNotification appNotification = event.getNotification();
    DeliveryMethod deliveryMethod = appNotification.getNotificationType().getDeliveryMethod();
    
    log.info("알림 전송 시작: id={}, type={}, method={}", 
        appNotification.getId(), 
        appNotification.getNotificationType().getType(), 
        deliveryMethod);
    
    // 전송 방식에 따라 선택적 전송
    if (deliveryMethod.shouldSendSse()) {
        sendSseNotificationSafely(appNotification);
    }
    
    if (deliveryMethod.shouldSendFcm()) {
        sendFcmNotificationAsyncSafely(appNotification);
    }
  }

  /**
   * 알림 목록 조회 (QueryDSL 사용)
   */
  @Transactional(readOnly = true)
  public NotificationListResponseDto getNotifications(Long userId, Long cursor, int size) {
    size = Math.min(size, 100); // 최대 100개 제한

    List<NotificationItemDto> notifications = 
        notificationRepository.findNotificationsByUserId(userId, cursor, size);

    return buildNotificationListResponse(userId, notifications);
  }

  /**
   * 특정 타입의 알림 목록 조회 (QueryDSL 사용)
   */
  @Transactional(readOnly = true)
  public NotificationListResponseDto getNotificationsByType(Long userId, Type type, Long cursor, int size) {
    size = Math.min(size, 100); // 최대 100개 제한
    
    List<NotificationItemDto> notifications = 
        notificationRepository.findNotificationsByUserIdAndType(userId, type, cursor, size);
    
    return buildNotificationListResponse(userId, notifications);
  }

  /**
   * 읽지 않은 알림 개수 조회 (QueryDSL 사용)
   */
  @Transactional(readOnly = true)
  public Long getUnreadCount(Long userId) {
    Long count = notificationRepository.countUnreadByUserId(userId);
    return count != null ? count : 0L;
  }

  /**
   * 모든 알림 읽음 처리 (개별 엔티티 업데이트)
   */
  @Transactional
  public void markAsRead(Long notificationId, Long userId) {
    AppNotification notification = notificationRepository.findById(notificationId)
        .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND));

    if (!notification.getUser().getUserId().equals(userId)) {
      throw new CustomException(ErrorCode.UNAUTHORIZED);
    }

    notification.markAsRead();
  }

  @Transactional
  public void markAllAsRead(Long userId) {
    long markedCount = notificationRepository.markAllAsReadByUserId(userId);
    
    if (markedCount > 0) {
      sendUnreadCountUpdate(userId); // SSE로 읽지 않은 개수 업데이트 전송
      log.info("Marked {} notifications as read for user: {}", markedCount, userId);
    }
  }

  /**
   * 알림 삭제 (소유권 검증 포함)
   */
  @Transactional
  public void deleteNotification(Long userId, Long notificationId) {
    AppNotification appNotification = findNotification(notificationId);
    validateNotificationOwnership(appNotification, userId);

    boolean wasUnread = !appNotification.isRead();
    notificationRepository.delete(appNotification);

    if (wasUnread) {
      sendUnreadCountUpdate(userId); // SSE로 읽지 않은 개수 업데이트 전송
    }

    log.info("Notification deleted: id={}", notificationId);
  }

  // ================================
  // Private Helper Methods
  // ================================

  // 사용자 조회 (예외 처리 포함)
  private User findUser(Long userId) {
    return findEntityOrThrow(
        userRepository.findById(userId),
        "User", userId, ErrorCode.USER_NOT_FOUND
    );
  }

  // 알림 타입 조회 (예외 처리 포함)
  private NotificationType findNotificationType(Type type) {
    return findEntityOrThrow(
        notificationTypeRepository.findByType(type),
        "NotificationType", type, ErrorCode.NOTIFICATION_TYPE_NOT_FOUND
    );
  }

  // 알림 조회 (예외 처리 포함)
  private AppNotification findNotification(Long notificationId) {
    return findEntityOrThrow(
        notificationRepository.findById(notificationId),
        "Notification", notificationId, ErrorCode.NOTIFICATION_NOT_FOUND
    );
  }

  // 공통 엔티티 조회 메서드 (Optional → Entity 변환)
  private <T> T findEntityOrThrow(Optional<T> optional, String entityName, Object id, ErrorCode errorCode) {
    return optional.orElseThrow(() -> {
      log.error("{} not found: id={}", entityName, id);
      return new CustomException(errorCode);
    });
  }

  // 알림 생성 및 저장
  private AppNotification createAndSaveNotification(User user, NotificationType type, String... args) {
    AppNotification appNotification = AppNotification.create(user, type, args);
    return notificationRepository.save(appNotification);
  }

  // 타겟 정보를 포함한 알림 생성 및 저장
  private AppNotification createAndSaveNotificationWithTarget(User user, NotificationType type, 
                                                              String targetType, Long targetId, 
                                                              String... args) {
    AppNotification appNotification = AppNotification.createWithTarget(
        user, type, targetType, targetId, args);
    return notificationRepository.save(appNotification);
  }

  // SSE 알림 전송 (예외 안전)
  private void sendSseNotificationSafely(AppNotification appNotification) {
    executeNotificationSafely(
        () -> sseEmittersService.sendSseNotification(appNotification.getUser().getUserId(),
            appNotification),
        "SSE", appNotification.getId()
    );
  }

  // FCM 알림 전송 (비동기, 예외 안전)
  private void sendFcmNotificationAsyncSafely(AppNotification appNotification) {
    Long userId = appNotification.getUser().getUserId();
    String fcmToken = appNotification.getUser().getFcmToken();
    
    // FCM 토큰 상태 상세 로깅
    log.info("FCM notification attempt: userId={}, notificationId={}, hasToken={}, tokenLength={}, tokenPrefix={}", 
        userId, appNotification.getId(), 
        fcmToken != null, fcmToken != null ? fcmToken.length() : 0,
        fcmToken != null ? fcmToken.substring(0, Math.min(20, fcmToken.length())) + "..." : "null");
    
    if (fcmToken == null || fcmToken.isBlank()) {
      log.warn("FCM token is null or empty for user: {}, skipping FCM notification", userId);
      updateFcmSentStatus(appNotification, false);
      return;
    }
    
    try {
      fcmService.sendFcmNotification(appNotification);
      updateFcmSentStatus(appNotification, true);

    } catch (CustomException e) {
      // FCM 관련 예외 처리 및 상태 업데이트
      updateFcmSentStatus(appNotification, false);

      // FCM 토큰 관련 에러 로깅
      if (e.getErrorCode() == ErrorCode.FCM_TOKEN_NOT_FOUND) {
        log.warn("FCM token not found for user: {}, client should refresh token", 
            appNotification.getUser().getUserId());
      } else if (e.getErrorCode() == ErrorCode.FCM_TOKEN_REFRESH_REQUIRED) {
        log.warn("FCM token refresh required for user: {}, client should re-register token", 
            appNotification.getUser().getUserId());
      }

    } catch (Exception e) {
      // 예상치 못한 예외 처리
      updateFcmSentStatus(appNotification, false);
      log.error("Unexpected FCM error: id={}, error={}",
          appNotification.getId(), e.getMessage(), e);
    }
  }

  // FCM 전송 상태 업데이트 (별도 트랜잭션)
  @Transactional
  private void updateFcmSentStatus(AppNotification appNotification, boolean sent) {
    try {
      AppNotification managedNotification = notificationRepository.findById(appNotification.getId())
          .orElse(null);
      if (managedNotification != null) {
        managedNotification.markFcmSent();
        notificationRepository.save(managedNotification);
      }
    } catch (Exception e) {
      log.error("Failed to update FCM sent status: notificationId={}, error={}", 
          appNotification.getId(), e.getMessage());
    }
  }

  // 알림 전송 예외 안전 실행
  private void executeNotificationSafely(Runnable task, String type, Long id) {
    try {
      task.run();
    } catch (Exception e) {
      log.error("{} notification failed: id={}, error={}", type, id, e.getMessage());
    }
  }

  // SSE로 읽지 않은 개수 업데이트 전송
  private void sendUnreadCountUpdate(Long userId) {
    executeNotificationSafely(
        () -> sseEmittersService.sendUnreadCountUpdate(userId),
        "UnreadCount", userId
    );
  }

  // 알림 소유권 검증 (보안)
  private void validateNotificationOwnership(AppNotification appNotification, Long userId) {
    if (!appNotification.getUser().getUserId().equals(userId)) {
      log.error("Unauthorized notification access: userId={}, notificationOwnerId={}",
          userId, appNotification.getUser().getUserId());
      throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
  }


  // NotificationListResponseDto 빌드
  private NotificationListResponseDto buildNotificationListResponse(Long userId, List<NotificationItemDto> notifications) {
    Long nextCursor = notifications.isEmpty() ? null :
        notifications.get(notifications.size() - 1).getNotificationId();

    boolean hasMore = nextCursor != null &&
        !notificationRepository.findNotificationsByUserId(userId, nextCursor, 1).isEmpty();

    Long unreadCount = notificationRepository.countUnreadByUserId(userId);

    return NotificationListResponseDto.builder()
        .notifications(notifications)
        .cursor(nextCursor)
        .hasMore(hasMore)
        .unreadCount(unreadCount != null ? unreadCount : 0L)
        .build();
  }

  /**
   * 알림 생성 이벤트 클래스 (트랜잭션 분리용)
   */
  public static class NotificationCreatedEvent {
    private final AppNotification notification;

    public NotificationCreatedEvent(AppNotification notification) {
      this.notification = notification;
    }

    public AppNotification getNotification() {
      return notification;
    }
  }
}