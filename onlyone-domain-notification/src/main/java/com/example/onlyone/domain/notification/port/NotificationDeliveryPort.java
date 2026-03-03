package com.example.onlyone.domain.notification.port;

import java.util.concurrent.CompletableFuture;

/**
 * 알림 전송 추상화 포트.
 * SSE, FCM, WebFlux 등 구현체를 {@code app.notification.delivery} 프로퍼티로 교체할 수 있다.
 */
public interface NotificationDeliveryPort {

    boolean isUserReachable(Long userId);

    CompletableFuture<Boolean> deliver(Long userId, String eventName, Object data);

    String channelName();
}
