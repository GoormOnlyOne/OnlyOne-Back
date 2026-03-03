package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.entity.OutboxStatus;
import com.example.onlyone.domain.settlement.event.OutboxEvent;
import com.example.onlyone.domain.settlement.port.OutboxMessageRelay;
import com.example.onlyone.domain.settlement.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 릴레이 서비스.
 * 주기적으로 outbox_event 테이블에서 NEW 상태의 이벤트를 조회하여
 * {@link OutboxMessageRelay} 포트를 통해 메시지 브로커로 발행한다.
 *
 * <p>기존 Kafka 직접 의존이 {@link OutboxMessageRelay} 포트로 분리되어
 * Kafka/RabbitMQ/Redis Streams 중 프로퍼티 하나로 교체 가능하다.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelayService {

    private static final int MAX_RETRY_COUNT = 5;

    private final OutboxRepository outboxRepository;
    private final OutboxMessageRelay outboxMessageRelay;

    @Scheduled(fixedDelay = 200)
    @Transactional
    public void publishBatch() {
        List<OutboxEvent> batch = outboxRepository.pickNewForUpdateSkipLocked(200);
        if (batch.isEmpty()) return;
        outboxMessageRelay.publishBatch(batch);
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void retryFailedMessages() {
        List<OutboxEvent> failedBatch = outboxRepository.findFailedForRetry(MAX_RETRY_COUNT, 100);
        if (failedBatch.isEmpty()) return;

        log.info("[{}] Retrying {} failed outbox messages", outboxMessageRelay.brokerName(), failedBatch.size());

        for (OutboxEvent event : failedBatch) {
            try {
                outboxMessageRelay.publishSingle(event);
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
}
