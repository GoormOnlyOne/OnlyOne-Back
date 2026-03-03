package com.example.onlyone.domain.settlement.port;

import com.example.onlyone.domain.settlement.service.SettlementEventProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis Streams 원장 기록 리스너.
 * stream:user-settlement.result를 구독하여 {@link SettlementEventProcessor}에 위임한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.settlement.message-broker", havingValue = "redis-streams")
public class RedisStreamsLedgerListener
        implements StreamListener<String, MapRecord<String, String, String>> {

    private final SettlementEventProcessor processor;
    private final StringRedisTemplate redisTemplate;

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        log.info("Redis Streams 원장 메시지 수신: id={}", message.getId());
        try {
            String payload = message.getValue().get("payload");
            processor.processLedgerPayloads(List.of(payload));

            // XACK
            redisTemplate.opsForStream().acknowledge(
                    message.getStream(), "ledger-writer", message.getId());
        } catch (Exception e) {
            log.error("Redis Streams 원장 메시지 처리 실패: id={}", message.getId(), e);
        }
    }
}
