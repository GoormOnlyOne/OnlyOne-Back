package com.example.onlyone.global.reactive;

import com.example.onlyone.domain.notification.port.NotificationDeliveryPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * WebFlux SSE 기반 알림 전송 어댑터.
 *
 * <p>Servlet {@link com.example.onlyone.sse.service.SseEventSender}와 동등한 기능을 제공한다.</p>
 * <ul>
 *   <li>Virtual Thread(sseEventExecutor)에서 비동기 실행</li>
 *   <li>데이터 크기 검증 (64KB 제한)</li>
 *   <li>연결 없는 유저는 즉시 false 반환</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.notification.delivery", havingValue = "webflux")
public class ReactiveNotificationDeliveryAdapter implements NotificationDeliveryPort {

    private static final int MAX_DATA_SIZE = 64 * 1024;

    private final ReactiveConnectionManager connectionManager;
    private final ObjectMapper objectMapper;
    private final Executor sseEventExecutor;

    public ReactiveNotificationDeliveryAdapter(
            ReactiveConnectionManager connectionManager,
            ObjectMapper objectMapper,
            @Qualifier("sseEventExecutor") Executor sseEventExecutor) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
        this.sseEventExecutor = sseEventExecutor;
    }

    @Override
    public boolean isUserReachable(Long userId) {
        return connectionManager.hasConnection(userId);
    }

    @Override
    public CompletableFuture<Boolean> deliver(Long userId, String eventName, Object data) {
        if (!connectionManager.hasConnection(userId)) {
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String json = objectMapper.writeValueAsString(data);

                if (json.length() * 2 > MAX_DATA_SIZE) {
                    log.warn("Event data too large: userId={}, truncating", userId);
                    json = json.substring(0, MAX_DATA_SIZE / 2 - 3) + "...";
                }

                boolean sent = connectionManager.send(userId, eventName, json);
                if (sent) {
                    log.debug("WebFlux SSE 알림 전송 성공: userId={}", userId);
                }
                return sent;
            } catch (Exception e) {
                log.warn("WebFlux SSE 알림 전송 실패: userId={}", userId, e);
                return false;
            }
        }, sseEventExecutor);
    }

    @Override
    public String channelName() {
        return "webflux";
    }
}
