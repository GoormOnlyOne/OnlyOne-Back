package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.entity.FcmToken;
import com.example.onlyone.domain.notification.repository.FcmTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Firebase Cloud Messaging(FCM) 기반 알림 전송 어댑터.
 * {@code app.notification.delivery=fcm} 일 때 활성화된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.delivery", havingValue = "fcm")
public class FcmNotificationDeliveryAdapter implements NotificationDeliveryPort {

    private final FirebaseMessaging firebaseMessaging;
    private final FcmTokenRepository fcmTokenRepository;
    private final ObjectMapper objectMapper;

    @Override
    public boolean isUserReachable(Long userId) {
        return fcmTokenRepository.existsByUserId(userId);
    }

    @Override
    public CompletableFuture<Boolean> deliver(Long userId, String eventName, Object data) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<FcmToken> tokens = fcmTokenRepository.findByUserId(userId);
                if (tokens.isEmpty()) {
                    log.debug("FCM 토큰 없음: userId={}", userId);
                    return false;
                }

                String jsonData = objectMapper.writeValueAsString(data);

                boolean anySent = false;
                for (FcmToken fcmToken : tokens) {
                    try {
                        Message message = Message.builder()
                                .setToken(fcmToken.getToken())
                                .setNotification(Notification.builder()
                                        .setTitle("OnlyOne")
                                        .setBody(extractContent(data))
                                        .build())
                                .putData("event", eventName)
                                .putData("payload", jsonData)
                                .build();

                        firebaseMessaging.send(message);
                        anySent = true;
                    } catch (Exception e) {
                        log.warn("FCM 전송 실패: userId={}, token={}...", userId,
                                fcmToken.getToken().substring(0, Math.min(10, fcmToken.getToken().length())), e);
                    }
                }
                return anySent;
            } catch (Exception e) {
                log.error("FCM 전송 중 오류: userId={}", userId, e);
                return false;
            }
        });
    }

    @Override
    public String channelName() {
        return "fcm";
    }

    private String extractContent(Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            if (json.contains("\"content\"")) {
                var node = objectMapper.readTree(json);
                if (node.has("content")) {
                    return node.get("content").asText();
                }
            }
        } catch (Exception ignored) {
        }
        return "새로운 알림이 있습니다";
    }
}
