package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.config.kafka.KafkaProperties;
import com.example.onlyone.domain.settlement.entity.OutboxStatus;
import com.example.onlyone.domain.settlement.event.OutboxEvent;
import com.example.onlyone.domain.settlement.repository.OutboxRepository;
import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 릴레이 서비스.
 * 주기적으로 outbox_event 테이블에서 NEW 상태의 이벤트를 조회하여 Kafka로 발행한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true")
public class OutboxRelayService {

    private static final int MAX_RETRY_COUNT = 5;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaProperties kafkaProperties;

    @Scheduled(fixedDelay = 50)
    @Transactional
    public void publishBatch() {
        List<OutboxEvent> batch = outboxRepository.pickNewForUpdateSkipLocked(500);
        if (batch.isEmpty()) return;

        kafkaTemplate.executeInTransaction(ops -> {
            batch.forEach(e -> {
                String topic = routeTopic(e.getEventType());
                ops.send(topic, e.getKeyString(), e.getPayload());
            });
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
        List<OutboxEvent> failedBatch = outboxRepository.findFailedForRetry(MAX_RETRY_COUNT, 100);
        if (failedBatch.isEmpty()) return;

        log.info("Retrying {} failed outbox messages", failedBatch.size());

        for (OutboxEvent event : failedBatch) {
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
            case "ParticipantSettlementResult" -> kafkaProperties.getConsumer()
                    .getUserSettlementLedgerConsumerConfig().getTopic();
            case "SettlementProcessEvent" -> kafkaProperties.getProducer()
                    .getSettlementProcessProducerConfig().getTopic();
            default -> throw new CustomException(FinanceErrorCode.INVALID_TOPIC);
        };
    }
}
