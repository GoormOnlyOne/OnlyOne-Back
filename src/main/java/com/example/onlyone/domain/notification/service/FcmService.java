package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
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
  public void sendFcmNotification(AppNotification appNotification) {
    try {
      String token = validateAndGetToken(appNotification);
      Message message = buildMessage(appNotification, token);

      String response = firebaseMessaging.send(message);
      log.info("FCM sent successfully: response={}, notificationId={}",
          response, appNotification.getNotificationId());

    } catch (IllegalArgumentException e) {
      // FCM 토큰 관련 예외
      log.error("FCM token validation failed: notificationId={}, error={}",
          appNotification.getNotificationId(), e.getMessage());
      throw new CustomException(ErrorCode.FCM_TOKEN_NOT_FOUND);

    } catch (FirebaseMessagingException e) {
      // Firebase 서비스 예외
      String errorCode = e.getErrorCode() != null ? e.getErrorCode().toString() : "UNKNOWN";
      log.error("FCM message send failed: notificationId={}, errorCode={}, error={}",
          appNotification.getNotificationId(), errorCode, e.getMessage());
      
      // 특정 에러 코드에 따른 처리
      if (isInvalidTokenError(e)) {
        // 무효한 토큰인 경우 사용자의 토큰을 정리
        log.warn("Invalid FCM token detected for user: {}, clearing token", 
            appNotification.getUser().getUserId());
        appNotification.getUser().clearFcmToken();
      }
      
      throw new CustomException(ErrorCode.FCM_MESSAGE_SEND_FAILED);

    } catch (Exception e) {
      // 기타 예상치 못한 예외
      log.error("Unexpected FCM error: notificationId={}, error={}",
          appNotification.getNotificationId(), e.getMessage(), e);
      throw new CustomException(ErrorCode.FCM_MESSAGE_SEND_FAILED);
    }
  }
  
  /**
   * Firebase 예외가 무효한 토큰 에러인지 확인
   */
  private boolean isInvalidTokenError(FirebaseMessagingException e) {
    if (e.getErrorCode() == null) return false;
    
    String errorCode = e.getErrorCode().toString();
    return "INVALID_ARGUMENT".equals(errorCode) || 
           "UNREGISTERED".equals(errorCode) ||
           "INVALID_REGISTRATION".equals(errorCode);
  }

  /**
   * 실패한 FCM 알림 비동기 재전송
   */
  @Async
  @Transactional
  public void retryFailedNotifications(Long userId) {
    log.info("Starting FCM retry for user: {}", userId);

    try {
      List<AppNotification> failedAppNotifications = notificationRepository.findByUser_UserIdAndFcmSentFalse(userId);

      if (failedAppNotifications.isEmpty()) {
        log.info("No failed notifications to retry for user: {}", userId);
        return;
      }

      int successCount = 0;
      for (AppNotification appNotification : failedAppNotifications) {
        if (retrySingleNotification(appNotification)) {
          successCount++;
        }
      }

      log.info("FCM retry completed: user={}, total={}, success={}",
          userId, failedAppNotifications.size(), successCount);

    } catch (Exception e) {
      log.error("FCM retry process failed for user: {}, error={}", userId, e.getMessage(), e);
    }
  }

  // ================================
  // Private Helper Methods
  // ================================

  private String validateAndGetToken(AppNotification appNotification) {
    String token = appNotification.getUser().getFcmToken();
    if (token == null || token.isBlank()) {
      String errorMsg = String.format("FCM token not found for user: %s",
          appNotification.getUser().getUserId());
      log.warn(errorMsg);
      throw new IllegalArgumentException(errorMsg);
    }
    
    // FCM 토큰 형식 검증
    if (!isValidFcmToken(token)) {
      String errorMsg = String.format("Invalid FCM token format for user: %s", 
          appNotification.getUser().getUserId());
      log.warn(errorMsg);
      throw new IllegalArgumentException(errorMsg);
    }
    
    return token;
  }
  
  /**
   * FCM 토큰 형식 유효성 검증
   */
  private boolean isValidFcmToken(String token) {
    // FCM 토큰은 152자 이상이고 특정 패턴을 가짐
    if (token.length() < 140 || token.length() > 165) {
      return false;
    }
    
    // 기본적인 패턴 검증 (영문자, 숫자, 하이픈, 언더스코어, 콜론만 허용)
    return token.matches("^[a-zA-Z0-9_-]+:[a-zA-Z0-9_-]+$") || 
           token.matches("^[a-zA-Z0-9_-]+$");
  }

  private Message buildMessage(AppNotification appNotification, String token) {
    try {
      return Message.builder()
          .setToken(token)
          .setNotification(buildNotificationPayload(appNotification))
          .putAllData(buildDataPayload(appNotification))
          .build();
    } catch (Exception e) {
      log.error("Failed to build FCM message: notificationId={}, error={}",
          appNotification.getNotificationId(), e.getMessage());
      throw new IllegalArgumentException("Failed to build FCM message", e);
    }
  }

  private Notification buildNotificationPayload(AppNotification appNotification) {
    return com.google.firebase.messaging.Notification.builder()
        .setTitle(appNotification.getNotificationType().getType().name())
        .setBody(appNotification.getContent())
        .build();
  }

  private Map<String, String> buildDataPayload(AppNotification appNotification) {
    Map<String, String> dataMap = new HashMap<>();
    dataMap.put("notificationId", appNotification.getNotificationId().toString());
    dataMap.put("type", appNotification.getNotificationType().getType().name());
    dataMap.put("content", appNotification.getContent());
    dataMap.put("createdAt", appNotification.getCreatedAt().toString());
    return dataMap;
  }

  private boolean retrySingleNotification(AppNotification appNotification) {
    try {
      sendFcmNotification(appNotification);
      appNotification.markFcmSent(true);
      log.debug("FCM retry successful: notificationId={}", appNotification.getNotificationId());
      return true;
    } catch (CustomException e) {
      log.debug("FCM retry failed with CustomException: notificationId={}, errorCode={}",
          appNotification.getNotificationId(), e.getErrorCode());
      return false;
    } catch (Exception e) {
      log.error("FCM retry failed with unexpected error: notificationId={}, error={}",
          appNotification.getNotificationId(), e.getMessage());
      return false;
    }
  }
}