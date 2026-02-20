package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.entity.OutboxStatus;
import com.example.onlyone.domain.settlement.repository.OutboxRepository;
import com.example.onlyone.domain.settlement.config.kafka.KafkaProperties;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true")
public class OutboxRelayService {

    private static final int MAX_RETRY_COUNT = 5;

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
            // Kafka 전송 성공 후 같은 콜백 내에서 status 갱신
            // → Kafka tx 실패 시 status 변경도 함께 무효화
            LocalDateTime now = LocalDateTime.now();
            batch.forEach(e -> {
                e.setStatus(OutboxStatus.PUBLISHED);
                e.setPublishedAt(now);
            });
            return null;
        });
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void retryFailedMessages() {
        var failedBatch = outboxRepository.findFailedForRetry(MAX_RETRY_COUNT, 100);
        if (failedBatch.isEmpty()) return;

        log.info("Retrying {} failed outbox messages", failedBatch.size());

        for (var event : failedBatch) {
            try {
                String topic = routeTopic(event.getEventType());
                kafkaTemplate.send(topic, event.getKeyString(), event.getPayload()).get();
                event.setStatus(OutboxStatus.PUBLISHED);
                event.setPublishedAt(LocalDateTime.now());
                log.info("Successfully retried outbox event id={}", event.getId());
            } catch (Exception e) {
                event.setRetryCount(event.getRetryCount() + 1);
                if (event.getRetryCount() >= MAX_RETRY_COUNT) {
                    event.setStatus(OutboxStatus.DEAD);
                    log.error("Outbox event id={} exceeded max retries, marked as DEAD", event.getId());
                } else {
                    log.warn("Retry failed for outbox event id={}, retryCount={}", event.getId(), event.getRetryCount());
                }
            }
        }
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupOldEvents() {
        LocalDateTime publishedCutoff = LocalDateTime.now().minusDays(7);
        LocalDateTime deadCutoff = LocalDateTime.now().minusDays(30);

        int deletedPublished = outboxRepository.deletePublishedBefore(publishedCutoff);
        int deletedDead = outboxRepository.deleteDeadBefore(deadCutoff);

        if (deletedPublished > 0 || deletedDead > 0) {
            log.info("Outbox cleanup: deleted {} PUBLISHED (>7d), {} DEAD (>30d)",
                    deletedPublished, deletedDead);
        }
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
