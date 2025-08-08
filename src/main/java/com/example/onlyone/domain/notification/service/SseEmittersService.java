package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.responseDto.SseNotificationDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
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
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 연결 관리 서비스 - Last-Event-ID 지원
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SseEmittersService {

  @Value("${app.notification.sse-timeout-millis:1800000}") // 기본값 30분
  private long sseTimeoutMillis;

  private final Map<Long, SseEmitter> sseEmitters = new ConcurrentHashMap<>();
  private final NotificationRepository notificationRepository;

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
    log.debug("Creating SSE connection for user: {}, lastEventId: {}", userId, lastEventId);

    cleanupExistingConnection(userId);
    SseEmitter emitter = createNewEmitter(userId);
    registerConnectionCallbacks(emitter, userId);
    
    // 초기 연결 확인 후 놓친 메시지 전송
    if (sendInitialHeartbeat(emitter, userId)) {
      sendMissedNotifications(emitter, userId, lastEventId);
    }

    log.info("SSE connection established: userId={}, totalConnections={}", userId, sseEmitters.size());
    return emitter;
  }

  /**
   * SSE 알림 전송 - Event ID 포함
   */
  public void sendSseNotification(Long userId, AppNotification appNotification) {
    SseEmitter emitter = sseEmitters.get(userId);
    if (emitter == null) {
      log.debug("No SSE connection for user: {}", userId);
      return;
    }

    try {
      SseNotificationDto sseDto = SseNotificationDto.from(appNotification);
      String eventId = generateEventId(appNotification);
      
      emitter.send(SseEmitter.event()
          .id(eventId)
          .name("notification")
          .data(sseDto));

      log.debug("SSE notification sent: userId={}, notificationId={}, eventId={}", 
          userId, appNotification.getNotificationId(), eventId);
    } catch (IOException e) {
      log.error("Failed to send SSE notification: userId={}, error={}", userId, e.getMessage());
      cleanupConnection(userId, "send_failure");
    }
  }

  /**
   * 읽지 않은 개수 업데이트 전송 - Event ID 포함
   */
  public void sendUnreadCountUpdate(Long userId) {
    SseEmitter emitter = sseEmitters.get(userId);
    if (emitter == null) {
      log.debug("No SSE connection for unread count update: userId={}", userId);
      return;
    }

    try {
      Long unreadCount = notificationRepository.countByUser_UserIdAndIsReadFalse(userId);
      Map<String, Long> countData = new HashMap<>();
      countData.put("unread_count", unreadCount);

      String eventId = generateCountEventId();
      
      emitter.send(SseEmitter.event()
          .id(eventId)
          .name("unread_count")
          .data(countData));

      log.debug("Unread count update sent: userId={}, count={}, eventId={}", userId, unreadCount, eventId);
    } catch (IOException e) {
      log.error("Failed to send unread count update: userId={}, error={}", userId, e.getMessage());
      cleanupConnection(userId, "count_update_failure");
    }
  }

  // ================================
  // Private Helper Methods
  // ================================

  private void cleanupExistingConnection(Long userId) {
    SseEmitter existingEmitter = sseEmitters.get(userId);
    if (existingEmitter != null) {
      log.debug("Cleaning up existing SSE connection for user: {}", userId);
      existingEmitter.complete();
      sseEmitters.remove(userId);
    }
  }

  private SseEmitter createNewEmitter(Long userId) {
    SseEmitter emitter = new SseEmitter(sseTimeoutMillis);
    sseEmitters.put(userId, emitter);
    
    log.debug("SSE emitter created: userId={}, timeout={}ms", userId, sseTimeoutMillis);
    return emitter;
  }

  private void registerConnectionCallbacks(SseEmitter emitter, Long userId) {
    emitter.onCompletion(() -> {
      log.debug("SSE connection completed normally: userId={}", userId);
      cleanupConnection(userId, "completion");
    });
    
    emitter.onTimeout(() -> {
      log.info("SSE connection timed out: userId={}, timeout={}ms", userId, sseTimeoutMillis);
      cleanupConnection(userId, "timeout");
    });
    
    emitter.onError((ex) -> {
      log.error("SSE connection error for user {}: {}", userId, ex.getMessage());
      cleanupConnection(userId, "error");
    });
  }

  private boolean sendInitialHeartbeat(SseEmitter emitter, Long userId) {
    try {
      String eventId = generateHeartbeatEventId();
      emitter.send(SseEmitter.event()
          .id(eventId)
          .name("heartbeat")
          .data("connected"));
      log.debug("Initial heartbeat sent to user: {}, eventId: {}", userId, eventId);
      return true;
    } catch (IOException e) {
      log.error("Failed to send initial heartbeat to user {}: {}", userId, e.getMessage());
      sseEmitters.remove(userId);
      throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);
    }
  }

  /**
   * 놓친 알림 전송 (Last-Event-ID 기반)
   */
  private void sendMissedNotifications(SseEmitter emitter, Long userId, String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) {
      log.debug("No lastEventId provided, skipping missed notifications for user: {}", userId);
      return;
    }

    try {
      LocalDateTime lastEventTime = parseEventIdToDateTime(lastEventId);
      if (lastEventTime != null) {
        List<AppNotification> missedNotifications = notificationRepository
            .findByUser_UserIdAndCreatedAtAfterOrderByCreatedAtAsc(userId, lastEventTime);

        log.info("Sending {} missed notifications to user: {}, since: {}", 
            missedNotifications.size(), userId, lastEventTime);

        for (AppNotification notification : missedNotifications) {
          try {
            SseNotificationDto sseDto = SseNotificationDto.from(notification);
            String eventId = generateEventId(notification);
            
            emitter.send(SseEmitter.event()
                .id(eventId)
                .name("missed_notification")
                .data(sseDto));

            log.debug("Missed notification sent: userId={}, notificationId={}, eventId={}", 
                userId, notification.getNotificationId(), eventId);
          } catch (IOException e) {
            log.error("Failed to send missed notification: userId={}, notificationId={}, error={}", 
                userId, notification.getNotificationId(), e.getMessage());
            break; // 전송 실패 시 중단
          }
        }
      }
    } catch (Exception e) {
      log.error("Failed to process missed notifications for user {}: {}", userId, e.getMessage());
    }
  }

  private void cleanupConnection(Long userId, String reason) {
    sseEmitters.remove(userId);
    log.debug("SSE connection cleaned up: userId={}, reason={}, remaining={}",
        userId, reason, sseEmitters.size());
  }

  // ================================
  // Event ID 생성 및 파싱 메서드
  // ================================

  /**
   * 알림용 Event ID 생성 (notification_{notificationId}_{timestamp})
   */
  private String generateEventId(AppNotification appNotification) {
    return String.format("notification_%d_%s", 
        appNotification.getNotificationId(), 
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
   * Event ID에서 timestamp 추출
   */
  private LocalDateTime parseEventIdToDateTime(String eventId) {
    try {
      // notification_{id}_{timestamp} 또는 count_{timestamp} 또는 heartbeat_{timestamp} 형식
      String[] parts = eventId.split("_");
      if (parts.length >= 2) {
        String timestampPart = parts[parts.length - 1]; // 마지막 부분이 timestamp
        return LocalDateTime.parse(timestampPart);
      }
    } catch (DateTimeParseException e) {
      log.warn("Invalid eventId format for parsing timestamp: {}", eventId);
    }
    return null;
  }
}