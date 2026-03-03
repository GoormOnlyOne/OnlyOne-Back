package com.example.onlyone.domain.settlement.port;

import com.example.onlyone.domain.settlement.config.redis.RedisStreamsSettlementConfig;
import com.example.onlyone.domain.settlement.entity.OutboxStatus;
import com.example.onlyone.domain.settlement.event.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Redis Streams 기반 Outbox 메시지 릴레이.
 * {@code app.settlement.message-broker=redis-streams} 일 때 활성화된다.
 *
 * <p>XADD로 메시지를 스트림에 추가한다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.settlement.message-broker", havingValue = "redis-streams")
public class RedisStreamsOutboxRelay implements OutboxMessageRelay {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void publishBatch(List<OutboxEvent> events) {
        LocalDateTime now = LocalDateTime.now();
        for (OutboxEvent event : events) {
            String streamKey = routeStream(event.getEventType());
            Map<String, String> fields = Map.of(
                    "key", event.getKeyString(),
                    "payload", event.getPayload(),
                    "eventType", event.getEventType()
            );
            redisTemplate.opsForStream().add(StringRecord.of(fields).withStreamKey(streamKey));
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(now);
        }
    }

    @Override
    public void publishSingle(OutboxEvent event) {
        String streamKey = routeStream(event.getEventType());
        Map<String, String> fields = Map.of(
                "key", event.getKeyString(),
                "payload", event.getPayload(),
                "eventType", event.getEventType()
        );
        redisTemplate.opsForStream().add(StringRecord.of(fields).withStreamKey(streamKey));
    }

    @Override
    public String brokerName() {
        return "redis-streams";
    }

    private String routeStream(String eventType) {
        return switch (eventType) {
            case "SettlementProcessEvent" -> RedisStreamsSettlementConfig.SETTLEMENT_PROCESS_STREAM;
            case "ParticipantSettlementResult" -> RedisStreamsSettlementConfig.USER_SETTLEMENT_RESULT_STREAM;
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }
}
