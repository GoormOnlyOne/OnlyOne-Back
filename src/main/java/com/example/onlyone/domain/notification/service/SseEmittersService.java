package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.SseNotificationDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 연결 관리 서비스 - 개선된 버전
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class SseEmittersService {

  private final Map<Long, SseEmitter> sseEmitters = new ConcurrentHashMap<>();
  private final SseEmitterFactory emitterFactory;
  private final NotificationRepository notificationRepository;

  /**
   * SSE 연결 생성
   */
  public SseEmitter createSseConnection(Long userId) {
    log.debug("Creating SSE connection for user: {}", userId);

    cleanupExistingConnection(userId);
    SseEmitter emitter = createNewEmitter(userId);
    registerConnectionCallbacks(emitter, userId);
    sendInitialHeartbeat(emitter, userId);

    log.info("SSE connection established: userId={}, totalConnections={}", userId, sseEmitters.size());
    return emitter;
  }

  /**
   * SSE 알림 전송
   */
  public void sendSseNotification(Long userId, Notification notification) {
    SseEmitter emitter = sseEmitters.get(userId);
    if (emitter == null) {
      log.debug("No SSE connection for user: {}", userId);
      return;
    }

    try {
      SseNotificationDto sseDto = SseNotificationDto.from(notification);
      emitter.send(SseEmitter.event()
          .name("notification")
          .data(sseDto));

      log.debug("SSE notification sent: userId={}, notificationId={}", userId, notification.getNotificationId());
    } catch (IOException e) {
      log.error("Failed to send SSE notification: userId={}, error={}", userId, e.getMessage());
      cleanupConnection(userId, "send_failure");
    }
  }

  /**
   * 읽지 않은 개수 업데이트 전송
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

      emitter.send(SseEmitter.event()
          .name("unread_count")
          .data(countData));

      log.debug("Unread count update sent: userId={}, count={}", userId, unreadCount);
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
    SseEmitter emitter = emitterFactory.create(0L); // 무제한 타임아웃
    sseEmitters.put(userId, emitter);
    return emitter;
  }

  private void registerConnectionCallbacks(SseEmitter emitter, Long userId) {
    emitter.onCompletion(() -> cleanupConnection(userId, "completion"));
    emitter.onTimeout(() -> cleanupConnection(userId, "timeout"));
    emitter.onError((ex) -> {
      log.error("SSE connection error for user {}: {}", userId, ex.getMessage());
      cleanupConnection(userId, "error");
    });
  }

  private void sendInitialHeartbeat(SseEmitter emitter, Long userId) {
    try {
      emitter.send(SseEmitter.event()
          .name("heartbeat")
          .data("connected"));
      log.debug("Initial heartbeat sent to user: {}", userId);
    } catch (IOException e) {
      log.error("Failed to send initial heartbeat to user {}: {}", userId, e.getMessage());
      sseEmitters.remove(userId);
      throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);
    }
  }

  private void cleanupConnection(Long userId, String reason) {
    sseEmitters.remove(userId);
    log.debug("SSE connection cleaned up: userId={}, reason={}, remaining={}",
        userId, reason, sseEmitters.size());
  }
}