package com.example.onlyone.global.sse;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
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

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.DisposableBean;

/**
 * SSE 연결 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SseEmittersService implements InitializingBean, DisposableBean {

  @Value("${app.notification.sse-timeout-millis:1800000}") // 기본값 30분
  private long sseTimeoutMillis;

  private final NotificationRepository notificationRepository;
  private final ConcurrentHashMap<Long, SseConnection> activeConnections = new ConcurrentHashMap<>();
  
  // 정리 스케줄러
  private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor(
      r -> new Thread(r, "sse-cleanup-thread"));
  
  private static final long CLEANUP_INTERVAL_MINUTES = 10; // 10분마다 정리

  /**
   * SSE 연결 생성
   */
  public SseEmitter createSseConnection(Long userId) {
    cleanupExistingConnection(userId);
    
    SseConnection connection = SseConnection.builder()
            .userId(userId)
            .emitter(new SseEmitter(sseTimeoutMillis))
            .connectionTime(LocalDateTime.now())
            .build();
    
    activeConnections.put(userId, connection);
    registerConnectionCallbacks(connection);
    sendInitialHeartbeat(connection);

    log.info("SSE connection established: userId={}, totalConnections={}", 
            userId, activeConnections.size());
    
    return connection.getEmitter();
  }

  /**
   * SSE 연결 생성 (Last-Event-ID 지원)
   */
  public SseEmitter createSseConnection(Long userId, String lastEventId) {
    cleanupExistingConnection(userId);
    
    SseConnection connection = SseConnection.builder()
            .userId(userId)
            .emitter(new SseEmitter(sseTimeoutMillis))
            .connectionTime(LocalDateTime.now())
            .build();
    
    activeConnections.put(userId, connection);
    registerConnectionCallbacks(connection);
    sendInitialHeartbeat(connection);
    
    // 놓친 메시지 전송
    if (lastEventId != null && !lastEventId.trim().isEmpty()) {
      sendMissedMessages(connection, lastEventId);
    }

    log.info("SSE connection established: userId={}, lastEventId={}, totalConnections={}", 
            userId, lastEventId, activeConnections.size());
    
    return connection.getEmitter();
  }

  /**
   * 범용 SSE 이벤트 전송
   * @param userId 사용자 ID
   * @param eventName 이벤트 이름
   * @param data 전송할 데이터
   */
  public void sendEvent(Long userId, String eventName, Object data) {
    SseConnection connection = activeConnections.get(userId);
    if (connection == null) {
      log.debug("No SSE connection found for user: {}", userId);
      return;
    }

    try {
      String eventId = "evt_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
      
      connection.getEmitter().send(SseEmitter.event()
          .id(eventId)
          .name(eventName)
          .data(data));

      log.debug("SSE event sent: userId={}, eventName={}, eventId={}", userId, eventName, eventId);
    } catch (IOException e) {
      log.error("Failed to send SSE event: userId={}, eventName={}", userId, eventName, e);
      cleanupConnection(userId);
      throw new CustomException(ErrorCode.SSE_SEND_FAILED);
    }
  }


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


  private void cleanupConnection(Long userId) {
    activeConnections.remove(userId);
  }
  

  /**
   * 연결 수 조회
   */
  public int getActiveConnectionCount() {
    return activeConnections.size();
  }

  public boolean isUserConnected(Long userId) {
    return activeConnections.containsKey(userId);
  }

  /**
   * 사용자의 마지막 연결 시간 조회
   */
  public LocalDateTime getLastConnectedTime(Long userId) {
    SseConnection connection = activeConnections.get(userId);
    return connection != null ? connection.getConnectionTime() : null;
  }

  /**
   * 사용자의 연결 지속 시간 조회 (문자열)
   */
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
  
  /**
   * 모든 연결 제거
   */
  public void clearAllConnections() {
    try {
      // 활성 연결 정리
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


  /**
   * 서비스 초기화
   */
  @Override
  public void afterPropertiesSet() {
    // 연결 상태 점검
    cleanupScheduler.scheduleWithFixedDelay(
        this::cleanupStaleConnections, 
        CLEANUP_INTERVAL_MINUTES, 
        CLEANUP_INTERVAL_MINUTES, 
        TimeUnit.MINUTES
    );
    
    log.info("SSE cleanup scheduler started with interval: {} minutes", CLEANUP_INTERVAL_MINUTES);
  }

  /**
   * 서비스 종료
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
   * 만료된 연결 정리
   */
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
      // 스케줄러 정리 실패는 서비스 중단을 야기하지 않도록 예외를 던지지 않음
    }
  }

  // Event ID 생성 및 파싱


  /**
   * 하트비트 Event ID 생성
   */
  private String generateHeartbeatEventId() {
    return String.format("heartbeat_%s", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
  }

  /**
   * 놓친 메시지 전송 (Last-Event-ID 기반)
   */
  private void sendMissedMessages(SseConnection connection, String lastEventId) {
    try {
      LocalDateTime lastEventTime = parseEventIdToDateTime(lastEventId);
      if (lastEventTime == null) {
        log.warn("Invalid lastEventId format, skipping missed messages: {}", lastEventId);
        return;
      }
      
      // 마지막 이벤트 시간 이후의 읽지 않은 알림들 조회
      List<Notification> missedNotifications = notificationRepository
          .findUnreadNotificationsByUserId(connection.getUserId())
          .stream()
          .filter(notification -> notification.getCreatedAt().isAfter(lastEventTime))
          .sorted((n1, n2) -> n1.getCreatedAt().compareTo(n2.getCreatedAt())) // 시간순 정렬
          .toList();
      
      if (!missedNotifications.isEmpty()) {
        log.info("Sending {} missed notifications to userId: {}", missedNotifications.size(), connection.getUserId());
        
        for (Notification notification : missedNotifications) {
          try {
            String eventId = "recovery_" + System.currentTimeMillis() + "_" + notification.getId();
            connection.getEmitter().send(SseEmitter.event()
                .id(eventId)
                .name("notification")
                .data(notification));
            
            log.debug("Missed notification sent: userId={}, notificationId={}", 
                connection.getUserId(), notification.getId());
          } catch (IOException e) {
            log.error("Failed to send missed notification: userId={}, notificationId={}", 
                connection.getUserId(), notification.getId(), e);
            cleanupConnection(connection.getUserId());
            throw new CustomException(ErrorCode.SSE_SEND_FAILED);
          }
        }
      }
    } catch (Exception e) {
      log.error("Error processing missed messages for userId: {}", connection.getUserId(), e);
      // 놓친 메시지 전송 실패는 연결 자체를 실패시키지 않음
    }
  }

  /**
   * Event ID에서 DateTime 파싱
   */
  private LocalDateTime parseEventIdToDateTime(String eventId) {
    try {
      if (eventId.startsWith("evt_")) {
        // evt_1234567890_abcd1234 형식에서 타임스탬프 추출
        String[] parts = eventId.split("_");
        if (parts.length >= 2) {
          long timestamp = Long.parseLong(parts[1]);
          return LocalDateTime.ofEpochSecond(timestamp / 1000, 0, java.time.ZoneOffset.UTC);
        }
      } else if (eventId.startsWith("heartbeat_")) {
        // heartbeat_2024-01-01T12:00:00 형식
        String dateTimePart = eventId.substring("heartbeat_".length());
        return LocalDateTime.parse(dateTimePart, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
      }
    } catch (DateTimeParseException | NumberFormatException e) {
      log.warn("Failed to parse eventId: {}, error: {}", eventId, e.getMessage());
    }
    return null;
  }

}