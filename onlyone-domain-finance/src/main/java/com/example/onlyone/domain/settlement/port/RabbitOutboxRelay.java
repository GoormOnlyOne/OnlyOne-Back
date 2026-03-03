package com.example.onlyone.domain.settlement.port;

import com.example.onlyone.domain.settlement.config.rabbit.RabbitSettlementConfig;
import com.example.onlyone.domain.settlement.entity.OutboxStatus;
import com.example.onlyone.domain.settlement.event.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RabbitMQ 기반 Outbox 메시지 릴레이.
 * {@code app.settlement.message-broker=rabbitmq} 일 때 활성화된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.settlement.message-broker", havingValue = "rabbitmq")
public class RabbitOutboxRelay implements OutboxMessageRelay {

    private final RabbitTemplate settlementRabbitTemplate;

    @Override
    public void publishBatch(List<OutboxEvent> events) {
        LocalDateTime now = LocalDateTime.now();
        for (OutboxEvent event : events) {
            String routingKey = routeKey(event.getEventType());
            settlementRabbitTemplate.convertAndSend(
                    RabbitSettlementConfig.EXCHANGE, routingKey, event.getPayload());
            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(now);
        }
    }

    @Override
    public void publishSingle(OutboxEvent event) {
        String routingKey = routeKey(event.getEventType());
        settlementRabbitTemplate.convertAndSend(
                RabbitSettlementConfig.EXCHANGE, routingKey, event.getPayload());
    }

    @Override
    public String brokerName() {
        return "rabbitmq";
    }

    private String routeKey(String eventType) {
        return switch (eventType) {
            case "SettlementProcessEvent" -> RabbitSettlementConfig.SETTLEMENT_PROCESS_RK;
            case "ParticipantSettlementResult" -> RabbitSettlementConfig.USER_SETTLEMENT_RESULT_RK;
            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }
}
