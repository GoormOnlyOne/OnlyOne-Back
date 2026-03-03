package com.example.onlyone.domain.settlement.port;

import com.example.onlyone.domain.settlement.config.kafka.KafkaProperties;
import com.example.onlyone.domain.settlement.entity.OutboxStatus;
import com.example.onlyone.domain.settlement.event.OutboxEvent;
import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Kafka 기반 Outbox 메시지 릴레이.
 * {@code app.settlement.message-broker=kafka} (기본값) 일 때 활성화된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.settlement.message-broker", havingValue = "kafka", matchIfMissing = true)
public class KafkaOutboxRelay implements OutboxMessageRelay {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaProperties props;

    @Override
    public void publishBatch(List<OutboxEvent> events) {
        kafkaTemplate.executeInTransaction(kafkaOperation -> {
            events.forEach(e -> {
                String topic = routeTopic(e.getEventType());
                kafkaOperation.send(topic, e.getKeyString(), e.getPayload());
            });
            LocalDateTime now = LocalDateTime.now();
            events.forEach(e -> {
                e.setStatus(OutboxStatus.PUBLISHED);
                e.setPublishedAt(now);
            });
            return null;
        });
    }

    @Override
    public void publishSingle(OutboxEvent event) {
        try {
            String topic = routeTopic(event.getEventType());
            kafkaTemplate.send(topic, event.getKeyString(), event.getPayload()).get();
        } catch (Exception e) {
            throw new RuntimeException("Kafka publish failed for event id=" + event.getId(), e);
        }
    }

    @Override
    public String brokerName() {
        return "kafka";
    }

    private String routeTopic(String eventType) {
        return switch (eventType) {
            case "ParticipantSettlementResult" -> props.getConsumer()
                    .getUserSettlementLedgerConsumerConfig().getTopic();
            case "SettlementProcessEvent" -> props.getProducer()
                    .getSettlementProcessProducerConfig().getTopic();
            default -> throw new CustomException(FinanceErrorCode.INVALID_TOPIC);
        };
    }
}
