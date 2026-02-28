package com.example.onlyone.global.notification;

import com.example.onlyone.domain.notification.port.NotificationDeliveryPort;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket(STOMP) 기반 알림 전송 어댑터.
 * 기존 채팅 도메인의 STOMP 인프라를 재사용한다.
 * 목적지: /sub/notifications (user prefix 사용 시 /user/{userId}/sub/notifications)
 *
 * {@code app.notification.delivery=websocket} 일 때 활성화된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.delivery", havingValue = "websocket")
public class WebSocketNotificationDeliveryAdapter implements NotificationDeliveryPort {

    private final SimpMessagingTemplate messagingTemplate;
    private final SimpUserRegistry userRegistry;
    private final ExecutorService executor;

    private static final String DESTINATION = "/sub/notifications";

    public WebSocketNotificationDeliveryAdapter(
            SimpMessagingTemplate messagingTemplate,
            SimpUserRegistry userRegistry) {
        this.messagingTemplate = messagingTemplate;
        this.userRegistry = userRegistry;
        this.executor = Executors.newFixedThreadPool(
                Runtime.getRuntime().availableProcessors() * 2,
                Thread.ofVirtual().name("ws-notify-", 0).factory());
    }

    @Override
    public boolean isUserReachable(Long userId) {
        return userRegistry.getUser(String.valueOf(userId)) != null;
    }

    @Override
    public CompletableFuture<Boolean> deliver(Long userId, String eventName, Object data) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                messagingTemplate.convertAndSendToUser(
                        String.valueOf(userId), DESTINATION, data);
                log.debug("WebSocket 알림 전송 성공: userId={}", userId);
                return true;
            } catch (Exception e) {
                log.warn("WebSocket 알림 전송 실패: userId={}", userId, e);
                return false;
            }
        }, executor);
    }

    @Override
    public String channelName() {
        return "websocket";
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
