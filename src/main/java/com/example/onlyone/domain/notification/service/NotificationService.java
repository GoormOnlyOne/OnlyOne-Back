package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationCreateResponseDto;
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
  @Transactional
  public void createNotification(User user, Type type, String... args) {
    NotificationType notificationType = findNotificationType(type);

    Notification notification = createNotification(user, notificationType, args);

    // 알림 전송 이벤트 발행
    eventPublisher.publishEvent(new NotificationCreatedEvent(notification));

    NotificationCreateResponseDto.from(notification);
  }

  /**
   * 알림 전송 처리
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Async
  public void handleNotificationCreated(NotificationCreatedEvent event) {
    Notification notification = event.getNotification();

    log.info("알림 전송 시작: id={}, type={}",
        notification.getId(),
        notification.getNotificationType().getType());

    // SSE로 알림 전송
    sendNotification(notification);
  }

  /**
   * 알림 목록 조회
   */
  @Transactional(readOnly = true)
  public NotificationListResponseDto getNotifications(Long userId, Long cursor, int size) {
    // 사용자 검증 및 조회 (한 번만)
    User user = findUser(userId);
    size = Math.min(size, 100);
    
    // hasMore 체크용
    List<NotificationItemDto> notifications =
        notificationRepository.findNotificationsByUserId(user.getUserId(), cursor, size + 1);

    return buildNotificationListResponse(user, notifications, size);
  }


  /**
   * 읽지 않은 알림 개수 조회
   */
  @Transactional(readOnly = true)
  public Long getUnreadCount(Long userId) {
    if (userId == null) {
      throw new CustomException(ErrorCode.USER_NOT_FOUND);
    }
    findUser(userId);
    
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

  @Transactional(propagation = Propagation.REQUIRED)
  public void markAllAsRead(Long userId) {
    long markedCount = notificationRepository.markAllAsReadByUserId(userId);

    if (markedCount > 0) {
      log.info("Marked {} notifications as read for user: {}", markedCount, userId);
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

  // 사용자 조회
  private User findUser(Long userId) {
    return findEntityOrThrow(
        userRepository.findById(userId),
        "User", userId, ErrorCode.USER_NOT_FOUND
    );
  }

  // 알림 타입 조회
  private NotificationType findNotificationType(Type type) {
    return findEntityOrThrow(
        notificationTypeRepository.findByType(type),
        "NotificationType", type, ErrorCode.NOTIFICATION_TYPE_NOT_FOUND
    );
  }

  // 알림 조회
  private Notification findNotification(Long notificationId) {
    Notification notification = notificationRepository.findByIdWithFetchJoin(notificationId);
    if (notification == null) {
      log.error("Notification not found: id={}", notificationId);
      throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
    return notification;
  }

  // 엔티티 조회
  private <T> T findEntityOrThrow(Optional<T> optional, String entityName, Object id, ErrorCode errorCode) {
    return optional.orElseThrow(() -> {
      log.error("{} not found: id={}", entityName, id);
      return new CustomException(errorCode);
    });
  }

  // 알림 생성 및 저장
  private Notification createNotification(User user, NotificationType type, String... args) {
    Notification notification = Notification.create(user, type, args);
    return notificationRepository.save(notification);
  }

  // 알림 전송
  private void sendNotification(Notification notification) {
    Long userId = notification.getUser().getUserId();

    log.info("Notification sending: userId={}, notificationId={}",
        userId, notification.getId());

    try {
      sseEmittersService.sendEvent(userId, "notification", notification);
      updateSseSentStatus(notification, true);

    } catch (Exception e) {
      // 전송 실패 처리
      updateSseSentStatus(notification, false);
      log.warn("Notification send failed for user: {}, error: {}", userId, e.getMessage());
    }
  }

  // 전송 상태 업데이트
  private void updateSseSentStatus(Notification notification, boolean sent) {
    try {
      // Repository 계층을 통한 상태 업데이트
      long updated = notificationRepository.updateSseSentStatus(notification.getId(), sent);
      
      if (updated > 0) {
        log.debug("Send status updated: notificationId={}, sent={}", notification.getId(), sent);
      }
    } catch (Exception e) {
      log.error("Failed to update send status: notificationId={}, error={}",
          notification.getId(), e.getMessage(), e);
      // 상태 업데이트 실패는 비즈니스 로직에 영향 주지 않으므로 예외를 던지지 않음
    }
  }


  // 소유권 검증
  private void validateNotificationOwnership(Notification notification, Long userId) {
    if (!notification.getUser().getUserId().equals(userId)) {
      log.error("Unauthorized notification access: userId={}, notificationOwnerId={}",
          userId, notification.getUser().getUserId());
      throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
  }


  // 알림 목록 응답 생성
  private NotificationListResponseDto buildNotificationListResponse(User user, List<NotificationItemDto> notifications, int requestedSize) {
    boolean hasMore = notifications.size() > requestedSize;
    
    // 실제 반환 데이터
    List<NotificationItemDto> actualNotifications = hasMore ? 
        notifications.subList(0, requestedSize) : notifications;
    
    Long nextCursor = actualNotifications.isEmpty() ? null :
        actualNotifications.getLast().getNotificationId();

    // User 객체가 이미 검증되었으므로 직접 조회
    Long unreadCount = notificationRepository.countUnreadByUserId(user.getUserId());
    unreadCount = unreadCount != null ? unreadCount : 0L;

    return NotificationListResponseDto.builder()
        .notifications(actualNotifications)
        .cursor(nextCursor)
        .hasMore(hasMore)
        .unreadCount(unreadCount)
        .build();
  }
  


}