package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.dto.response.NotificationSseDto;
import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 기반 알림 이벤트 소비자.
 * 로컬 인스턴스에 해당 유저의 연결이 있으면 전송한다.
 * 채팅 도메인의 {@code ChatSubscriber} 패턴을 참고.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.event-bus", havingValue = "redis-pubsub")
public class RedisNotificationConsumer implements MessageListener {

    private final NotificationDeliveryPort deliveryPort;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            NotificationCreatedEvent event = objectMapper.readValue(
                    message.getBody(), NotificationCreatedEvent.class);

            if (deliveryPort.isUserReachable(event.userId())) {
                NotificationSseDto dto = NotificationSseDto.from(event);
                deliveryPort.deliver(event.userId(), "notification", dto);
                log.debug("Redis Pub/Sub 알림 전송: userId={}, notificationId={}",
                        event.userId(), event.notificationId());
            }
        } catch (Exception e) {
            log.error("Redis Pub/Sub 알림 이벤트 처리 실패", e);
        }
    }
}
