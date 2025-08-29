package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.request.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.request.BatchNotificationRequestDto;
import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.data.redis.core.RedisTemplate;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 알림 서비스
 */
@Service
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
  private final RedisHealthChecker redisHealthChecker;
  private final MeterRegistry meterRegistry;
  
  // 알림 타입 캐시
  private final Map<Type, NotificationType> typeCache = new ConcurrentHashMap<>();
  
  // 메트릭
  private final Timer notificationCreationTimer;
  private final Counter notificationCreatedCounter;
  private final Counter sseNotificationCounter;
  private final Counter fcmNotificationCounter;

  public NotificationService(UserRepository userRepository,
                           NotificationTypeRepository notificationTypeRepository,
                           NotificationRepository notificationRepository,
                           SseEmittersService sseEmittersService,
                           FcmService fcmService,
                           ApplicationEventPublisher eventPublisher,
                           JPAQueryFactory queryFactory,
                           RedisTemplate<String, Object> redisTemplate,
                           RedisHealthChecker redisHealthChecker,
                           MeterRegistry meterRegistry) {
    this.userRepository = userRepository;
    this.notificationTypeRepository = notificationTypeRepository;
    this.notificationRepository = notificationRepository;
    this.sseEmittersService = sseEmittersService;
    this.fcmService = fcmService;
    this.eventPublisher = eventPublisher;
    this.queryFactory = queryFactory;
    this.redisTemplate = redisTemplate;
    this.redisHealthChecker = redisHealthChecker;
    this.meterRegistry = meterRegistry;
    
    // 메트릭 초기화
    this.notificationCreationTimer = Timer.builder("notification.creation.duration")
        .description("알림 생성 시간")
        .register(meterRegistry);
        
    this.notificationCreatedCounter = Counter.builder("notification.created.total")
        .description("생성된 알림 수")
        .register(meterRegistry);
        
    this.sseNotificationCounter = Counter.builder("sse.notifications.sent.total")
        .description("SSE로 전송된 알림 수")
        .register(meterRegistry);
        
    this.fcmNotificationCounter = Counter.builder("fcm.notifications.sent.total")
        .description("FCM으로 전송된 알림 수")
        .register(meterRegistry);
  }

  /**
   * 알림 생성
   */
  @Transactional
  public NotificationCreateResponseDto createNotification(NotificationCreateRequestDto requestDto) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      User user = findUser(requestDto.getUserId());
      NotificationType type = findNotificationType(requestDto.getType());

      AppNotification appNotification = createAndSaveNotification(user, type, requestDto.getArgs());

      // Redis 캐시 무효화
      evictUnreadCountCache(requestDto.getUserId());
      
      // 알림 전송 이벤트 발행
      eventPublisher.publishEvent(new NotificationCreatedEvent(appNotification));

      // 메트릭 카운터 증가
      notificationCreatedCounter.increment();

      return NotificationCreateResponseDto.from(appNotification);
    } finally {
      sample.stop(notificationCreationTimer);
    }
  }

  /**
   * 알림 생성 (편의 메서드)
   */
  @Transactional
  public NotificationCreateResponseDto createNotification(User user, Type type, String... args) {
    Timer.Sample sample = Timer.start(meterRegistry);
    try {
      NotificationType notificationType = findNotificationType(type);
      AppNotification appNotification = createAndSaveNotification(user, notificationType, args);

      // Redis 캐시 무효화
      evictUnreadCountCache(user.getUserId());
      
      // 알림 전송 이벤트 발행
      eventPublisher.publishEvent(new NotificationCreatedEvent(appNotification));

      // 메트릭 카운터 증가
      notificationCreatedCounter.increment();

      return NotificationCreateResponseDto.from(appNotification);
    } finally {
      sample.stop(notificationCreationTimer);
    }
  }



  /**
   * 알림 전송 처리
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

    // 전송 방식별 처리
    if (deliveryMethod.shouldSendSse()) {
        sendSseNotificationSafely(appNotification);
    }

    if (deliveryMethod.shouldSendFcm()) {
        sendFcmNotificationAsyncSafely(appNotification);
    }
  }

  /**
   * 알림 목록 조회
   */
  @Transactional(readOnly = true)
  public NotificationListResponseDto getNotifications(Long userId, Long cursor, int size) {
    size = Math.min(size, 100);
    
    // hasMore 체크용
    List<NotificationItemDto> notifications =
        notificationRepository.findNotificationsByUserId(userId, cursor, size + 1);

    return buildNotificationListResponse(userId, notifications, size);
  }

  /**
   * 타입별 알림 목록 조회
   */
  @Transactional(readOnly = true)
  public NotificationListResponseDto getNotificationsByType(Long userId, Type type, Long cursor, int size) {
    size = Math.min(size, 100);
    
    // hasMore 체크용
    List<NotificationItemDto> notifications =
        notificationRepository.findNotificationsByUserIdAndType(userId, type, cursor, size + 1);

    return buildNotificationListResponseByType(userId, notifications, size);
  }

  /**
   * 읽지 않은 알림 개수 조회
   */
  @Transactional(readOnly = true)
  public Long getUnreadCount(Long userId) {
    // 사용자 확인
    if (userId == null) {
      throw new CustomException(ErrorCode.USER_NOT_FOUND);
    }
    findUser(userId);
    
    try {
      // Redis 캐시 확인
      String cacheKey = "notification:unread:" + userId;
      Object cached = redisTemplate.opsForValue().get(cacheKey);
      
      if (cached != null) {
        log.debug("Unread count cache hit: userId={}", userId);
        return Long.valueOf(cached.toString());
      }
      
      // DB 조회
      Long count = notificationRepository.countUnreadByUserId(userId);
      Long result = count != null ? count : 0L;
      
      // Redis 캐싱
      redisTemplate.opsForValue().set(cacheKey, result, Duration.ofMinutes(5));
      
      log.debug("Unread count cached: userId={}, count={}", userId, result);
      return result;
      
    } catch (Exception e) {
      log.warn("Redis cache error, falling back to DB query: userId={}", userId, e);
      try {
        Long count = notificationRepository.countUnreadByUserId(userId);
        return count != null ? count : 0L;
      } catch (Exception dbException) {
        log.error("Database fallback also failed for userId: {}", userId, dbException);
        throw new CustomException(ErrorCode.DATABASE_OPERATION_FAILED);
      }
    }
  }

  /**
   * 알림 읽음 처리
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void markAsRead(Long notificationId, Long userId) {
    AppNotification notification = findNotification(notificationId);
    validateNotificationOwnership(notification, userId);
    notification.markAsRead();
    
    // 캐시 무효화
    evictUnreadCountCache(userId);
  }

  @Transactional(propagation = Propagation.REQUIRED)
  public void markAllAsRead(Long userId) {
    long markedCount = notificationRepository.markAllAsReadByUserId(userId);

    if (markedCount > 0) {
      // 캐시 무효화
      evictUnreadCountCache(userId);
      
      sendUnreadCountUpdate(userId);
      log.info("Marked {} notifications as read for user: {}", markedCount, userId);
    }
  }

  /**
   * 알림 삭제
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public void deleteNotification(Long userId, Long notificationId) {
    AppNotification appNotification = findNotification(notificationId);
    validateNotificationOwnership(appNotification, userId);

    boolean wasUnread = !appNotification.isRead();
    notificationRepository.delete(appNotification);

    if (wasUnread) {
      // 캐시 무효화
      evictUnreadCountCache(userId);
      sendUnreadCountUpdate(userId);
    }

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
  private AppNotification findNotification(Long notificationId) {
    AppNotification notification = notificationRepository.findByIdWithFetchJoin(notificationId);
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

  // 알림 생성
  private AppNotification createAndSaveNotification(User user, NotificationType type, String... args) {
    AppNotification appNotification = AppNotification.create(user, type, args);
    return notificationRepository.save(appNotification);
  }

  // SSE 알림 전송
  private void sendSseNotificationSafely(AppNotification appNotification) {
    // Redis 상태 확인
    if (!redisHealthChecker.isHealthy()) {
      log.warn("Redis is unhealthy, falling back to FCM for notification: id={}", appNotification.getId());
      sendFcmAsFallback(appNotification);
      return;
    }
    
    try {
      sseEmittersService.sendSseNotification(appNotification.getUser().getUserId(), appNotification);
      updateSseSentStatus(appNotification, true);
      sseNotificationCounter.increment();
      log.debug("SSE notification sent successfully: notificationId={}", appNotification.getId());
    } catch (CustomException e) {
      updateSseSentStatus(appNotification, false);
      log.error("SSE notification failed with CustomException: id={}, errorCode={}, error={}", 
                appNotification.getId(), e.getErrorCode(), e.getMessage());
      
      // SSE 실패 시 FCM으로 폴백
      sendFcmAsFallback(appNotification);
      
    } catch (Exception e) {
      updateSseSentStatus(appNotification, false);
      log.error("SSE notification failed: id={}, error={}", appNotification.getId(), e.getMessage(), e);
      
      // SSE 실패 시 FCM으로 폴백
      sendFcmAsFallback(appNotification);
    }
  }
  
  // FCM 폴백 전송
  private void sendFcmAsFallback(AppNotification appNotification) {
    try {
      // 중복 전송 방지
      if (appNotification.isFcmSent()) {
        log.debug("FCM already sent for notification: id={}", appNotification.getId());
        return;
      }
      
      // FCM 토큰 확인
      String fcmToken = appNotification.getUser().getFcmToken();
      if (fcmToken == null || fcmToken.isBlank()) {
        log.warn("Cannot fallback to FCM - no token for user: {}", appNotification.getUser().getUserId());
        return;
      }
      
      log.info("Attempting FCM fallback for failed SSE notification: id={}", appNotification.getId());
      fcmService.sendFcmNotification(appNotification);
      updateFcmSentStatus(appNotification, true);
      log.info("FCM fallback successful for notification: id={}", appNotification.getId());
      
    } catch (CustomException e) {
      log.error("FCM fallback failed with CustomException: id={}, errorCode={}, error={}", 
                appNotification.getId(), e.getErrorCode(), e.getMessage());
    } catch (Exception e) {
      log.error("FCM fallback also failed for notification: id={}, error={}", 
                appNotification.getId(), e.getMessage(), e);
    }
  }

  // FCM 알림 전송
  private void sendFcmNotificationAsyncSafely(AppNotification appNotification) {
    Long userId = appNotification.getUser().getUserId();
    String fcmToken = appNotification.getUser().getFcmToken();

    // FCM 토큰 확인
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
      fcmNotificationCounter.increment();

    } catch (CustomException e) {
      // 예외 처리
      updateFcmSentStatus(appNotification, false);

      // 토큰 에러 처리
      if (e.getErrorCode() == ErrorCode.FCM_TOKEN_NOT_FOUND) {
        log.warn("FCM token not found for user: {}, client should refresh token",
            appNotification.getUser().getUserId());
      } else if (e.getErrorCode() == ErrorCode.FCM_TOKEN_REFRESH_REQUIRED) {
        log.warn("FCM token refresh required for user: {}, client should re-register token",
            appNotification.getUser().getUserId());
      }
      
      // CustomException 재던지기 - 이미 적절한 ErrorCode를 가지고 있음
      throw e;

    } catch (Exception e) {
      // 예외 처리
      updateFcmSentStatus(appNotification, false);
      log.error("Unexpected FCM error: id={}, error={}",
          appNotification.getId(), e.getMessage(), e);
      throw new CustomException(ErrorCode.NOTIFICATION_PROCESSING_FAILED);
    }
  }

  // FCM 전송 상태 업데이트
  private void updateFcmSentStatus(AppNotification appNotification, boolean sent) {
    try {
      // 상태 업데이트
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
          appNotification.getId(), e.getMessage(), e);
      // 상태 업데이트 실패는 비즘이스 로직에 영향 주지 않으므로 예외를 던지지 않음
    }
  }

  // SSE 전송 상태 업데이트
  private void updateSseSentStatus(AppNotification appNotification, boolean sent) {
    try {
      // 상태 업데이트
      long updated = queryFactory
          .update(QAppNotification.appNotification)
          .set(QAppNotification.appNotification.sseSent, sent)
          .where(QAppNotification.appNotification.id.eq(appNotification.getId()))
          .execute();
      
      if (updated > 0) {
        log.debug("SSE status updated: notificationId={}, sent={}", appNotification.getId(), sent);
      }
    } catch (Exception e) {
      log.error("Failed to update SSE sent status: notificationId={}, error={}",
          appNotification.getId(), e.getMessage(), e);
      // 상태 업데이트 실패는 비즘이스 로직에 영향 주지 않으므로 예외를 던지지 않음
    }
  }

  // 안전한 알림 전송
  private void executeNotificationSafely(Runnable task, String type, Long id) {
    try {
      task.run();
    } catch (CustomException e) {
      log.error("{} notification failed: id={}, errorCode={}, error={}", type, id, e.getErrorCode(), e.getMessage());
      // CustomException은 로깅만 하고 전파하지 않음 (안전하게 전송 실행)
    } catch (Exception e) {
      log.error("{} notification failed: id={}, error={}", type, id, e.getMessage(), e);
      // 일반 예외도 로깅만 하고 전파하지 않음
    }
  }

  // 읽지 않은 개수 전송

  private void sendUnreadCountUpdate(Long userId) {
    executeNotificationSafely(
        () -> sseEmittersService.sendUnreadCountUpdate(userId),
        "UnreadCount", userId
    );
  }

  // 캐시 무효화
  private void evictUnreadCountCache(Long userId) {
    try {
      String cacheKey = "notification:unread:" + userId;
      redisTemplate.delete(cacheKey);
      log.debug("Evicted unread count cache: userId={}", userId);
    } catch (Exception e) {
      log.warn("Failed to evict cache for userId: {}, falling back gracefully", userId, e);
      // 캐시 무효화 실패는 비즘이스 로직에 영향 주지 않으므로 예외를 던지지 않음
    }
  }

  // 소유권 검증
  private void validateNotificationOwnership(AppNotification appNotification, Long userId) {
    if (!appNotification.getUser().getUserId().equals(userId)) {
      log.error("Unauthorized notification access: userId={}, notificationOwnerId={}",
          userId, appNotification.getUser().getUserId());
      throw new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND);
    }
  }


  // 알림 목록 응답 생성
  private NotificationListResponseDto buildNotificationListResponse(Long userId, List<NotificationItemDto> notifications, int requestedSize) {
    boolean hasMore = notifications.size() > requestedSize;
    
    // 실제 반환 데이터
    List<NotificationItemDto> actualNotifications = hasMore ? 
        notifications.subList(0, requestedSize) : notifications;
    
    Long nextCursor = actualNotifications.isEmpty() ? null :
        actualNotifications.get(actualNotifications.size() - 1).getNotificationId();

    // 직접 DB 조회로 재귀 호출 방지
    Long unreadCount = notificationRepository.countUnreadByUserId(userId);
    unreadCount = unreadCount != null ? unreadCount : 0L;

    return NotificationListResponseDto.builder()
        .notifications(actualNotifications)
        .cursor(nextCursor)
        .hasMore(hasMore)
        .unreadCount(unreadCount)
        .build();
  }
  
  // 타입별 알림 응답 생성
  private NotificationListResponseDto buildNotificationListResponseByType(Long userId, List<NotificationItemDto> notifications, int requestedSize) {
    boolean hasMore = notifications.size() > requestedSize;
    
    // 실제 반환 데이터
    List<NotificationItemDto> actualNotifications = hasMore ? 
        notifications.subList(0, requestedSize) : notifications;
    
    Long nextCursor = actualNotifications.isEmpty() ? null :
        actualNotifications.get(actualNotifications.size() - 1).getNotificationId();

    // 직접 DB 조회로 재귀 호출 방지
    Long unreadCount = notificationRepository.countUnreadByUserId(userId);
    unreadCount = unreadCount != null ? unreadCount : 0L;

    return NotificationListResponseDto.builder()
        .notifications(actualNotifications)
        .cursor(nextCursor)
        .hasMore(hasMore)
        .unreadCount(unreadCount)
        .build();
  }

  /**
   * 배치 알림 생성
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int createBatchNotifications(List<BatchNotificationRequestDto> requests) {
    if (requests.isEmpty()) {
      return 0;
    }
    
    // 타입 캐시 준비
    prepareTypeCache(requests);
    
    // 사용자 조회
    List<Long> userIds = requests.stream()
        .map(BatchNotificationRequestDto::getUserId)
        .distinct()
        .collect(Collectors.toList());
    Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
        .collect(Collectors.toMap(User::getUserId, u -> u));
    
    // 배치 처리
    List<AppNotification> notifications = new ArrayList<>();
    for (BatchNotificationRequestDto request : requests) {
      User user = userMap.get(request.getUserId());
      NotificationType type = typeCache.get(request.getType());
      
      if (user != null && type != null) {
        AppNotification notification = AppNotification.create(user, type, request.getArgs());
        notifications.add(notification);
      }
    }
    
    // 배치 저장
    List<AppNotification> saved = notificationRepository.saveAll(notifications);
    
    log.info("Batch inserted {} notifications", saved.size());
    return saved.size();
  }
  
  private void prepareTypeCache(List<BatchNotificationRequestDto> requests) {
    List<Type> types = requests.stream()
        .map(BatchNotificationRequestDto::getType)
        .distinct()
        .filter(type -> !typeCache.containsKey(type))
        .collect(Collectors.toList());
    
    if (!types.isEmpty()) {
      List<NotificationType> notificationTypes = notificationTypeRepository.findAllByTypeIn(types);
      notificationTypes.forEach(nt -> typeCache.put(nt.getType(), nt));
    }
  }

}