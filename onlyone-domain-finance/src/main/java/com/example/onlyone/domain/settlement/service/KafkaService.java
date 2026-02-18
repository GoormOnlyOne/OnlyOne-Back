package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.config.kafka.KafkaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaService {

    private final LedgerWriter ledgerWriter;
    private final KafkaProperties props;

    // KafkaListener: Kafka 메시지를 받는 entry point
    // 메시지는 List<ConsumerRecord<String, String>> 배치(batch) 형태
    // user-settlement.result.v1 구독
    @KafkaListener(
            groupId = "ledger-writer",
            containerFactory = "userSettlementLedgerKafkaListenerContainerFactory",
            topics = "#{@kafkaProperties.consumer.userSettlementLedgerConsumerConfig.topic}",
            concurrency = "8"
    )
    public void onUserSettlementResultBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        log.info("Kafka 메시지 수신: topic=user-settlement-result, count={}", records.size());
        try {
            ledgerWriter.writeBatch(records);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Kafka 메시지 처리 실패: count={}", records.size(), e);
            throw e;
        }
    }
}
