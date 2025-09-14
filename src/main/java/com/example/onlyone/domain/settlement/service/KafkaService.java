package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.global.config.kafka.KafkaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Log4j2
@Service
@RequiredArgsConstructor
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
    @Transactional
    public void onUserSettlementResultBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        log.info("📩 KafkaService: Consumed {} records from user-settlement.result.v1", records.size());
        try {
            log.info("onUserSettlementResultBatch 진입");
            ledgerWriter.writeBatch(records);
            // 오프셋 커밋
            ack.acknowledge();
        } catch (Exception e) {
            // Kafka는 "at least once delivery" 모델이 기본 -> throw해서 컨테이너 재시도
            log.error("❌ KafkaService: Error while processing batch", e);
            throw e;
        }
    }
}
