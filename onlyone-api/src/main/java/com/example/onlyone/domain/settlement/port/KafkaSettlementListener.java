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
 * Kafka 정산 처리 리스너.
 * settlement.process.v1 토픽을 구독하여 {@link SettlementEventProcessor}에 위임한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true")
public class KafkaSettlementListener {

    private final SettlementEventProcessor processor;

    @KafkaListener(
            groupId = "settlement-orchestrator",
            containerFactory = "settlementProcessKafkaListenerContainerFactory",
            topics = "#{@kafkaProperties.producer.settlementProcessProducerConfig.topic}",
            concurrency = "12"
    )
    public void onSettlementProcess(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        log.info("Kafka 정산 메시지 수신: count={}", records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                processor.processSettlementEvents(List.of(record.value()));
            } catch (Exception e) {
                log.error("정산 레코드 처리 실패 (FAILED 전환됨): key={}, partition={}, offset={}",
                        record.key(), record.partition(), record.offset(), e);
            }
        }
        ack.acknowledge();
    }
}
