package com.example.onlyone.domain.settlement.config.redis;

import com.example.onlyone.domain.settlement.port.RedisStreamsLedgerListener;
import com.example.onlyone.domain.settlement.port.RedisStreamsSettlementListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;

/**
 * Redis Streams 정산 도메인 설정.
 * {@code app.settlement.message-broker=redis-streams} 일 때 활성화된다.
 *
 * <p>Stream keys: stream:settlement.process, stream:user-settlement.result
 * <p>Consumer Groups: settlement-orchestrator, ledger-writer
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.settlement.message-broker", havingValue = "redis-streams")
public class RedisStreamsSettlementConfig {

    public static final String SETTLEMENT_PROCESS_STREAM = "stream:settlement.process";
    public static final String USER_SETTLEMENT_RESULT_STREAM = "stream:user-settlement.result";
    public static final String SETTLEMENT_GROUP = "settlement-orchestrator";
    public static final String LEDGER_GROUP = "ledger-writer";

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> settlementStreamListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisStreamsSettlementListener settlementListener) {

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofMillis(100))
                .batchSize(50)
                .build();

        var container = StreamMessageListenerContainer.create(connectionFactory, options);

        // Consumer Group 생성 (이미 존재하면 무시)
        ensureConsumerGroup(connectionFactory, SETTLEMENT_PROCESS_STREAM, SETTLEMENT_GROUP);

        container.receive(
                Consumer.from(SETTLEMENT_GROUP, "settlement-consumer-1"),
                StreamOffset.create(SETTLEMENT_PROCESS_STREAM, ReadOffset.lastConsumed()),
                settlementListener
        );

        container.start();
        return container;
    }

    @Bean
    public StreamMessageListenerContainer<String, MapRecord<String, String, String>> ledgerStreamListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisStreamsLedgerListener ledgerListener) {

        var options = StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                .builder()
                .pollTimeout(Duration.ofMillis(100))
                .batchSize(50)
                .build();

        var container = StreamMessageListenerContainer.create(connectionFactory, options);

        ensureConsumerGroup(connectionFactory, USER_SETTLEMENT_RESULT_STREAM, LEDGER_GROUP);

        container.receive(
                Consumer.from(LEDGER_GROUP, "ledger-consumer-1"),
                StreamOffset.create(USER_SETTLEMENT_RESULT_STREAM, ReadOffset.lastConsumed()),
                ledgerListener
        );

        container.start();
        return container;
    }

    private void ensureConsumerGroup(RedisConnectionFactory connectionFactory,
                                      String streamKey, String groupName) {
        try {
            StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
            // 스트림이 없으면 MKSTREAM으로 자동 생성
            template.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);
            log.info("Created consumer group '{}' on stream '{}'", groupName, streamKey);
        } catch (Exception e) {
            // BUSYGROUP: 이미 존재
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group '{}' already exists on stream '{}'", groupName, streamKey);
            } else {
                log.warn("Failed to create consumer group '{}' on stream '{}': {}",
                        groupName, streamKey, e.getMessage());
            }
        }
    }
}
