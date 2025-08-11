package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationRepository.NotificationListProjection;
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
import java.util.stream.Collectors;

/**
 * 알림 서비스 - 개선된 버전
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
   * 알림 생성 및 전송
   */
  @Transactional
  public NotificationCreateResponseDto createNotification(NotificationCreateRequestDto requestDto) {
    log.debug("Creating notification: userId={}, type={}", requestDto.getUserId(), requestDto.getType());

    User user = findUser(requestDto.getUserId());
    NotificationType type = findNotificationType(requestDto.getType());

    AppNotification appNotification = createAndSaveNotification(user, type, requestDto.getArgs());
    
    // 이벤트 발행 (트랜잭션 커밋 후 자동 처리)
    eventPublisher.publishEvent(new NotificationCreatedEvent(appNotification));

    log.info("Notification created and event published: id={}", appNotification.getNotificationId());
    return NotificationCreateResponseDto.from(appNotification);
  }

  /**
   * 알림 생성 편의 메서드 (다른 서비스에서 사용)
   */
  @Transactional
  public NotificationCreateResponseDto createNotification(User user, Type type, String... args) {
    log.debug("Creating notification via convenience method: userId={}, type={}", user.getUserId(), type);

    NotificationType notificationType = findNotificationType(type);
    AppNotification appNotification = createAndSaveNotification(user, notificationType, args);
    
    // 이벤트 발행 (트랜잭션 커밋 후 자동 처리)
    eventPublisher.publishEvent(new NotificationCreatedEvent(appNotification));

    log.info("Notification created via convenience method and event published: id={}", appNotification.getNotificationId());
    return NotificationCreateResponseDto.from(appNotification);
  }

  /**
   * 트랜잭션 커밋 후 실시간 알림 전송 이벤트 처리
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Async
  public void handleNotificationCreated(NotificationCreatedEvent event) {
    AppNotification appNotification = event.getNotification();
    log.debug("Handling notification created event: id={}", appNotification.getNotificationId());
    
    sendSseNotificationSafely(appNotification);
    sendFcmNotificationAsyncSafely(appNotification);
  }

  /**
   * 알림 목록 조회 (커서 기반 페이징) - 읽지 않은 알림만
   */
  @Transactional(readOnly = true)
  public NotificationListResponseDto getNotifications(Long userId, Long cursor, int size) {
    log.debug("Fetching unread notifications: userId={}, cursor={}, size={}", userId, cursor, size);

    size = Math.min(size, 100); // 최대 100개 제한

    List<NotificationListProjection> projections = (cursor == null)
        ? notificationRepository.findFirstPageByUserId(userId, size)
        : notificationRepository.findAfterCursorByUserId(userId, cursor, size);

    List<NotificationItemDto> notifications = projections.stream()
        .map(this::toNotificationItemDto)
        .collect(Collectors.toList());

    return buildNotificationListResponse(userId, notifications);
  }

  /**
   * 전체 알림 목록 조회 (커서 기반 페이징) - 읽은 알림 포함
   */
  @Transactional(readOnly = true)
  public NotificationListResponseDto getAllNotifications(Long userId, Long cursor, int size) {
    log.debug("Fetching all notifications: userId={}, cursor={}, size={}", userId, cursor, size);

    size = Math.min(size, 100); // 최대 100개 제한

    List<NotificationListProjection> projections = (cursor == null)
        ? notificationRepository.findAllFirstPageByUserId(userId, size)
        : notificationRepository.findAllAfterCursorByUserId(userId, cursor, size);

    List<NotificationItemDto> notifications = projections.stream()
        .map(this::toNotificationItemDto)
        .collect(Collectors.toList());

    return buildNotificationListResponse(userId, notifications);
  }

  /**
   * 모든 알림 읽음 처리
   */
  @Transactional
  public void markAllAsRead(Long userId) {
    log.debug("Marking all notifications as read: userId={}", userId);

    List<AppNotification> unreadAppNotifications = notificationRepository.findByUser_UserIdAndIsReadFalse(userId);
    if (unreadAppNotifications.isEmpty()) {
      return;
    }

    unreadAppNotifications.forEach(AppNotification::markAsRead);
    sendUnreadCountUpdate(userId);

    log.info("Marked {} notifications as read for user: {}", unreadAppNotifications.size(), userId);
  }

  /**
   * 알림 삭제
   */
  @Transactional
  public void deleteNotification(Long userId, Long notificationId) {
    log.debug("Deleting notification: userId={}, notificationId={}", userId, notificationId);

    AppNotification appNotification = findNotification(notificationId);
    validateNotificationOwnership(appNotification, userId);

    boolean wasUnread = !appNotification.getIsRead();
    notificationRepository.delete(appNotification);

    if (wasUnread) {
      sendUnreadCountUpdate(userId);
    }

    log.info("Notification deleted: id={}", notificationId);
  }



  // ================================
  // Private Helper Methods
  // ================================

  private User findUser(Long userId) {
    return findEntityOrThrow(
        userRepository.findById(userId),
        "User", userId, ErrorCode.USER_NOT_FOUND
    );
  }

  private NotificationType findNotificationType(Type type) {
    return findEntityOrThrow(
        notificationTypeRepository.findByType(type),
        "NotificationType", type, ErrorCode.NOTIFICATION_TYPE_NOT_FOUND
    );
  }

  private AppNotification findNotification(Long notificationId) {
    return findEntityOrThrow(
        notificationRepository.findById(notificationId),
        "Notification", notificationId, ErrorCode.NOTIFICATION_NOT_FOUND
    );
  }

  private <T> T findEntityOrThrow(Optional<T> optional, String entityName, Object id, ErrorCode errorCode) {
    return optional.orElseThrow(() -> {
      log.error("{} not found: id={}", entityName, id);
      return new CustomException(errorCode);
    });
  }

  private AppNotification createAndSaveNotification(User user, NotificationType type, String... args) {
    AppNotification appNotification = AppNotification.create(user, type, args);
    return notificationRepository.save(appNotification);
  }

  private void sendNotifications(AppNotification appNotification) {
    sendSseNotificationSafely(appNotification);
    sendFcmNotificationAsyncSafely(appNotification);
  }

  private void sendSseNotificationSafely(AppNotification appNotification) {
    executeNotificationSafely(
        () -> sseEmittersService.sendSseNotification(appNotification.getUser().getUserId(),
            appNotification),
        "SSE", appNotification.getNotificationId()
    );
  }

  private void sendFcmNotificationAsyncSafely(AppNotification appNotification) {
    Long userId = appNotification.getUser().getUserId();
    String fcmToken = appNotification.getUser().getFcmToken();
    
    // FCM 토큰 상태 상세 로깅
    log.info("FCM notification attempt: userId={}, notificationId={}, hasToken={}, tokenLength={}, tokenPrefix={}", 
        userId, appNotification.getNotificationId(), 
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
      log.debug("FCM notification sent successfully: id={}", appNotification.getNotificationId());

    } catch (CustomException e) {
      // FCM 관련 CustomException은 이미 FcmService에서 적절히 로깅됨
      updateFcmSentStatus(appNotification, false);
      log.debug("FCM notification failed with CustomException: id={}, errorCode={}",
          appNotification.getNotificationId(), e.getErrorCode());

      // FCM 토큰 관련 에러인 경우 로깅만 수행
      if (e.getErrorCode() == ErrorCode.FCM_TOKEN_NOT_FOUND) {
        log.warn("FCM token not found for user: {}, client should refresh token", 
            appNotification.getUser().getUserId());
      } else if (e.getErrorCode() == ErrorCode.FCM_TOKEN_REFRESH_REQUIRED) {
        log.warn("FCM token refresh required for user: {}, client should re-register token", 
            appNotification.getUser().getUserId());
      }

    } catch (Exception e) {
      // 예상치 못한 예외 (FcmService에서 모든 예외를 CustomException으로 변환하므로 발생하지 않아야 함)
      updateFcmSentStatus(appNotification, false);
      log.error("Unexpected FCM error: id={}, error={}",
          appNotification.getNotificationId(), e.getMessage(), e);
    }
  }

  @Transactional
  private void updateFcmSentStatus(AppNotification appNotification, boolean sent) {
    try {
      // 새로운 트랜잭션에서 FCM 전송 상태 업데이트
      AppNotification managedNotification = notificationRepository.findById(appNotification.getNotificationId())
          .orElse(null);
      if (managedNotification != null) {
        managedNotification.markFcmSent(sent);
        notificationRepository.save(managedNotification);
      }
    } catch (Exception e) {
      log.error("Failed to update FCM sent status: notificationId={}, error={}", 
          appNotification.getNotificationId(), e.getMessage());
    }
  }

  private void executeNotificationSafely(Runnable task, String type, Long id) {
    try {
      task.run();
      log.debug("{} notification sent: id={}", type, id);
    } catch (Exception e) {
      log.error("{} notification failed: id={}, error={}", type, id, e.getMessage());
    }
  }

  private void sendUnreadCountUpdate(Long userId) {
    executeNotificationSafely(
        () -> sseEmittersService.sendUnreadCountUpdate(userId),
        "UnreadCount", userId
    );
  }

  private void validateNotificationOwnership(AppNotification appNotification, Long userId) {
    if (!appNotification.getUser().getUserId().equals(userId)) {
      log.error("Unauthorized notification access: userId={}, notificationOwnerId={}",
          userId, appNotification.getUser().getUserId());
      throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
  }

  private NotificationItemDto toNotificationItemDto(NotificationListProjection projection) {
    return NotificationItemDto.builder()
        .notificationId(projection.getNotificationId())
        .content(projection.getContent())
        .type(Type.valueOf(projection.getType()))
        .isRead(projection.getIsRead())
        .createdAt(projection.getCreatedAt())
        .build();
  }

  private NotificationListResponseDto buildNotificationListResponse(Long userId, List<NotificationItemDto> notifications) {
    Long nextCursor = notifications.isEmpty() ? null :
        notifications.get(notifications.size() - 1).getNotificationId();

    boolean hasMore = nextCursor != null &&
        !notificationRepository.findAfterCursorByUserId(userId, nextCursor, 1).isEmpty();

    Long unreadCount = notificationRepository.countByUser_UserIdAndIsReadFalse(userId);

    return NotificationListResponseDto.builder()
        .notifications(notifications)
        .cursor(nextCursor)
        .hasMore(hasMore)
        .unreadCount(unreadCount)
        .build();
  }

  /**
   * 알림 생성 이벤트 클래스
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