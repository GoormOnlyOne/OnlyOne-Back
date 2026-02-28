package com.example.onlyone.global.sse;

import com.example.onlyone.domain.notification.port.NotificationDeliveryPort;
import com.example.onlyone.sse.service.SseEventSender;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * SSE 기반 알림 전송 어댑터.
 * 기존 {@link SseEventSender}에 위임한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.delivery", havingValue = "sse", matchIfMissing = true)
public class SseNotificationDeliveryAdapter implements NotificationDeliveryPort {

    private final SseEventSender sseEventSender;

    @Override
    public boolean isUserReachable(Long userId) {
        return sseEventSender.isUserConnected(userId);
    }

    @Override
    public CompletableFuture<Boolean> deliver(Long userId, String eventName, Object data) {
        return sseEventSender.sendEvent(userId, eventName, data);
    }

    @Override
    public String channelName() {
        return "sse";
    }
}
