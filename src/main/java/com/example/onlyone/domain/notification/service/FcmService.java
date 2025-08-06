package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FcmService {

  private final FirebaseMessaging firebaseMessaging;
  private final NotificationRepository notificationRepository;

  public void sendFcmNotification(Notification notification) throws FirebaseMessagingException {
    String token = notification.getUser().getFcmToken();
    if (token == null || token.isBlank()) {
      log.warn("FCM 토큰이 없습니다. - 사용자 ID: {}", notification.getUser().getUserId());
      return;
    }

    // 추가로 전달할 데이터가 있으면 맵에 담아 보냅니다.
    Map<String, String> data = new HashMap<>();
    data.put("notificationId", String.valueOf(notification.getNotificationId()));
    data.put("type", notification.getNotificationType().getType().name());

    com.google.firebase.messaging.Notification fcmNotification =
        com.google.firebase.messaging.Notification.builder()
            .setTitle(notification.getNotificationType().getType().name())
            .setBody(notification.getContent())
            .build();

    Message message = Message.builder()
        .setToken(token)
        .setNotification(fcmNotification)
        .putAllData(data)
        .build();

    String response = firebaseMessaging.send(message);
    log.info("FCM 전송 완료 - response: {}, 알림 ID: {}", response, notification.getNotificationId());
  }

  /**
   * 실패한 FCM 알림을 비동기로 재전송합니다.
   * 트랜잭션 경계를 걸고, 각 알림 전송 성공 시 fcmSent=true 로 업데이트합니다.
   */
  @Async
  @Transactional
  public void retryFailedNotifications(Long userId) {
    log.info("실패한 알림 재전송 시작 - 사용자 ID: {}", userId);

    // 1. DB에 남아 있는 실패 알림(=fcmSent=false) 조회
    List<Notification> failedList = notificationRepository
        .findByUser_UserIdAndFcmSentFalse(userId);

    if (failedList.isEmpty()) {
      log.info("재전송 대상 알림이 없습니다. userId={}", userId);
      return;
    }

    // 2. 개별 알림에 대해 FCM 전송 시도
    for (Notification notification : failedList) {
      try {
        sendFcm(notification);
        // 전송 성공 시 플래그 변경
        notification.markFcmSent(true);
        log.info("FCM 재전송 성공 - notificationId={}", notification.getNotificationId());
      } catch (Exception ex) {
        log.error("FCM 재전송 실패 - notificationId={}, error={}",
            notification.getNotificationId(), ex.getMessage(), ex);
        // 실패한 채로 두고 다음 알림 진행
      }
    }

    // 3. 변경된 fcmSent 값(=true)만 DB에 반영
    //    Spring Data JPA는 @Transactional 안에서 변경 감지(dirty checking)로 자동 반영해 줍니다.
    log.info("재전송 처리 완료 - 총 {}건 중 {}건 성공",
        failedList.size(),
        failedList.stream().filter(Notification::getFcmSent).count());
  }

  /**
   * 실제 FCM 메시지 전송 로직
   * @throws FirebaseMessagingException 전송 실패 시 예외
   */
  private void sendFcm(Notification notification) throws FirebaseMessagingException {
    // 사용자 디바이스 토큰이 있어야 전송
    String token = notification.getUser().getFcmToken();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("FCM 토큰이 없습니다. notificationId="
          + notification.getNotificationId());
    }

    // FCM Message 빌드 (notification or data payload)
    com.google.firebase.messaging.Message message =
        com.google.firebase.messaging.Message.builder()
            .setToken(token)
            .putData("notificationId", notification.getNotificationId().toString())
            .putData("type", notification.getNotificationType().getType().name())
            .putData("content", notification.getContent())
            .putData("createdAt", notification.getCreatedAt().toString())
            .build();

    // 동기 방식 전송. 비동기 방식으로 사용하려면 sendAsync() 활용
    firebaseMessaging.send(message);
  }
}