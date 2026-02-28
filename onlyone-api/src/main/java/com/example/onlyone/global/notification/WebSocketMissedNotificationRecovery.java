package com.example.onlyone.global.notification;

import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.port.NotificationDeliveryPort;
import com.example.onlyone.domain.notification.port.NotificationStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket(STOMP) SUBSCRIBE 시 놓친 알림을 복구한다.
 * {@code /user/sub/notifications} 구독 이벤트가 발생하면,
 * 미전송 알림을 조회하여 일괄 전송한다.
 *
 * {@code app.notification.delivery=websocket} 일 때만 활성화된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.delivery", havingValue = "websocket")
public class WebSocketMissedNotificationRecovery {

    private static final String NOTIFICATION_DESTINATION = "/user/sub/notifications";
    private static final int MAX_RECOVERY_SIZE = 50;
    private static final int SEND_TIMEOUT_SECONDS = 5;

    private final NotificationStoragePort storagePort;
    private final NotificationDeliveryPort deliveryPort;

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();

        if (!NOTIFICATION_DESTINATION.equals(destination)) {
            return;
        }

        if (accessor.getUser() == null) {
            return;
        }

        try {
            Long userId = Long.valueOf(accessor.getUser().getName());
            recover(userId);
        } catch (NumberFormatException e) {
            log.warn("WebSocket 놓친 알림 복구 실패: invalid userId from principal");
        }
    }

    @Transactional
    public void recover(Long userId) {
        try {
            List<NotificationItemDto> missed = storagePort
                    .findUndeliveredByUserId(userId, MAX_RECOVERY_SIZE);
            if (missed.isEmpty()) return;

            List<Long> sentIds = sendAllInParallel(userId, missed);

            if (!sentIds.isEmpty()) {
                storagePort.markDeliveredByIds(sentIds);
            }
            log.debug("WebSocket 놓친 알림 복구: userId={}, sent={}/{}", userId, sentIds.size(), missed.size());
        } catch (Exception e) {
            log.warn("WebSocket 놓친 알림 복구 실패: userId={}", userId, e);
        }
    }

    private List<Long> sendAllInParallel(Long userId, List<NotificationItemDto> missed) {
        List<CompletableFuture<Long>> futures = missed.stream()
                .map(item -> deliveryPort.deliver(userId, "notification", item)
                        .orTimeout(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .thenApply(success -> success ? item.notificationId() : null)
                        .exceptionally(ex -> null))
                .toList();

        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

        return futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();
    }
}
