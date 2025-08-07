package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FCM 서비스 - 기존 ErrorCode 활용한 예외 처리 개선
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FcmService {

  private final FirebaseMessaging firebaseMessaging;
  private final NotificationRepository notificationRepository;

  /**
   * FCM 알림 전송
   * 모든 예외를 CustomException으로 변환하여 글로벌 예외 처리기에서 처리
   */
  public void sendFcmNotification(Notification notification) {
    try {
      String token = validateAndGetToken(notification);
      Message message = buildMessage(notification, token);

      String response = firebaseMessaging.send(message);
      log.info("FCM sent successfully: response={}, notificationId={}",
          response, notification.getNotificationId());

    } catch (IllegalArgumentException e) {
      // FCM 토큰 관련 예외
      log.error("FCM token validation failed: notificationId={}, error={}",
          notification.getNotificationId(), e.getMessage());
      throw new CustomException(ErrorCode.FCM_TOKEN_NOT_FOUND);

    } catch (FirebaseMessagingException e) {
      // Firebase 서비스 예외
      log.error("FCM message send failed: notificationId={}, errorCode={}, error={}",
          notification.getNotificationId(), e.getErrorCode(), e.getMessage());
      throw new CustomException(ErrorCode.FCM_MESSAGE_SEND_FAILED);

    } catch (Exception e) {
      // 기타 예상치 못한 예외
      log.error("Unexpected FCM error: notificationId={}, error={}",
          notification.getNotificationId(), e.getMessage(), e);
      throw new CustomException(ErrorCode.FCM_MESSAGE_SEND_FAILED);
    }
  }

  /**
   * 실패한 FCM 알림 비동기 재전송
   */
  @Async
  @Transactional
  public void retryFailedNotifications(Long userId) {
    log.info("Starting FCM retry for user: {}", userId);

    try {
      List<Notification> failedNotifications = notificationRepository.findByUser_UserIdAndFcmSentFalse(userId);

      if (failedNotifications.isEmpty()) {
        log.info("No failed notifications to retry for user: {}", userId);
        return;
      }

      int successCount = 0;
      for (Notification notification : failedNotifications) {
        if (retrySingleNotification(notification)) {
          successCount++;
        }
      }

      log.info("FCM retry completed: user={}, total={}, success={}",
          userId, failedNotifications.size(), successCount);

    } catch (Exception e) {
      log.error("FCM retry process failed for user: {}, error={}", userId, e.getMessage(), e);
      // 재시도 프로세스 실패는 전체 시스템에 영향을 주지 않도록 예외를 다시 던지지 않음
    }
  }

  // ================================
  // Private Helper Methods
  // ================================

  private String validateAndGetToken(Notification notification) {
    String token = notification.getUser().getFcmToken();
    if (token == null || token.isBlank()) {
      String errorMsg = String.format("FCM token not found for user: %s",
          notification.getUser().getUserId());
      log.warn(errorMsg);
      throw new IllegalArgumentException(errorMsg);
    }
    return token;
  }

  private Message buildMessage(Notification notification, String token) {
    try {
      return Message.builder()
          .setToken(token)
          .setNotification(buildNotificationPayload(notification))
          .putAllData(buildDataPayload(notification))
          .build();
    } catch (Exception e) {
      log.error("Failed to build FCM message: notificationId={}, error={}",
          notification.getNotificationId(), e.getMessage());
      throw new IllegalArgumentException("Failed to build FCM message", e);
    }
  }

  private com.google.firebase.messaging.Notification buildNotificationPayload(Notification notification) {
    return com.google.firebase.messaging.Notification.builder()
        .setTitle(notification.getNotificationType().getType().name())
        .setBody(notification.getContent())
        .build();
  }

  private Map<String, String> buildDataPayload(Notification notification) {
    Map<String, String> dataMap = new HashMap<>();
    dataMap.put("notificationId", notification.getNotificationId().toString());
    dataMap.put("type", notification.getNotificationType().getType().name());
    dataMap.put("content", notification.getContent());
    dataMap.put("createdAt", notification.getCreatedAt().toString());
    return dataMap;
  }

  private boolean retrySingleNotification(Notification notification) {
    try {
      sendFcmNotification(notification);
      notification.markFcmSent(true);
      log.debug("FCM retry successful: notificationId={}", notification.getNotificationId());
      return true;
    } catch (CustomException e) {
      // CustomException은 이미 로깅됨
      log.debug("FCM retry failed with CustomException: notificationId={}, errorCode={}",
          notification.getNotificationId(), e.getErrorCode());
      return false;
    } catch (Exception e) {
      log.error("FCM retry failed with unexpected error: notificationId={}, error={}",
          notification.getNotificationId(), e.getMessage());
      return false;
    }
  }
}