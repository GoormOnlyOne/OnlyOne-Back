package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.responseDto.SseNotificationDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.DisposableBean;

/**
 * SSE 연결 관리 서비스 - Last-Event-ID 지원
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SseEmittersService implements InitializingBean, DisposableBean {

  @Value("${app.notification.sse-timeout-millis:1800000}") // 기본값 30분
  private long sseTimeoutMillis;

  private final ConcurrentHashMap<Long, SseConnection> activeConnections = new ConcurrentHashMap<>();
  private final NotificationRepository notificationRepository;
  private final RedisTemplate<String, Object> redisTemplate;
  
  // 주기적인 정리를 위한 스케줄러
  private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
      r -> new Thread(r, "sse-cleanup-thread"));
  
  private static final String SSE_CONNECTION_KEY = "sse:connections";
  private static final String SSE_CONNECTION_INFO_KEY = "sse:connection_info:";
  private static final int CONNECTION_INFO_TTL = 3600; // 1시간
  private static final long CLEANUP_INTERVAL_MINUTES = 10; // 10분마다 정리

  /**
   * SSE 연결 생성 (기존 호환성)
   */
  public SseEmitter createSseConnection(Long userId) {
    return createSseConnection(userId, null);
  }

  /**
   * SSE 연결 생성 - Last-Event-ID 지원
   */
  public SseEmitter createSseConnection(Long userId, String lastEventId) {
    cleanupExistingConnection(userId);
    
    SseConnection connection = SseConnection.builder()
            .userId(userId)
            .emitter(new SseEmitter(sseTimeoutMillis))
            .connectionTime(LocalDateTime.now())
            .lastEventId(lastEventId)
            .build();
    
    activeConnections.put(userId, connection);
    registerConnectionCallbacks(connection);
    registerConnectionToRedis(userId, connection);
    
    if (sendInitialHeartbeat(connection)) {
      sendMissedNotifications(connection);
    }
    
    log.info("SSE connection established: userId={}, totalConnections={}", 
            userId, activeConnections.size());
    
    return connection.getEmitter();
  }

  /**
   * SSE 알림 전송
   */
  public void sendSseNotification(Long userId, AppNotification appNotification) {
    SseConnection connection = activeConnections.get(userId);
    if (connection == null) {
      return;
    }

    try {
      SseNotificationDto sseDto = SseNotificationDto.from(appNotification);
      String eventId = generateEventId(appNotification);
      
      connection.getEmitter().send(SseEmitter.event()
          .id(eventId)
          .name("notification")
          .data(sseDto));

    } catch (IOException e) {
      cleanupConnection(userId);
    }
  }

  /**
   * 읽지 않은 개수 업데이트 전송
   */
  public void sendUnreadCountUpdate(Long userId) {
    SseConnection connection = activeConnections.get(userId);
    if (connection == null) {
      return;
    }

    try {
      Long unreadCount = notificationRepository.countUnreadByUserId(userId);
      Map<String, Long> countData = Map.of("unread_count", unreadCount != null ? unreadCount : 0L);

      String eventId = generateCountEventId();
      
      connection.getEmitter().send(SseEmitter.event()
          .id(eventId)
          .name("unread_count")
          .data(countData));

    } catch (IOException e) {
      cleanupConnection(userId);
    }
  }

  // ================================
  // Private Helper Methods
  // ================================

  private void cleanupExistingConnection(Long userId) {
    SseConnection existingConnection = activeConnections.get(userId);
    if (existingConnection != null) {
      existingConnection.getEmitter().complete();
      activeConnections.remove(userId);
    }
  }

  private void registerConnectionCallbacks(SseConnection connection) {
    SseEmitter emitter = connection.getEmitter();
    Long userId = connection.getUserId();
    
    emitter.onCompletion(() -> cleanupConnection(userId));
    emitter.onTimeout(() -> {
      log.info("SSE connection timed out: userId={}, duration={}ms", 
              userId, connection.getDuration());
      cleanupConnection(userId);
    });
    emitter.onError((ex) -> cleanupConnection(userId));
  }

  private boolean sendInitialHeartbeat(SseConnection connection) {
    try {
      String eventId = generateHeartbeatEventId();
      connection.getEmitter().send(SseEmitter.event()
          .id(eventId)
          .name("heartbeat")
          .data("connected"));
      return true;
    } catch (IOException e) {
      activeConnections.remove(connection.getUserId());
      throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);
    }
  }

  /**
   * 놓친 알림 전송
   */
  private void sendMissedNotifications(SseConnection connection) {
    String lastEventId = connection.getLastEventId();
    if (lastEventId == null || lastEventId.isBlank()) {
      return;
    }

    try {
      LocalDateTime lastEventTime = parseEventIdToDateTime(lastEventId);
      if (lastEventTime != null) {
        List<AppNotification> missedNotifications = notificationRepository
                .findNotificationsByUserIdAfter(connection.getUserId(), lastEventTime);

        log.info("Sending {} missed notifications to user: {}", 
            missedNotifications.size(), connection.getUserId());

        for (AppNotification notification : missedNotifications) {
          try {
            SseNotificationDto sseDto = SseNotificationDto.from(notification);
            String eventId = generateEventId(notification);
            
            connection.getEmitter().send(SseEmitter.event()
                .id(eventId)
                .name("missed_notification")
                .data(sseDto));

          } catch (IOException e) {
            break;
          }
        }
      }
    } catch (Exception e) {
      log.warn("Failed to send missed notifications for user: {}", connection.getUserId());
    }
  }

  private void cleanupConnection(Long userId) {
    activeConnections.remove(userId);
    removeConnectionFromRedis(userId);
  }
  
  /**
   * Redis에 연결 정보 등록 (클러스터 환경 대응)
   */
  private void registerConnectionToRedis(Long userId, SseConnection connection) {
    try {
      String serverInstance = getServerInstanceId();
      Map<String, Object> connectionInfo = Map.of(
          "userId", userId,
          "serverInstance", serverInstance,
          "connectionTime", connection.getConnectionTime().toString(),
          "lastEventId", connection.getLastEventId() != null ? connection.getLastEventId() : ""
      );
      
      redisTemplate.opsForHash().putAll(SSE_CONNECTION_INFO_KEY + userId, connectionInfo);
      redisTemplate.expire(SSE_CONNECTION_INFO_KEY + userId, Duration.ofSeconds(CONNECTION_INFO_TTL));
      redisTemplate.opsForSet().add(SSE_CONNECTION_KEY, userId.toString());
      
      log.debug("Registered SSE connection to Redis: userId={}, server={}", userId, serverInstance);
    } catch (Exception e) {
      log.warn("Failed to register SSE connection to Redis: userId={}", userId, e);
    }
  }
  
  /**
   * Redis에서 연결 정보 제거
   */
  private void removeConnectionFromRedis(Long userId) {
    try {
      redisTemplate.delete(SSE_CONNECTION_INFO_KEY + userId);
      redisTemplate.opsForSet().remove(SSE_CONNECTION_KEY, userId.toString());
      
      log.debug("Removed SSE connection from Redis: userId={}", userId);
    } catch (Exception e) {
      log.warn("Failed to remove SSE connection from Redis: userId={}", userId, e);
    }
  }
  
  /**
   * 클러스터 전체에서 사용자 연결 상태 확인
   */
  public boolean isUserConnectedGlobally(Long userId) {
    try {
      return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(SSE_CONNECTION_KEY, userId.toString()));
    } catch (Exception e) {
      log.warn("Failed to check global SSE connection status: userId={}", userId, e);
      return isUserConnected(userId); // 로컬 연결 상태로 폴백
    }
  }
  
  /**
   * 클러스터 전체 연결된 사용자 수 조회
   */
  public long getGlobalActiveConnectionCount() {
    try {
      Long count = redisTemplate.opsForSet().size(SSE_CONNECTION_KEY);
      return count != null ? count : 0L;
    } catch (Exception e) {
      log.warn("Failed to get global SSE connection count", e);
      return getActiveConnectionCount(); // 로컬 연결 수로 폴백
    }
  }
  
  /**
   * 서버 인스턴스 ID 생성 (클러스터 환경에서 서버 식별용)
   */
  private String getServerInstanceId() {
    return System.getProperty("server.instance.id", 
        "server-" + System.currentTimeMillis() % 10000);
  }

  /**
   * 현재 연결된 사용자 수 조회
   */
  public int getActiveConnectionCount() {
    return activeConnections.size();
  }

  public boolean isUserConnected(Long userId) {
    return activeConnections.containsKey(userId);
  }
  
  /**
   * 모든 SSE 연결 제거 (테스트용)
   */
  public void clearAllConnections() {
    try {
      // 모든 활성 연결 정리
      for (Long userId : new HashSet<>(activeConnections.keySet())) {
        SseConnection connection = activeConnections.remove(userId);
        if (connection != null && connection.getEmitter() != null) {
          try {
            connection.getEmitter().complete();
          } catch (Exception e) {
            // 이미 완료된 연결은 무시
          }
        }
      }
      
      // Redis에서도 제거 시도 (테스트 환경에서는 실패할 수 있음)
      try {
        redisTemplate.delete(SSE_CONNECTION_KEY);
        log.debug("Cleared all SSE connections");
      } catch (Exception e) {
        log.debug("Failed to clear Redis connections (expected in test environment): {}", e.getMessage());
      }
      
    } catch (Exception e) {
      log.warn("Error while clearing all connections", e);
    }
  }

  /**
   * 전체 사용자에게 브로드캐스트 메시지 전송
   */
  public CompletableFuture<BroadcastResult> broadcastToAll(String eventName, Object data) {
    if (activeConnections.isEmpty()) {
      log.info("No active SSE connections for broadcast");
      return CompletableFuture.completedFuture(BroadcastResult.empty());
    }

    return CompletableFuture.supplyAsync(() -> {
      String eventId = generateBroadcastEventId();
      AtomicLong successCount = new AtomicLong(0);
      AtomicLong failureCount = new AtomicLong(0);

      log.info("Broadcasting message to {} connected users: event={}", activeConnections.size(), eventName);

      activeConnections.entrySet().parallelStream().forEach(entry -> {
        Long userId = entry.getKey();
        SseConnection connection = entry.getValue();
        
        try {
          connection.getEmitter().send(SseEmitter.event()
              .id(eventId)
              .name(eventName)
              .data(data));
          successCount.incrementAndGet();
          
        } catch (IOException e) {
          log.warn("Failed to send broadcast message to user: {}", userId);
          cleanupConnection(userId);
          failureCount.incrementAndGet();
        }
      });
      
      BroadcastResult result = BroadcastResult.of(successCount.get(), failureCount.get());
      log.info("Broadcast completed: success={}, failed={}", result.getSuccessCount(), result.getFailureCount());
      return result;
    });
  }

  /**
   * 전체 사용자에게 공지사항 전송
   */
  public CompletableFuture<BroadcastResult> broadcastAnnouncement(String title, String message) {
    Map<String, String> announcement = Map.of(
        "title", title,
        "message", message,
        "timestamp", LocalDateTime.now().toString()
    );
    
    return broadcastToAll("announcement", announcement);
  }

  /**
   * 전체 사용자에게 시스템 공지 전송
   */
  public CompletableFuture<BroadcastResult> broadcastSystemNotice(String noticeType, String content) {
    Map<String, String> systemNotice = Map.of(
        "type", noticeType,
        "content", content,
        "timestamp", LocalDateTime.now().toString()
    );
    
    return broadcastToAll("system_notice", systemNotice);
  }

  /**
   * 서비스 초기화 시 주기적인 정리 작업 시작
   */
  @Override
  public void afterPropertiesSet() {
    // 주기적인 연결 상태 점검 및 정리
    cleanupScheduler.scheduleWithFixedDelay(
        this::cleanupStaleConnections, 
        CLEANUP_INTERVAL_MINUTES, 
        CLEANUP_INTERVAL_MINUTES, 
        TimeUnit.MINUTES
    );
    
    log.info("SSE cleanup scheduler started with interval: {} minutes", CLEANUP_INTERVAL_MINUTES);
  }

  /**
   * 서비스 종료 시 스케줄러 정리
   */
  @Override
  public void destroy() {
    log.info("Shutting down SSE service...");
    
    // 모든 활성 연결 정리
    activeConnections.values().forEach(connection -> {
      try {
        connection.getEmitter().complete();
      } catch (Exception e) {
        log.warn("Error closing SSE connection during shutdown", e);
      }
    });
    activeConnections.clear();
    
    // 스케줄러 종료
    cleanupScheduler.shutdown();
    try {
      if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        cleanupScheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      cleanupScheduler.shutdownNow();
      Thread.currentThread().interrupt();
    }
    
    log.info("SSE service shutdown completed");
  }

  /**
   * 만료된 SSE 연결 정리
   */
  private void cleanupStaleConnections() {
    try {
      LocalDateTime now = LocalDateTime.now();
      LocalDateTime cutoffTime = now.minusSeconds((sseTimeoutMillis + 60000) / 1000); // 타임아웃 + 1분 여유
      
      List<Long> staleConnections = activeConnections.entrySet().stream()
          .filter(entry -> entry.getValue().getConnectionTime().isBefore(cutoffTime))
          .map(Map.Entry::getKey)
          .toList();
      
      staleConnections.forEach(this::cleanupConnection);
      
      if (!staleConnections.isEmpty()) {
        log.info("Cleaned up {} stale SSE connections", staleConnections.size());
      }
      
      log.debug("SSE cleanup completed: active connections={}", activeConnections.size());
      
    } catch (Exception e) {
      log.error("Error during SSE cleanup", e);
    }
  }

  // ================================
  // Event ID 생성 및 파싱 메서드
  // ================================

  /**
   * 알림용 Event ID 생성 (notification_{notificationId}_{timestamp})
   */
  private String generateEventId(AppNotification appNotification) {
    return String.format("notification_%d_%s", 
        appNotification.getId(), 
        appNotification.getCreatedAt().toString());
  }

  /**
   * 읽지 않은 개수용 Event ID 생성
   */
  private String generateCountEventId() {
    return String.format("count_%s", LocalDateTime.now().toString());
  }

  /**
   * 하트비트용 Event ID 생성
   */
  private String generateHeartbeatEventId() {
    return String.format("heartbeat_%s", LocalDateTime.now().toString());
  }

  /**
   * 브로드캐스트용 Event ID 생성
   */
  private String generateBroadcastEventId() {
    return String.format("broadcast_%s", LocalDateTime.now().toString());
  }

  /**
   * Event ID에서 timestamp 추출
   */
  private LocalDateTime parseEventIdToDateTime(String eventId) {
    try {
      String[] parts = eventId.split("_");
      if (parts.length >= 2) {
        String timestampPart = parts[parts.length - 1];
        return LocalDateTime.parse(timestampPart);
      }
    } catch (DateTimeParseException e) {
      log.warn("Failed to parse event ID: {}", eventId);
    }
    return null;
  }

  /**
   * 불변 SSE 연결 정보
   */
  public static class SseConnection {
    private final Long userId;
    private final SseEmitter emitter;
    private final LocalDateTime connectionTime;
    private final String lastEventId;

    private SseConnection(Builder builder) {
      this.userId = Objects.requireNonNull(builder.userId);
      this.emitter = Objects.requireNonNull(builder.emitter);
      this.connectionTime = Objects.requireNonNull(builder.connectionTime);
      this.lastEventId = builder.lastEventId;
    }

    public static Builder builder() {
      return new Builder();
    }

    public Long getUserId() { return userId; }
    public SseEmitter getEmitter() { return emitter; }
    public LocalDateTime getConnectionTime() { return connectionTime; }
    public String getLastEventId() { return lastEventId; }
    
    public long getDuration() {
      return java.time.Duration.between(connectionTime, LocalDateTime.now()).toMillis();
    }

    public static class Builder {
      private Long userId;
      private SseEmitter emitter;
      private LocalDateTime connectionTime;
      private String lastEventId;

      public Builder userId(Long userId) {
        this.userId = userId;
        return this;
      }

      public Builder emitter(SseEmitter emitter) {
        this.emitter = emitter;
        return this;
      }

      public Builder connectionTime(LocalDateTime connectionTime) {
        this.connectionTime = connectionTime;
        return this;
      }

      public Builder lastEventId(String lastEventId) {
        this.lastEventId = lastEventId;
        return this;
      }

      public SseConnection build() {
        return new SseConnection(this);
      }
    }
  }

  /**
   * 브로드캐스트 결과
   */
  public static class BroadcastResult {
    private final long successCount;
    private final long failureCount;

    private BroadcastResult(long successCount, long failureCount) {
      this.successCount = successCount;
      this.failureCount = failureCount;
    }

    public static BroadcastResult of(long successCount, long failureCount) {
      return new BroadcastResult(successCount, failureCount);
    }

    public static BroadcastResult empty() {
      return new BroadcastResult(0, 0);
    }

    public long getSuccessCount() { return successCount; }
    public long getFailureCount() { return failureCount; }
    public long getTotalCount() { return successCount + failureCount; }
  }
}