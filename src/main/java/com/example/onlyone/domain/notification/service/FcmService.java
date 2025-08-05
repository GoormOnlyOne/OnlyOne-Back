package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FcmService {

  private final FirebaseMessaging firebaseMessaging;

  /**
   * 실제 FCM 전송을 수행합니다.
   */
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
}