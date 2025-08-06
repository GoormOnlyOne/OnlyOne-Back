package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

  /**
   * 알림 생성 및 전송
   */
  @Transactional
  public NotificationCreateResponseDto createNotification(NotificationCreateRequestDto requestDto) {
    log.debug("Creating notification: userId={}, type={}", requestDto.getUserId(), requestDto.getType());

    User user = findUser(requestDto.getUserId());
    NotificationType type = findNotificationType(requestDto.getType());

    Notification notification = createAndSaveNotification(user, type, requestDto.getArgs());
    sendNotifications(notification);

    log.info("Notification created: id={}", notification.getNotificationId());
    return NotificationCreateResponseDto.from(notification);
  }

  /**
   * 알림 목록 조회 (커서 기반 페이징)
   */
  @Transactional(readOnly = true)
  public NotificationListResponseDto getNotifications(Long userId, Long cursor, int size) {
    log.debug("Fetching notifications: userId={}, cursor={}, size={}", userId, cursor, size);

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
   * 모든 알림 읽음 처리
   */
  @Transactional
  public void markAllAsRead(Long userId) {
    log.debug("Marking all notifications as read: userId={}", userId);

    List<Notification> unreadNotifications = notificationRepository.findByUser_UserIdAndIsReadFalse(userId);
    if (unreadNotifications.isEmpty()) {
      return;
    }

    unreadNotifications.forEach(Notification::markAsRead);
    sendUnreadCountUpdate(userId);

    log.info("Marked {} notifications as read for user: {}", unreadNotifications.size(), userId);
  }

  /**
   * 알림 삭제
   */
  @Transactional
  public void deleteNotification(Long userId, Long notificationId) {
    log.debug("Deleting notification: userId={}, notificationId={}", userId, notificationId);

    Notification notification = findNotification(notificationId);
    validateNotificationOwnership(notification, userId);

    boolean wasUnread = !notification.getIsRead();
    notificationRepository.delete(notification);

    if (wasUnread) {
      sendUnreadCountUpdate(userId);
    }

    log.info("Notification deleted: id={}", notificationId);
  }

  /**
   * 알림 생성 이벤트 처리
   */
  public void sendCreated(Notification notification) {
    sendSseNotificationSafely(notification);
  }

  /**
   * 알림 읽음 이벤트 처리
   */
  public void sendRead(Notification notification) {
    sendUnreadCountUpdate(notification.getUser().getUserId());
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

  private Notification findNotification(Long notificationId) {
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

  private Notification createAndSaveNotification(User user, NotificationType type, String... args) {
    Notification notification = Notification.create(user, type, args);
    return notificationRepository.save(notification);
  }

  private void sendNotifications(Notification notification) {
    sendSseNotificationSafely(notification);
    sendFcmNotificationSafely(notification);
  }

  private void sendSseNotificationSafely(Notification notification) {
    executeNotificationSafely(
        () -> sseEmittersService.sendSseNotification(notification.getUser().getUserId(), notification),
        "SSE", notification.getNotificationId()
    );
  }

  private void sendFcmNotificationSafely(Notification notification) {
    try {
      fcmService.sendFcmNotification(notification);
      notification.markFcmSent(true);
      log.debug("FCM notification sent successfully: id={}", notification.getNotificationId());

    } catch (CustomException e) {
      // FCM 관련 CustomException은 이미 FcmService에서 적절히 로깅됨
      notification.markFcmSent(false);
      log.debug("FCM notification failed with CustomException: id={}, errorCode={}",
          notification.getNotificationId(), e.getErrorCode());

      // FCM 토큰 관련 에러인 경우 추가 처리 가능
      if (e.getErrorCode() == ErrorCode.FCM_TOKEN_NOT_FOUND) {
        handleInvalidFcmToken(notification.getUser());
      }

    } catch (Exception e) {
      // 예상치 못한 예외 (FcmService에서 모든 예외를 CustomException으로 변환하므로 발생하지 않아야 함)
      notification.markFcmSent(false);
      log.error("Unexpected FCM error: id={}, error={}",
          notification.getNotificationId(), e.getMessage(), e);
    }
  }

  private void handleInvalidFcmToken(User user) {
    try {
      // FCM 토큰이 무효한 경우 처리 로직
      log.info("Handling invalid FCM token for user: {}", user.getUserId());
      // 예: user.clearFcmToken();
      // 예: 토큰 갱신 이벤트 발행
    } catch (Exception e) {
      log.error("Failed to handle invalid FCM token for user: {}, error={}",
          user.getUserId(), e.getMessage());
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

  private void validateNotificationOwnership(Notification notification, Long userId) {
    if (!notification.getUser().getUserId().equals(userId)) {
      log.error("Unauthorized notification access: userId={}, notificationOwnerId={}",
          userId, notification.getUser().getUserId());
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
}