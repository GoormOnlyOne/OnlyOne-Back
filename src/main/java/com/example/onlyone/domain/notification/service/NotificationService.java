package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.sse.SseEmittersService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.List;
import java.util.Optional;

/**
 * 알림 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

  private final UserRepository userRepository;
  private final NotificationTypeRepository notificationTypeRepository;
  private final NotificationRepository notificationRepository;
  private final SseEmittersService sseEmittersService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * 알림 생성 및 전송
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  @Async("dbTaskExecutor")
  public void createNotification(User user, Type type, String... args) {
    try {
      NotificationType notificationType = findNotificationTypeWithCache(type);
      Notification notification = createNotification(user, notificationType, args);
      
      eventPublisher.publishEvent(new NotificationCreatedEvent(notification));
      
      log.debug("Notification created: userId={}, type={}", user.getUserId(), type);
    } catch (Exception e) {
      log.error("Failed to create notification: userId={}, type={}", user.getUserId(), type, e);
      throw new CustomException(ErrorCode.NOTIFICATION_PROCESSING_FAILED);
    }
  }

  /**
   * 알림 전송 처리
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Async("notificationExecutor")
  public void handleNotificationCreated(NotificationCreatedEvent event) {
    Notification notification = event.getNotification();

    log.info("알림 전송 시작: id={}, type={}, thread={}",
        notification.getId(),
        notification.getNotificationType().getType(),
        Thread.currentThread().getName());

    sendNotification(notification);
  }

  /**
   * 알림 목록 조회 (Redis 캐시 적용)
   */
  @Cacheable(value = "notifications", key = "'user:' + #userId + ':' + (#cursor != null ? #cursor : 'null') + ':' + #size", unless = "#result.notifications.size() == 0")
  @Transactional(readOnly = true, timeout = 10)
  public NotificationListResponseDto getNotifications(Long userId, Long cursor, int size) {
    if (userId == null || userId <= 0) {
      throw new CustomException(ErrorCode.USER_NOT_FOUND);
    }
    size = Math.min(size, 50);
    
    User user = findUser(userId);
    List<NotificationItemDto> notifications = 
        notificationRepository.findNotificationsByUserId(userId, cursor, size + 1);

    return buildNotificationListResponse(user, notifications, size);
  }

  /**
   * 읽지 않은 알림 개수 조회 (Redis 캐시 적용)
   */
  @Cacheable(value = "unreadCount", key = "'user:' + #userId")
  @Transactional(readOnly = true, timeout = 5)
  public Long getUnreadCount(Long userId) {
    if (userId == null || userId <= 0) {
      throw new CustomException(ErrorCode.USER_NOT_FOUND);
    }
    
    Long count = notificationRepository.countUnreadByUserId(userId);
    return count != null ? count : 0L;
  }

  /**
   * 알림 읽음 처리
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void markAsRead(Long notificationId, Long userId) {
    Notification notification = findNotification(notificationId);
    validateNotificationOwnership(notification, userId);
    notification.markAsRead();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 15)
  @Async("dbTaskExecutor")
  public void markAllAsRead(Long userId) {
    try {
      long markedCount = notificationRepository.markAllAsReadByUserId(userId);
      
      if (markedCount > 0) {
        log.debug("Marked {} notifications as read for user: {}", markedCount, userId);
      }
    } catch (Exception e) {
      log.error("Failed to mark all notifications as read: userId={}", userId, e);
      throw new CustomException(ErrorCode.NOTIFICATION_PROCESSING_FAILED);
    }
  }

  /**
   * 알림 삭제
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void deleteNotification(Long userId, Long notificationId) {
    Notification notification = findNotification(notificationId);
    validateNotificationOwnership(notification, userId);

    notificationRepository.delete(notification);

    log.info("Notification deleted: id={}", notificationId);
  }

  // === Private Methods ===

  private User findUser(Long userId) {
    return userRepository.findById(userId)
        .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
  }

  private NotificationType findNotificationType(Type type) {
    return notificationTypeRepository.findByType(type)
        .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND));
  }

  @Cacheable(value = "notificationTypes", key = "#type.name()")
  private NotificationType findNotificationTypeWithCache(Type type) {
    return findNotificationType(type);
  }

  private Notification findNotification(Long notificationId) {
    Notification notification = notificationRepository.findByIdWithFetchJoin(notificationId);
    if (notification == null) {
      throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
    return notification;
  }

  private Notification createNotification(User user, NotificationType type, String... args) {
    try {
      Notification notification = Notification.create(user, type, args);
      return notificationRepository.save(notification);
    } catch (Exception e) {
      log.error("Failed to create notification: userId={}, type={}", user.getUserId(), type.getType(), e);
      throw new CustomException(ErrorCode.DATABASE_OPERATION_FAILED);
    }
  }

  private NotificationListResponseDto buildNotificationListResponse(User user, List<NotificationItemDto> notifications, int requestedSize) {
    boolean hasMore = notifications.size() > requestedSize;
    
    List<NotificationItemDto> actualNotifications = hasMore ? 
        notifications.subList(0, requestedSize) : notifications;
    
    Long nextCursor = actualNotifications.isEmpty() ? null :
        actualNotifications.get(actualNotifications.size() - 1).getNotificationId();

    Long unreadCount = notificationRepository.countUnreadByUserId(user.getUserId());
    unreadCount = unreadCount != null ? unreadCount : 0L;

    return NotificationListResponseDto.builder()
        .notifications(actualNotifications)
        .cursor(nextCursor)
        .hasMore(hasMore)
        .unreadCount(unreadCount)
        .build();
  }

  private void sendNotification(Notification notification) {
    Long userId = notification.getUser().getUserId();

    log.info("Notification sending: userId={}, notificationId={}", userId, notification.getId());

    sseEmittersService.sendEvent(userId, "notification", notification)
        .thenAcceptAsync(success -> {
          if (success) {
            updateSseSentStatus(notification, true);
            log.debug("Notification sent via persistent connection: userId={}", userId);
          } else {
            updateSseSentStatus(notification, false);
            log.debug("User offline, notification stored for later: userId={}", userId);
          }
        })
        .exceptionally(throwable -> {
          log.debug("Persistent connection failed: userId={}", userId);
          updateSseSentStatus(notification, false);
          return null;
        });
  }
  

  @Async("notificationExecutor")
  private void updateSseSentStatus(Notification notification, boolean sent) {
    try {
      long updated = notificationRepository.updateSseSentStatus(notification.getId(), sent);
      
      if (updated > 0) {
        log.debug("Send status updated: notificationId={}, sent={}", notification.getId(), sent);
      }
    } catch (Exception e) {
      log.error("Failed to update send status: notificationId={}, error={}",
          notification.getId(), e.getMessage(), e);
    }
  }

  private void validateNotificationOwnership(Notification notification, Long userId) {
    if (!notification.getUser().getUserId().equals(userId)) {
      log.error("Unauthorized notification access: userId={}, notificationOwnerId={}",
          userId, notification.getUser().getUserId());
      throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
  }

}