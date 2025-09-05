package com.example.onlyone.global.sse;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.DisposableBean;

/**
 * SSE 연결 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmittersService implements InitializingBean, DisposableBean {

  @Value("${app.notification.sse-timeout-millis:300000}")  // 5분으로 단축
  private long sseTimeoutMillis;
  
  @Value("${app.notification.max-connections:8000}")  // 8000명 대응
  private int maxConnections;
  
  @Value("${app.notification.cleanup-interval-minutes:2}")  // 2분으로 단축
  private int cleanupIntervalMinutes;

  private final NotificationRepository notificationRepository;
  private final ConcurrentHashMap<Long, SseConnection> activeConnections = new ConcurrentHashMap<>();
  
  
  private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
      r -> {
        Thread thread = new Thread(r, "sse-cleanup");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
      });
      
  private final ExecutorService sseEventExecutor = new ThreadPoolExecutor(
      10, 50, 30L, TimeUnit.SECONDS,  // 대용량 트래픽 대응 확장
      new LinkedBlockingQueue<>(200),
      r -> {
        Thread thread = new Thread(r, "sse-event");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
      },
      new ThreadPoolExecutor.CallerRunsPolicy()
  );
      
  private final ExecutorService missedMessageExecutor = new ThreadPoolExecutor(
      5, 20, 60L, TimeUnit.SECONDS,  // 복구 성능 향상
      new LinkedBlockingQueue<>(100),
      r -> {
        Thread thread = new Thread(r, "sse-recovery");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
      },
      new ThreadPoolExecutor.DiscardOldestPolicy()
  );
  
  private final AtomicLong totalConnectionsCreated = new AtomicLong(0);
  private final AtomicLong totalConnectionsClosed = new AtomicLong(0);
  private final AtomicLong eventIdCounter = new AtomicLong(0);
  
  // 대용량 트래픽 대비 배치 처리
  private final Map<Long, String> pendingEvents = new ConcurrentHashMap<>();
  private final ScheduledExecutorService batchProcessor = Executors.newSingleThreadScheduledExecutor(
      r -> {
        Thread thread = new Thread(r, "sse-batch-processor");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        return thread;
      });

  /**
   * SSE 연결 생성
   */
  public SseEmitter createSseConnection(User user, String lastEventId) {
    Long userId = user.getUserId();
    
    // 기존 연결 먼저 정리 (중복 연결 방지)
    cleanupExistingConnection(userId);
    
    if (activeConnections.size() >= maxConnections) {
      int cleaned = forceCleanupStaleConnections();
      log.info("Force cleanup completed: {} stale connections removed", cleaned);
      
      if (activeConnections.size() >= maxConnections) {
        log.warn("Maximum SSE connections reached: {}/{}, rejecting userId: {}", 
                activeConnections.size(), maxConnections, userId);
        throw new CustomException(ErrorCode.SSE_CONNECTION_LIMIT_EXCEEDED);
      }
    }
    
    SseConnection connection = SseConnection.builder()
            .userId(userId)
            .cachedUser(user)
            .emitter(new SseEmitter(sseTimeoutMillis))
            .connectionTime(LocalDateTime.now())
            .build();
    
    activeConnections.put(userId, connection);
    totalConnectionsCreated.incrementAndGet();
    
    registerConnectionCallbacks(connection);
    sendInitialHeartbeat(connection);
    
    // 재연결 시에만 놓친 알림 전송 (첫 연결은 과거 알림 전송 안 함)
    if (lastEventId != null && !lastEventId.trim().isEmpty()) {
      CompletableFuture.runAsync(() -> {
        sendMissedNotifications(connection, lastEventId);
      }, missedMessageExecutor)
          .exceptionally(ex -> {
            log.warn("Failed to send missed notifications for userId: {}, error: {}", userId, ex.getMessage());
            return null;
          });
    }

    log.info("SSE connection established: userId={}, lastEventId={}, activeConnections={}/{}", 
            userId, lastEventId, activeConnections.size(), maxConnections);
    
    return connection.getEmitter();
  }

  /**
   * SSE 이벤트 전송
   */
  
  public CompletableFuture<Boolean> sendEvent(Long userId, String eventName, Object data) {
    SseConnection connection = activeConnections.get(userId);
    if (connection == null) {
      log.debug("No SSE connection found for user: {}", userId);
      return CompletableFuture.completedFuture(false);
    }

    // 대용량 트래픽 시 배치 처리를 위한 부하 분산
    if (activeConnections.size() > 5000) {
      return sendEventWithBatching(userId, eventName, data);
    }

    return CompletableFuture.supplyAsync(() -> {
      try {
        String eventId = "evt_" + System.currentTimeMillis() + "_" + eventIdCounter.incrementAndGet();
        
        connection.getEmitter().send(SseEmitter.event()
            .id(eventId)
            .name(eventName)
            .data(data));

        log.debug("SSE event sent: userId={}, eventName={}, eventId={}", userId, eventName, eventId);
        return true;
      } catch (IOException e) {
        String errorMessage = e.getMessage();
        if (errorMessage != null && errorMessage.contains("Broken pipe")) {
          log.debug("Client disconnected: userId={}, eventName={} (Broken pipe)", userId, eventName);
        } else {
          log.error("Failed to send SSE event: userId={}, eventName={}", userId, eventName, e);
        }
        cleanupConnection(userId);
        return false;
      } catch (Exception e) {
        log.error("Unexpected error while sending SSE event: userId={}, eventName={}", userId, eventName, e);
        cleanupConnection(userId);
        return false;
      }
    }, sseEventExecutor);
  }
  
  /**
   * 대용량 트래픽 시 배치 처리로 전송
   */
  private CompletableFuture<Boolean> sendEventWithBatching(Long userId, String eventName, Object data) {
    try {
      // JSON 직렬화하여 배치 컴에 대기
      String jsonData = data.toString(); // 실제로는 ObjectMapper 사용 권장
      pendingEvents.put(userId, jsonData);
      
      log.debug("Event queued for batch processing: userId={}, eventName={}", userId, eventName);
      return CompletableFuture.completedFuture(true);
    } catch (Exception e) {
      log.error("Failed to queue event for batching: userId={}, eventName={}", userId, eventName, e);
      return CompletableFuture.completedFuture(false);
    }
  }


  // === Connection Management ===

  public int getActiveConnectionCount() {
    return activeConnections.size();
  }

  public boolean isUserConnected(Long userId) {
    return activeConnections.containsKey(userId);
  }

  public Set<Long> getActiveUserIds() {
    return activeConnections.keySet();
  }

  public LocalDateTime getLastConnectedTime(Long userId) {
    SseConnection connection = activeConnections.get(userId);
    return connection != null ? connection.getConnectionTime() : null;
  }

  public String getConnectionDuration(Long userId) {
    SseConnection connection = activeConnections.get(userId);
    if (connection == null) {
      return null;
    }
    
    long durationMs = connection.getDuration();
    long seconds = durationMs / 1000;
    long minutes = seconds / 60;
    long hours = minutes / 60;
    
    if (hours > 0) {
      return String.format("%d시간 %d분", hours, minutes % 60);
    } else if (minutes > 0) {
      return String.format("%d분 %d초", minutes, seconds % 60);
    } else {
      return String.format("%d초", seconds);
    }
  }
  
  public void clearAllConnections() {
    try {
      for (Long userId : new HashSet<>(activeConnections.keySet())) {
        SseConnection connection = activeConnections.remove(userId);
        if (connection != null && connection.getEmitter() != null) {
          try {
            connection.getEmitter().complete();
          } catch (Exception e) {
            // 완료된 연결 무시
          }
        }
      }
      
      log.debug("Cleared all SSE connections");
      
    } catch (Exception e) {
      log.error("Error while clearing all connections", e);
      throw new CustomException(ErrorCode.SSE_CLEANUP_FAILED);
    }
  }

  // === Lifecycle Management ===

  @Override
  public void afterPropertiesSet() {
    cleanupScheduler.scheduleWithFixedDelay(
        this::cleanupStaleConnections, 
        cleanupIntervalMinutes, 
        cleanupIntervalMinutes, 
        TimeUnit.MINUTES
    );
    
    // 대용량 트래픽 대비 배치 처리 시작
    batchProcessor.scheduleWithFixedDelay(
        this::processPendingEvents,
        100, 100, TimeUnit.MILLISECONDS  // 100ms마다 배치 처리
    );
    
    log.info("SSE services started - cleanup: {}min, max connections: {}", 
        cleanupIntervalMinutes, maxConnections);
  }

  @Override
  public void destroy() {
    log.info("Shutting down SSE service...");
    
    activeConnections.values().forEach(connection -> {
      try {
        connection.getEmitter().complete();
      } catch (Exception e) {
        log.warn("Error closing SSE connection during shutdown", e);
      }
    });
    activeConnections.clear();
    
    cleanupScheduler.shutdown();
    sseEventExecutor.shutdown();
    missedMessageExecutor.shutdown();
    batchProcessor.shutdown();
    
    try {
      if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        cleanupScheduler.shutdownNow();
      }
      if (!sseEventExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        sseEventExecutor.shutdownNow();
      }
      if (!missedMessageExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        missedMessageExecutor.shutdownNow();
      }
      if (!batchProcessor.awaitTermination(5, TimeUnit.SECONDS)) {
        batchProcessor.shutdownNow();
      }
    } catch (InterruptedException e) {
      cleanupScheduler.shutdownNow();
      sseEventExecutor.shutdownNow();
      missedMessageExecutor.shutdownNow();
      batchProcessor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    
    log.info("SSE service shutdown completed");
  }

  // === Private Methods ===

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

  private void sendInitialHeartbeat(SseConnection connection) {
    try {
      String eventId = "heartbeat_" + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      connection.getEmitter().send(SseEmitter.event()
          .id(eventId)
          .name("heartbeat")
          .data("connected"));
    } catch (IOException e) {
      activeConnections.remove(connection.getUserId());
      throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);
    }
  }

  private void cleanupConnection(Long userId) {
    activeConnections.remove(userId);
    totalConnectionsClosed.incrementAndGet();
  }

  private void cleanupStaleConnections() {
    try {
      // 연결 수가 적을 때는 cleanup 건너뛰기 (CPU 절약)
      if (activeConnections.size() < 10) {
        return;
      }
      
      LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds((sseTimeoutMillis + 60000) / 1000);
      
      // parallelStream 대신 일반 stream 사용 (적은 연결 수에서 오버헤드 방지)
      Set<Long> staleConnections = activeConnections.entrySet().stream()
          .filter(entry -> entry.getValue().getConnectionTime().isBefore(cutoffTime))
          .map(Map.Entry::getKey)
          .collect(Collectors.toSet());
      
      staleConnections.forEach(this::cleanupConnection);
      
      if (!staleConnections.isEmpty()) {
        log.info("Cleaned up {} stale SSE connections", staleConnections.size());
      }
      
      log.debug("SSE cleanup completed: active connections={}", activeConnections.size());
      
    } catch (Exception e) {
      log.error("Error during SSE cleanup, continuing gracefully", e);
    }
  }

  private int forceCleanupStaleConnections() {
    try {
      LocalDateTime cutoffTime = LocalDateTime.now().minusSeconds((sseTimeoutMillis + 30000) / 1000);
      
      Set<Long> staleConnections = activeConnections.entrySet().parallelStream()
          .filter(entry -> entry.getValue().getConnectionTime().isBefore(cutoffTime))
          .map(Map.Entry::getKey)
          .collect(Collectors.toSet());
      
      staleConnections.parallelStream().forEach(this::cleanupConnection);
      
      return staleConnections.size();
      
    } catch (Exception e) {
      log.error("Error during force cleanup", e);
      return 0;
    }
  }

  private void sendMissedNotifications(SseConnection connection, String lastEventId) {
    try {
      LocalDateTime lastEventTime = parseEventIdToDateTime(lastEventId);
      if (lastEventTime == null) {
        log.warn("Invalid lastEventId format, skipping missed notifications: {}", lastEventId);
        return;
      }
      
      // 재연결 시 lastEventId 이후의 모든 놓친 알림 전송
      List<Notification> missedNotifications = notificationRepository
          .findUnreadNotificationsByUserId(connection.getUserId())
          .stream()
          .filter(notification -> notification.getCreatedAt().isAfter(lastEventTime))
          .sorted(Comparator.comparing(Notification::getCreatedAt))
          .toList();
      
      if (!missedNotifications.isEmpty()) {
        log.info("Sending {} missed notifications to userId: {} (reconnection)", 
            missedNotifications.size(), connection.getUserId());
        
        sendNotificationBatch(connection, missedNotifications, "recovery");
      } else {
        log.debug("No missed notifications for userId: {} since {}", 
            connection.getUserId(), lastEventTime);
      }
    } catch (Exception e) {
      log.error("Error processing missed notifications for userId: {}", connection.getUserId(), e);
    }
  }
  
  private void sendNotificationBatch(SseConnection connection, List<Notification> notifications, String prefix) {
    int successCount = 0;
    for (Notification notification : notifications) {
      try {
        String eventId = prefix + "_" + System.currentTimeMillis() + "_" + eventIdCounter.incrementAndGet();
        connection.getEmitter().send(SseEmitter.event()
            .id(eventId)
            .name("notification")
            .data(notification));
        
        successCount++;
        log.debug("{} notification sent: userId={}, notificationId={}", 
            prefix, connection.getUserId(), notification.getId());
      } catch (IOException e) {
        log.error("Failed to send {} notification: userId={}, notificationId={}", 
            prefix, connection.getUserId(), notification.getId(), e);
        break;
      }
    }
    
    if (successCount > 0) {
      log.info("Successfully sent {}/{} {} notifications to userId: {}", 
          successCount, notifications.size(), prefix, connection.getUserId());
    }
    
    if (successCount == 0) {
      log.warn("All {} message sends failed, cleaning up connection for userId: {}", prefix, connection.getUserId());
      cleanupConnection(connection.getUserId());
    }
  }

  /**
   * 대용량 트래픽 대비 배치 이벤트 처리 - 낮은 연결 수에서 CPU 절약
   */
  private void processPendingEvents() {
    if (pendingEvents.isEmpty()) {
      return;
    }
    
    // 연결 수가 1000개 미만이면 배치 처리 건너뛰기 (CPU 절약)
    if (activeConnections.size() < 1000) {
      pendingEvents.clear(); // 일반 이벤트로 전송되도록 큐 비우기
      return;
    }
    
    // 성능 최적화: 최대 50개씩 배치 처리 (100→50으로 축소)
    int processed = 0;
    for (Map.Entry<Long, String> entry : pendingEvents.entrySet()) {
      if (processed >= 50) break;
      
      Long userId = entry.getKey();
      String eventData = entry.getValue();
      
      SseConnection connection = activeConnections.get(userId);
      if (connection != null) {
        try {
          String eventId = "batch_" + System.currentTimeMillis() + "_" + eventIdCounter.incrementAndGet();
          connection.getEmitter().send(SseEmitter.event()
              .id(eventId)
              .name("notification")
              .data(eventData));
          
          pendingEvents.remove(userId);
          processed++;
        } catch (IOException e) {
          cleanupConnection(userId);
          pendingEvents.remove(userId);
        }
      } else {
        pendingEvents.remove(userId);
      }
    }
    
    if (processed > 0) {
      log.debug("Processed {} batch events", processed);
    }
  }
  
  private LocalDateTime parseEventIdToDateTime(String eventId) {
    try {
      if (eventId.startsWith("evt_") || eventId.startsWith("batch_")) {
        String[] parts = eventId.split("_");
        if (parts.length >= 2) {
          long timestamp = Long.parseLong(parts[1]);
          return LocalDateTime.ofEpochSecond(timestamp / 1000, 0, java.time.ZoneOffset.UTC);
        }
      } else if (eventId.startsWith("heartbeat_")) {
        String dateTimePart = eventId.substring("heartbeat_".length());
        return LocalDateTime.parse(dateTimePart, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      }
    } catch (DateTimeParseException | NumberFormatException e) {
      log.warn("Failed to parse eventId: {}, error: {}", eventId, e.getMessage());
    }
    return null;
  }

}