package com.example.onlyone.global.sse;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.sse.connection.SseConnectionManager;
import com.example.onlyone.global.sse.event.SseEventSender;
import com.example.onlyone.global.sse.metrics.SseMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;

/**
 * SSE 서비스 - 연결 관리와 이벤트 전송을 통합
 * 성능 최적화 완료
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmittersService implements InitializingBean, DisposableBean {

  @Value("${app.notification.cleanup-interval-minutes:2}")
  private int cleanupIntervalMinutes;

  private final NotificationRepository notificationRepository;
  private final SseMetrics sseMetrics;
  private final SseConnectionManager connectionManager;
  private final SseEventSender eventSender;
  private final ObjectMapper objectMapper;
  
  private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
      r -> {
        Thread thread = new Thread(r, "sse-cleanup");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
      });
      
  private final ExecutorService missedMessageExecutor = new ThreadPoolExecutor(
      3, 10, 60L, TimeUnit.SECONDS,
      new LinkedBlockingQueue<>(50),
      r -> {
        Thread thread = new Thread(r, "sse-recovery");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
      },
      new ThreadPoolExecutor.DiscardOldestPolicy()
  );


  /**
   * SSE 연결 생성 (비즈니스 레벨)
   * - 인프라 연결 생성 + 놓친 알림 복구 로직
   * - 재연결 시 lastEventId 기반 미전송 알림 처리
   * - 백프레셔 제어 추가
   */
  public SseEmitter createSseConnection(User user, String lastEventId) {
    // 백프레셔 체크
    if (!checkConnectionCapacity(user.getUserId())) {
      log.warn("Connection rejected due to capacity limits for userId: {}", user.getUserId());
      throw new IllegalStateException("Server at capacity, please try again later");
    }
    
    SseEmitter emitter = connectionManager.createConnection(user);
    
    // 재연결 시에만 놓친 알림 전송
    if (lastEventId != null && !lastEventId.trim().isEmpty()) {
      SseConnection connection = connectionManager.getConnection(user.getUserId());
      if (connection != null) {
        CompletableFuture.runAsync(() -> {
          sendMissedNotifications(connection, lastEventId);
        }, missedMessageExecutor)
            .exceptionally(ex -> {
              log.warn("Failed to send missed notifications for userId: {}, error: {}", user.getUserId(), ex.getMessage());
              return null;
            });
      }
    }
    
    return emitter;
  }
  
  /**
   * 연결 용량 체크
   */
  private boolean checkConnectionCapacity(Long userId) {
    // 온라인 사용자 수 체크
    int currentConnections = connectionManager.getActiveConnectionCount();
    int maxCapacity = 10000; // 설정값으로 변경 가능
    
    if (currentConnections >= maxCapacity) {
      sseMetrics.recordConnectionRejected();
      return false;
    }
    
    return true;
  }

  /**
   * SSE 이벤트 전송
   */
  public CompletableFuture<Boolean> sendEvent(Long userId, String eventName, Object data) {
    return eventSender.sendEvent(userId, eventName, data);
  }

  /**
   * SSE 이벤트 전송 (동기)
   */
  public boolean sendEventSync(Long userId, String eventName, Object data) {
    return eventSender.sendEventSync(userId, eventName, data);
  }

  // === Connection Management ===
  public int getActiveConnectionCount() {
    return connectionManager.getActiveConnectionCount();
  }

  public boolean isUserConnected(Long userId) {
    return connectionManager.isUserConnected(userId);
  }

  public Set<Long> getActiveUserIds() {
    return connectionManager.getActiveUserIds();
  }

  public LocalDateTime getLastConnectedTime(Long userId) {
    return connectionManager.getLastConnectedTime(userId);
  }

  public String getConnectionDuration(Long userId) {
    return connectionManager.getConnectionDuration(userId);
  }
  
  public void clearAllConnections() {
    connectionManager.clearAllConnections();
  }

  // === Lifecycle Management ===

  @Override
  public void afterPropertiesSet() {
    cleanupScheduler.scheduleWithFixedDelay(
        connectionManager::cleanupStaleConnections, 
        cleanupIntervalMinutes, 
        cleanupIntervalMinutes, 
        TimeUnit.MINUTES
    );
    
    log.info("SSE services started - cleanup: {}min", cleanupIntervalMinutes);
  }

  @Override
  public void destroy() {
    log.info("Shutting down SSE service...");
    
    connectionManager.clearAllConnections();
    
    cleanupScheduler.shutdown();
    missedMessageExecutor.shutdown();
    
    try {
      if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
        cleanupScheduler.shutdownNow();
      }
      if (!missedMessageExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        missedMessageExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      cleanupScheduler.shutdownNow();
      missedMessageExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
    
    log.info("SSE service shutdown completed");
  }

  // === Private Methods ===

  private void sendMissedNotifications(SseConnection connection, String lastEventId) {
    Timer.Sample sample = sseMetrics.startMissedRecoveryTimer();
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
      sseMetrics.stopMissedRecoveryTimer(sample);
    } catch (Exception e) {
      log.error("Error processing missed notifications for userId: {}", connection.getUserId(), e);
      sseMetrics.stopMissedRecoveryTimer(sample);
    }
  }
  
  private void sendNotificationBatch(SseConnection connection, List<Notification> notifications, String prefix) {
    int successCount = 0;
    int batchSize = Math.min(notifications.size(), 20);
    
    for (int i = 0; i < batchSize; i++) {
      Notification notification = notifications.get(i);
      try {
        String eventId = prefix + "_" + System.currentTimeMillis() + "_" + i;
        
        String notificationJson = objectMapper.writeValueAsString(notification);
        
        connection.getEmitter().send(SseEmitter.event()
            .id(eventId)
            .name("notification")
            .data(notificationJson));
        
        successCount++;
        log.debug("{} notification sent: userId={}, notificationId={}", 
            prefix, connection.getUserId(), notification.getId());
      } catch (IOException e) {
        String errorMessage = e.getMessage();
        if (errorMessage != null && errorMessage.contains("Broken pipe")) {
          log.debug("Client disconnected during batch send: userId={}", connection.getUserId());
        } else {
          log.error("Failed to send {} notification: userId={}, notificationId={}", 
              prefix, connection.getUserId(), notification.getId(), e);
        }
        break;
      } catch (Exception e) {
        log.error("Unexpected error during batch send: userId={}, notificationId={}", 
            connection.getUserId(), notification.getId(), e);
        break;
      }
    }
    
    if (successCount > 0) {
      log.info("Successfully sent {}/{} {} notifications to userId: {}", 
          successCount, notifications.size(), prefix, connection.getUserId());
    }
    
    if (successCount == 0) {
      log.warn("All {} message sends failed, cleaning up connection for userId: {}", prefix, connection.getUserId());
      connectionManager.cleanupConnection(connection.getUserId());
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