package com.example.onlyone.global.config.kafka;


import com.example.onlyone.domain.settlement.entity.OutboxStatus;
import com.example.onlyone.domain.settlement.repository.OutboxRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
@Component
@RequiredArgsConstructor
public class OutboxRelayConfig {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaProperties props;

    @Scheduled(fixedDelay = 200)
    @Transactional
    public void publishBatch() {
        var batch = outboxRepository.pickNewForUpdateSkipLocked(200);
        if (batch.isEmpty()) return;
        kafkaTemplate.executeInTransaction(kafkaOperation -> {
            batch.forEach(e -> {
                String topic = routeTopic(e.getEventType());
                kafkaOperation.send(topic, e.getKeyString(), e.getPayload());
            });
            return null;
        });

        batch.forEach(e -> {
            e.setStatus(OutboxStatus.PUBLISHED);
            e.setPublishedAt(LocalDateTime.now());
        });
    }

    private String routeTopic(String eventType) {
        return switch (eventType) {
            case "ParticipantSettlementResult" -> props.getConsumer()
                    .getUserSettlementLedgerConsumerConfig().getTopic();
            case "SettlementProcessEvent" -> props.getProducer()
                    .getSettlementProcessProducerConfig().getTopic();
            default -> throw new CustomException(ErrorCode.INVALID_TOPIC);
        };
    }
}
