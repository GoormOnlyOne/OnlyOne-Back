package com.example.onlyone.domain.settlement.port;

import com.example.onlyone.domain.settlement.service.SettlementEventProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Kafka 원장 기록 리스너.
 * user-settlement.result.v1 토픽을 구독하여 {@link SettlementEventProcessor}에 위임한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.settlement.message-broker", havingValue = "kafka", matchIfMissing = true)
public class KafkaLedgerListener {

    private final SettlementEventProcessor processor;

    @KafkaListener(
            groupId = "ledger-writer",
            containerFactory = "userSettlementLedgerKafkaListenerContainerFactory",
            topics = "#{@kafkaProperties.consumer.userSettlementLedgerConsumerConfig.topic}",
            concurrency = "8"
    )
    public void onUserSettlementResultBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        log.info("Kafka 원장 메시지 수신: count={}", records.size());
        try {
            processor.processLedgerEvents(records);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Kafka 원장 메시지 처리 실패: count={}", records.size(), e);
            throw e;
        }
    }
}
