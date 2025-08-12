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
      return;
    }

    try {
      SseNotificationDto sseDto = SseNotificationDto.from(appNotification);
      String eventId = generateEventId(appNotification);
      
      emitter.send(SseEmitter.event()
          .id(eventId)
          .name("notification")
          .data(sseDto));

    } catch (IOException e) {
      cleanupConnection(userId, "send_failure");
    }
  }

  /**
   * 읽지 않은 개수 업데이트 전송 - Event ID 포함
   */
  public void sendUnreadCountUpdate(Long userId) {
    SseEmitter emitter = sseEmitters.get(userId);
    if (emitter == null) {
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

    } catch (IOException e) {
      cleanupConnection(userId, "count_update_failure");
    }
  }

  // ================================
  // Private Helper Methods
  // ================================

  private void cleanupExistingConnection(Long userId) {
    SseEmitter existingEmitter = sseEmitters.get(userId);
    if (existingEmitter != null) {
      existingEmitter.complete();
      sseEmitters.remove(userId);
    }
  }

  private SseEmitter createNewEmitter(Long userId) {
    SseEmitter emitter = new SseEmitter(sseTimeoutMillis);
    sseEmitters.put(userId, emitter);
    
    return emitter;
  }

  private void registerConnectionCallbacks(SseEmitter emitter, Long userId) {
    emitter.onCompletion(() -> {
      cleanupConnection(userId, "completion");
    });
    
    emitter.onTimeout(() -> {
      log.info("SSE connection timed out: userId={}, timeout={}ms", userId, sseTimeoutMillis);
      cleanupConnection(userId, "timeout");
    });
    
    emitter.onError((ex) -> {
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
      return true;
    } catch (IOException e) {
      sseEmitters.remove(userId);
      throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);
    }
  }

  /**
   * 놓친 알림 전송 (Last-Event-ID 기반)
   */
  private void sendMissedNotifications(SseEmitter emitter, Long userId, String lastEventId) {
    if (lastEventId == null || lastEventId.isBlank()) {
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

          } catch (IOException e) {
            break; // 전송 실패 시 중단
          }
        }
      }
    } catch (Exception e) {
      // 놓친 알림 처리 실패 시 무시
    }
  }

  private void cleanupConnection(Long userId, String reason) {
    sseEmitters.remove(userId);
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
      // 파싱 실패 시 무시
    }
    return null;
  }
}