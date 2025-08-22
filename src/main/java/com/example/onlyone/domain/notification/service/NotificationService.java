package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.DeliveryMethod;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.QAppNotification;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;

import java.time.Duration;
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
  private final JPAQueryFactory queryFactory;
  private final RedisTemplate<String, Object> redisTemplate;

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
   * 알림 생성 편의 메서드 (다른 서비스에서 사용) - 하위 호환성
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
    
    // hasMore 체크를 위해 size + 1 개를 조회
    List<NotificationItemDto> notifications =
        notificationRepository.findNotificationsByUserId(userId, cursor, size + 1);

    return buildNotificationListResponse(userId, notifications, size);
  }

  /**
   * 특정 타입의 알림 목록 조회 (QueryDSL 사용)
   */
  @Transactional(readOnly = true)
  public NotificationListResponseDto getNotificationsByType(Long userId, Type type, Long cursor, int size) {
    size = Math.min(size, 100); // 최대 100개 제한
    
    // hasMore 체크를 위해 size + 1 개를 조회
    List<NotificationItemDto> notifications =
        notificationRepository.findNotificationsByUserIdAndType(userId, type, cursor, size + 1);

    return buildNotificationListResponseByType(userId, notifications, size);
  }

  /**
   * 읽지 않은 알림 개수 조회 (Redis 캐싱 적용)
   */
  @Transactional(readOnly = true)
  @Cacheable(value = "unreadCount", key = "#userId", unless = "#result == null")
  public Long getUnreadCount(Long userId) {
    try {
      // Redis 캐시에서 먼저 확인
      String cacheKey = "notification:unread:" + userId;
      Object cached = redisTemplate.opsForValue().get(cacheKey);
      
      if (cached != null) {
        log.debug("Unread count cache hit: userId={}", userId);
        return Long.valueOf(cached.toString());
      }
      
      // 캐시 미스 시 DB 조회
      Long count = notificationRepository.countUnreadByUserId(userId);
      Long result = count != null ? count : 0L;
      
      // Redis에 5분간 캐싱
      redisTemplate.opsForValue().set(cacheKey, result, Duration.ofMinutes(5));
      
      log.debug("Unread count cached: userId={}, count={}", userId, result);
      return result;
      
    } catch (Exception e) {
      log.warn("Redis cache error, falling back to DB query", e);
      Long count = notificationRepository.countUnreadByUserId(userId);
      return count != null ? count : 0L;
    }
  }

  /**
   * 모든 알림 읽음 처리 (개별 엔티티 업데이트)
   */
  @Transactional
  @CacheEvict(value = "unreadCount", key = "#userId")
  public void markAsRead(Long notificationId, Long userId) {
    AppNotification notification = findNotification(notificationId);
    validateNotificationOwnership(notification, userId);
    notification.markAsRead();
    
    // Redis 캐시도 함께 무효화
    evictUnreadCountCache(userId);
  }

  @Transactional
  @CacheEvict(value = "unreadCount", key = "#userId")
  public void markAllAsRead(Long userId) {
    long markedCount = notificationRepository.markAllAsReadByUserId(userId);

    if (markedCount > 0) {
      // Redis 캐시 무효화
      evictUnreadCountCache(userId);
      
      sendUnreadCountUpdate(userId); // SSE로 읽지 않은 개수 업데이트 전송
      log.info("Marked {} notifications as read for user: {}", markedCount, userId);
    }
  }

  /**
   * 알림 삭제 (소유권 검증 포함)
   */
  @Transactional
  @CacheEvict(value = "unreadCount", key = "#userId")
  public void deleteNotification(Long userId, Long notificationId) {
    AppNotification appNotification = findNotification(notificationId);
    validateNotificationOwnership(appNotification, userId);

    boolean wasUnread = !appNotification.isRead();
    notificationRepository.delete(appNotification);

    if (wasUnread) {
      // Redis 캐시 무효화
      evictUnreadCountCache(userId);
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

  // 알림 조회 (fetchJoin 포함, 예외 처리 포함)
  private AppNotification findNotification(Long notificationId) {
    AppNotification notification = notificationRepository.findByIdWithFetchJoin(notificationId);
    if (notification == null) {
      log.error("Notification not found: id={}", notificationId);
      throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
    return notification;
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

  // FCM 전송 상태 업데이트 (최적화된 직접 업데이트)
  @Transactional
  private void updateFcmSentStatus(AppNotification appNotification, boolean sent) {
    try {
      // QueryDSL로 직접 업데이트하여 재조회 방지
      long updated = queryFactory
          .update(QAppNotification.appNotification)
          .set(QAppNotification.appNotification.fcmSent, sent)
          .where(QAppNotification.appNotification.id.eq(appNotification.getId()))
          .execute();
      
      if (updated > 0) {
        log.debug("FCM status updated: notificationId={}, sent={}", appNotification.getId(), sent);
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

  // Redis 캐시 무효화 헬퍼 메서드
  private void evictUnreadCountCache(Long userId) {
    try {
      String cacheKey = "notification:unread:" + userId;
      redisTemplate.delete(cacheKey);
      log.debug("Evicted unread count cache: userId={}", userId);
    } catch (Exception e) {
      log.warn("Failed to evict cache for userId: {}", userId, e);
    }
  }

  // 알림 소유권 검증 (보안)
  private void validateNotificationOwnership(AppNotification appNotification, Long userId) {
    if (!appNotification.getUser().getUserId().equals(userId)) {
      log.error("Unauthorized notification access: userId={}, notificationOwnerId={}",
          userId, appNotification.getUser().getUserId());
      throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
  }


  // NotificationListResponseDto 빌드 (최적화된 hasMore 체크, 중복 쿼리 제거)
  private NotificationListResponseDto buildNotificationListResponse(Long userId, List<NotificationItemDto> notifications, int requestedSize) {
    boolean hasMore = notifications.size() > requestedSize;
    
    // 실제 반환할 데이터는 요청된 크기만큼만
    List<NotificationItemDto> actualNotifications = hasMore ? 
        notifications.subList(0, requestedSize) : notifications;
    
    Long nextCursor = actualNotifications.isEmpty() ? null :
        actualNotifications.get(actualNotifications.size() - 1).getNotificationId();

    // 중복 쿼리 방지: 한 번만 조회하여 재사용
    Long unreadCount = getUnreadCount(userId);

    return NotificationListResponseDto.builder()
        .notifications(actualNotifications)
        .cursor(nextCursor)
        .hasMore(hasMore)
        .unreadCount(unreadCount)
        .build();
  }
  
  // 타입별 조회용 별도 메서드 (중복 쿼리 제거)
  private NotificationListResponseDto buildNotificationListResponseByType(Long userId, List<NotificationItemDto> notifications, int requestedSize) {
    boolean hasMore = notifications.size() > requestedSize;
    
    // 실제 반환할 데이터는 요청된 크기만큼만
    List<NotificationItemDto> actualNotifications = hasMore ? 
        notifications.subList(0, requestedSize) : notifications;
    
    Long nextCursor = actualNotifications.isEmpty() ? null :
        actualNotifications.get(actualNotifications.size() - 1).getNotificationId();

    // 중복 쿼리 방지: 한 번만 조회하여 재사용
    Long unreadCount = getUnreadCount(userId);

    return NotificationListResponseDto.builder()
        .notifications(actualNotifications)
        .cursor(nextCursor)
        .hasMore(hasMore)
        .unreadCount(unreadCount)
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