package com.example.onlyone.domain.notification.port;

import com.example.onlyone.domain.notification.config.RedisNotificationPubSubConfig;
import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 기반 알림 이벤트 발행기.
 * 기존 Redis 인프라를 재사용하므로 추가 의존성이 필요 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notification.event-bus", havingValue = "redis-pubsub")
public class RedisNotificationPublisher implements NotificationEventPublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(NotificationCreatedEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(RedisNotificationPubSubConfig.CHANNEL, json);
            log.debug("Redis Pub/Sub 알림 이벤트 발행: notificationId={}", event.notificationId());
        } catch (Exception e) {
            log.error("Redis Pub/Sub 알림 이벤트 발행 실패: notificationId={}", event.notificationId(), e);
        }
    }
}
