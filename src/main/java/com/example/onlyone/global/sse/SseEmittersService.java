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

  @Value("${app.notification.sse-timeout-millis:1800000}")
  private long sseTimeoutMillis;
  
  @Value("${app.notification.max-connections:2000}")
  private int maxConnections;
  
  @Value("${app.notification.cleanup-interval-minutes:10}")
  private int cleanupIntervalMinutes;

  private final NotificationRepository notificationRepository;
  private final ConcurrentHashMap<Long, SseConnection> activeConnections = new ConcurrentHashMap<>();
  
  
  private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
      r -> {
        Thread thread = new Thread(r, "sse-cleanup-thread");
        thread.setDaemon(true);
        return thread;
      });
      
  private final ExecutorService sseEventExecutor = new ThreadPoolExecutor(
      25, 100, 30L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>(200),
      r -> {
        Thread thread = new Thread(r, "sse-event-" + System.nanoTime());
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        return thread;
      },
      new ThreadPoolExecutor.AbortPolicy()
  );
      
  private final ExecutorService missedMessageExecutor = new ThreadPoolExecutor(
      10, 30, 45L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>(100),
      r -> {
        Thread thread = new Thread(r, "sse-recovery-" + System.nanoTime());
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        return thread;
      },
      new ThreadPoolExecutor.DiscardOldestPolicy()
  );
  
  private final AtomicLong totalConnectionsCreated = new AtomicLong(0);
  private final AtomicLong totalConnectionsClosed = new AtomicLong(0);

  /**
   * SSE 연결 생성
   */
  public SseEmitter createSseConnection(User user, String lastEventId) {
    Long userId = user.getUserId();
    
    if (activeConnections.size() >= maxConnections) {
      int cleaned = forceCleanupStaleConnections();
      log.info("Force cleanup completed: {} stale connections removed", cleaned);
      
      if (activeConnections.size() >= maxConnections) {
        log.warn("Maximum SSE connections reached: {}/{}, rejecting userId: {}", 
                activeConnections.size(), maxConnections, userId);
        throw new CustomException(ErrorCode.SSE_CONNECTION_LIMIT_EXCEEDED);
      }
    }
    
    cleanupExistingConnection(userId);
    
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
    
    CompletableFuture.runAsync(() -> {
      if (lastEventId != null && !lastEventId.trim().isEmpty()) {
        sendMissedMessages(connection, lastEventId);
      } else {
        sendUnsentNotifications(connection);
      }
    }, missedMessageExecutor)
        .exceptionally(ex -> {
          log.warn("Failed to send initial/missed messages for userId: {}, error: {}", userId, ex.getMessage());
          return null;
        });

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

    return CompletableFuture.supplyAsync(() -> {
      try {
        String eventId = "evt_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
        
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


  // === Connection Management ===

  public int getActiveConnectionCount() {
    return activeConnections.size();
  }

  public boolean isUserConnected(Long userId) {
    return activeConnections.containsKey(userId);
  }

  public Set<Long> getActiveUserIds() {
    return new HashSet<>(activeConnections.keySet());
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
    
    log.info("SSE cleanup scheduler started with interval: {} minutes", cleanupIntervalMinutes);
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
    } catch (InterruptedException e) {
      cleanupScheduler.shutdownNow();
      sseEventExecutor.shutdownNow();
      missedMessageExecutor.shutdownNow();
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
      LocalDateTime now = LocalDateTime.now();
      LocalDateTime cutoffTime = now.minusSeconds((sseTimeoutMillis + 60000) / 1000);
      
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
      log.error("Error during SSE cleanup, continuing gracefully", e);
    }
  }

  private int forceCleanupStaleConnections() {
    try {
      LocalDateTime now = LocalDateTime.now();
      LocalDateTime cutoffTime = now.minusSeconds((sseTimeoutMillis + 30000) / 1000);
      
      List<Long> staleConnections = activeConnections.entrySet().stream()
          .filter(entry -> entry.getValue().getConnectionTime().isBefore(cutoffTime))
          .map(Map.Entry::getKey)
          .toList();
      
      staleConnections.parallelStream().forEach(this::cleanupConnection);
      
      return staleConnections.size();
      
    } catch (Exception e) {
      log.error("Error during force cleanup", e);
      return 0;
    }
  }

  private void sendMissedMessages(SseConnection connection, String lastEventId) {
    try {
      LocalDateTime lastEventTime = parseEventIdToDateTime(lastEventId);
      if (lastEventTime == null) {
        log.warn("Invalid lastEventId format, skipping missed messages: {}", lastEventId);
        return;
      }
      
      List<Notification> missedNotifications = notificationRepository
          .findUnreadNotificationsByUserId(connection.getUserId())
          .stream()
          .filter(notification -> notification.getCreatedAt().isAfter(lastEventTime))
          .sorted((n1, n2) -> n1.getCreatedAt().compareTo(n2.getCreatedAt()))
          .toList();
      
      if (!missedNotifications.isEmpty()) {
        log.info("Sending {} missed notifications to userId: {}", missedNotifications.size(), connection.getUserId());
        
        int successCount = 0;
        for (Notification notification : missedNotifications) {
          try {
            String eventId = "recovery_" + System.currentTimeMillis() + "_" + notification.getId();
            connection.getEmitter().send(SseEmitter.event()
                .id(eventId)
                .name("notification")
                .data(notification));
            
            successCount++;
            log.debug("Missed notification sent: userId={}, notificationId={}", 
                connection.getUserId(), notification.getId());
          } catch (IOException e) {
            log.error("Failed to send missed notification: userId={}, notificationId={}", 
                connection.getUserId(), notification.getId(), e);
            break;
          }
        }
        
        if (successCount > 0) {
          log.info("Successfully sent {}/{} missed notifications to userId: {}", 
              successCount, missedNotifications.size(), connection.getUserId());
        }
        
        if (successCount == 0 && !missedNotifications.isEmpty()) {
          log.warn("All missed message sends failed, cleaning up connection for userId: {}", connection.getUserId());
          cleanupConnection(connection.getUserId());
        }
      }
    } catch (Exception e) {
      log.error("Error processing missed messages for userId: {}", connection.getUserId(), e);
    }
  }

  private void sendUnsentNotifications(SseConnection connection) {
    try {
      List<Notification> unsentNotifications = notificationRepository
          .findUnreadNotificationsByUserId(connection.getUserId())
          .stream()
          .filter(notification -> !notification.isSseSent())
          .sorted((n1, n2) -> n1.getCreatedAt().compareTo(n2.getCreatedAt()))
          .toList();
      
      if (!unsentNotifications.isEmpty()) {
        log.info("Sending {} SSE-unsent notifications to userId: {} (initial connection)", 
            unsentNotifications.size(), connection.getUserId());
        
        int successCount = 0;
        for (Notification notification : unsentNotifications) {
          try {
            String eventId = "initial_" + System.currentTimeMillis() + "_" + notification.getId();
            connection.getEmitter().send(SseEmitter.event()
                .id(eventId)
                .name("notification")
                .data(notification));
            
            successCount++;
            log.debug("Initial notification sent: userId={}, notificationId={}", 
                connection.getUserId(), notification.getId());
          } catch (IOException e) {
            log.error("Failed to send initial notification: userId={}, notificationId={}", 
                connection.getUserId(), notification.getId(), e);
            break;
          }
        }
        
        if (successCount > 0) {
          log.info("Successfully sent {}/{} initial notifications to userId: {}", 
              successCount, unsentNotifications.size(), connection.getUserId());
        }
      } else {
        log.debug("No SSE-unsent notifications for initial connection: userId={}", connection.getUserId());
      }
    } catch (Exception e) {
      log.error("Error processing initial notifications for userId: {}", connection.getUserId(), e);
    }
  }

  private LocalDateTime parseEventIdToDateTime(String eventId) {
    try {
      if (eventId.startsWith("evt_")) {
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