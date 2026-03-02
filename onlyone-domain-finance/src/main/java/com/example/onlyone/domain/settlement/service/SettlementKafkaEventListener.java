package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.common.event.SettlementCompletedEvent;
import com.example.onlyone.domain.settlement.event.SettlementProcessEvent;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.global.exception.CustomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class SettlementKafkaEventListener {
    private final ObjectMapper objectMapper;

    private final UserSettlementRepository userSettlementRepository;
    private final UserSettlementService userSettlementService;
    private final SettlementRepository settlementRepository;
    private final WalletRepository walletRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate txTemplate;
    private final OutboxAppender outboxAppender;

    public SettlementKafkaEventListener(
            ObjectMapper objectMapper,
            UserSettlementRepository userSettlementRepository,
            UserSettlementService userSettlementService,
            SettlementRepository settlementRepository,
            WalletRepository walletRepository,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            OutboxAppender outboxAppender
    ) {
        this.objectMapper = objectMapper;
        this.userSettlementRepository = userSettlementRepository;
        this.userSettlementService = userSettlementService;
        this.settlementRepository = settlementRepository;
        this.walletRepository = walletRepository;
        this.eventPublisher = eventPublisher;
        this.outboxAppender = outboxAppender;

        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            groupId = "settlement-orchestrator",
            containerFactory = "settlementProcessKafkaListenerContainerFactory",
            topics = "#{@kafkaProperties.producer.settlementProcessProducerConfig.topic}",
            concurrency = "3"
    )
    public void onSettlementProcess(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        for (ConsumerRecord<String, String> rec : records) {
            SettlementProcessEvent event = parse(rec.value());
            processSettlementBatch(event);
        }
        ack.acknowledge();
    }

    private SettlementProcessEvent parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            return objectMapper.treeToValue(payload, SettlementProcessEvent.class);
        } catch (Exception e) {
            throw new CustomException(FinanceErrorCode.INVALID_EVENT_PAYLOAD);
        }
    }

    /**
     * 배치 정산 처리 — 참가자별 개별 트랜잭션 대신 정산 단위로 배치 처리
     *
     * 기존: 참가자 N명 × (Redis gate + SELECT 3 + UPDATE 2 + INSERT 1) = N개 REQUIRES_NEW 트랜잭션
     * 개선: 1개 트랜잭션에서 batchCaptureHold(IN절) + batchMarkCompleted(IN절) + 배치 Outbox
     *
     * 100건 정산 × 10명 기준: 1,000 tx → 100 tx (10배 감소)
     */
    private void processSettlementBatch(SettlementProcessEvent event) {
        try {
            txTemplate.executeWithoutResult(status -> {
                List<Long> targetUserIds = event.targetUserIds();
                long amount = event.costPerUser();
                Long settlementId = event.settlementId();

                // 1) 배치 captureHold — 한 번의 UPDATE로 전 참가자 지갑 차감
                int captured = walletRepository.batchCaptureHold(targetUserIds, amount);

                if (captured != targetUserIds.size()) {
                    log.error("배치 captureHold 부분 실패: settlementId={}, expected={}, captured={}",
                            settlementId, targetUserIds.size(), captured);
                    // 성공한 건의 captureHold 롤백 (트랜잭션 롤백으로 자동 처리)
                    status.setRollbackOnly();
                    return;
                }

                // 2) 배치 상태 변경 — 한 번의 UPDATE로 전 참가자 COMPLETED
                int marked = userSettlementRepository.batchMarkCompleted(
                        settlementId, targetUserIds, LocalDateTime.now());

                if (marked != targetUserIds.size()) {
                    log.warn("배치 markCompleted 부분 실패: settlementId={}, expected={}, marked={}",
                            settlementId, targetUserIds.size(), marked);
                }

                // 3) 배치 Outbox 기록 — 참가자별 성공 이벤트
                for (Long participantId : targetUserIds) {
                    appendSuccessOutbox(event, participantId);
                }
            });

            // 트랜잭션 성공 시 정산 완료 처리
            completeSettlement(event);
        } catch (Exception e) {
            log.error("배치 정산 처리 실패, 개별 재시도: settlementId={}", event.settlementId(), e);
            // 폴백: 배치 실패 시 개별 병렬 처리로 재시도
            processSettlementParallel(event);
        }
    }

    /**
     * 폴백: 배치 실패 시 참가자별 병렬 처리 (Virtual Threads)
     * 부분 실패 허용 — 실패한 참가자만 기록하고 전체를 FAILED로 복원
     *
     * 기존 순차 처리: 10명 × 2~3s = 20~30s
     * 병렬 처리: max(각 참가자 처리 시간) = 2~3s
     */
    private void processSettlementParallel(SettlementProcessEvent event) {
        List<Long> targetUserIds = event.targetUserIds();
        List<Long> failedParticipants = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Long>> futures = new ArrayList<>(targetUserIds.size());

            for (Long participantId : targetUserIds) {
                futures.add(executor.submit(() -> {
                    txTemplate.executeWithoutResult(status -> {
                        int captured = walletRepository.captureHold(participantId, event.costPerUser());
                        if (captured != 1) {
                            throw new CustomException(FinanceErrorCode.WALLET_HOLD_CAPTURE_FAILED);
                        }
                        userSettlementRepository.batchMarkCompleted(
                                event.settlementId(), List.of(participantId), LocalDateTime.now());
                        appendSuccessOutbox(event, participantId);
                    });
                    return participantId;
                }));
            }

            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Long failedId = targetUserIds.get(i);
                    log.error("개별 정산 실패: settlementId={}, participantId={}",
                            event.settlementId(), failedId, e);
                    failedParticipants.add(failedId);
                }
            }
        }

        if (failedParticipants.isEmpty()) {
            completeSettlement(event);
        } else {
            log.error("정산 부분 실패: settlementId={}, failedParticipants={}",
                    event.settlementId(), failedParticipants);
            revertSettlementToFailed(event.settlementId());
        }
    }

    private void appendSuccessOutbox(SettlementProcessEvent event, Long participantId) {
        Long settlementId = event.settlementId();
        String operationId = ("stl:%d:usr:%d:v1").formatted(settlementId, participantId);
        outboxAppender.append(
                "UserSettlement",
                settlementId * 10000 + participantId,
                "ParticipantSettlementResult",
                String.valueOf(participantId),
                Map.of(
                        "type", "SUCCESS",
                        "operationId", operationId,
                        "occurredAt", java.time.Instant.now().toString(),
                        "settlementId", settlementId,
                        "participantId", participantId,
                        "leaderId", event.leaderId(),
                        "leaderWalletId", event.leaderWalletId(),
                        "amount", event.costPerUser()
                )
        );
    }

    @Transactional
    public void completeSettlement(SettlementProcessEvent event) {
        int updated = settlementRepository.markCompleted(event.settlementId(), LocalDateTime.now());
        if (updated == 0) {
            log.info("Settlement already completed, skipping. id={}", event.settlementId());
            return;
        }

        userSettlementService.creditToLeader(event.leaderId(), event.totalAmount());

        eventPublisher.publishEvent(new SettlementCompletedEvent(
                event.settlementId(), event.scheduleId(), event.clubId(), LocalDateTime.now()));
    }

    private void revertSettlementToFailed(Long settlementId) {
        try {
            txTemplate.executeWithoutResult(status ->
                    settlementRepository.revertToFailed(settlementId));
        } catch (Exception e) {
            log.error("Failed to revert settlement to FAILED. id={}", settlementId, e);
        }
    }
}
