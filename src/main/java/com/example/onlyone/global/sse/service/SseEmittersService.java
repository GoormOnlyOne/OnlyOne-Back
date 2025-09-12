package com.example.onlyone.global.sse.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.sse.dto.SseConnection;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;

/**
 * SSE 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmittersService implements InitializingBean, DisposableBean {

  @Value("${app.notification.cleanup-interval-minutes:2}")
  private int cleanupIntervalMinutes;

  private final NotificationRepository notificationRepository;
  private final SseConnectionManager connectionManager;
  private final SseEventSender eventSender;
  private final ObjectMapper objectMapper;
  
  // Virtual Thread로 변경: 무제한 동시성 지원
  private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
      Thread.ofVirtual().name("sse-cleanup-vt").factory());
      
  // Virtual Thread로 변경: 대용량 알림 처리
  private final ExecutorService missedMessageExecutor = Executors.newVirtualThreadPerTaskExecutor();


  /**
   * SSE 연결 생성 - DB 연결 즉시 반환
   */
  public SseEmitter createSseConnection(User user, String lastEventId) {
    // SSE 연결 생성 (메모리 기반, DB 연결 불필요)
    SseEmitter emitter = connectionManager.createConnection(user);
    
    // 재연결 시에만 놓친 알림 전송 (별도 트랜잭션으로 처리)
    if (lastEventId != null && !lastEventId.trim().isEmpty()) {
      Long userId = user.getUserId();
      CompletableFuture.runAsync(() -> {
        sendMissedNotificationsAsync(userId, lastEventId);
      }, missedMessageExecutor)
          .exceptionally(ex -> {
            log.warn("Failed to send missed notifications for userId: {}, error: {}", userId, ex.getMessage());
            return null;
          });
    }
    
    // 이 시점에서 DB 연결은 반환됨, SSE만 유지
    return emitter;
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

  /**
   * SSE 연결 생성 - userId만 사용 (DB 조회 없음)
   * JWT 인증 후 userId만으로 SSE 연결 생성
   */
  public SseEmitter createSseConnectionByUserId(Long userId, String lastEventId) {
    // SSE Connection 생성 및 등록
    SseEmitter emitter = connectionManager.createConnection(userId);
    
    // 놓친 알림 전송 (lastEventId가 있는 경우)
    if (lastEventId != null && !lastEventId.trim().isEmpty()) {
      sendMissedNotificationsAsync(userId, lastEventId);
    }
    
    return emitter;
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

  /**
   * 놓친 알림 비동기 전송 - 별도 트랜잭션
   */
  public void sendMissedNotificationsAsync(Long userId, String lastEventId) {
    SseConnection connection = connectionManager.getConnection(userId);
    if (connection == null) {
      log.debug("SSE connection not found for userId: {}", userId);
      return;
    }
    sendMissedNotifications(connection, lastEventId);
  }
  
  private void sendMissedNotifications(SseConnection connection, String lastEventId) {
    long startTime = System.currentTimeMillis();
    try {
      LocalDateTime lastEventTime = parseEventIdToDateTime(lastEventId);
      if (lastEventTime == null) {
        log.debug("Invalid lastEventId format, skipping missed notifications: {}", lastEventId);
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
      long duration = System.currentTimeMillis() - startTime;
      log.debug("Missed recovery completed in {}ms", duration);
    } catch (Exception e) {
      log.error("Error processing missed notifications for userId: {}", connection.getUserId(), e);
      long duration = System.currentTimeMillis() - startTime;
      log.debug("Missed recovery failed after {}ms", duration);
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
      } else {
        // 단순 숫자나 기타 형식의 경우 - 최근 1시간으로 처리
        try {
          // 숫자인지 확인
          Long.parseLong(eventId);
          log.debug("Using simple eventId format, defaulting to 1 hour ago: {}", eventId);
          return LocalDateTime.now().minusHours(1);
        } catch (NumberFormatException ignored) {
          // 숫자가 아닌 알 수 없는 형식
        }
      }
    } catch (DateTimeParseException | NumberFormatException e) {
      log.debug("Failed to parse eventId: {}, error: {}", eventId, e.getMessage());
    }
    return null;
  }


}