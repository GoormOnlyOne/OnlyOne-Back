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

/**
 * 정산 이벤트 비즈니스 로직 프로세서.
 * Kafka 리스너에서 수신한 메시지의 비즈니스 로직을 처리한다.
 */
@Slf4j
@Component
public class SettlementEventProcessor {

    private static final long OUTBOX_AGGREGATE_MULTIPLIER = 10_000;
    private static final int FUTURE_TIMEOUT_SECONDS = 10;

    private final ObjectMapper objectMapper;
    private final UserSettlementRepository userSettlementRepository;
    private final UserSettlementService userSettlementService;
    private final SettlementRepository settlementRepository;
    private final WalletRepository walletRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate txTemplate;
    private final OutboxAppender outboxAppender;
    private final LedgerWriter ledgerWriter;

    public SettlementEventProcessor(
            ObjectMapper objectMapper,
            UserSettlementRepository userSettlementRepository,
            UserSettlementService userSettlementService,
            SettlementRepository settlementRepository,
            WalletRepository walletRepository,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            OutboxAppender outboxAppender,
            LedgerWriter ledgerWriter
    ) {
        this.objectMapper = objectMapper;
        this.userSettlementRepository = userSettlementRepository;
        this.userSettlementService = userSettlementService;
        this.settlementRepository = settlementRepository;
        this.walletRepository = walletRepository;
        this.eventPublisher = eventPublisher;
        this.outboxAppender = outboxAppender;
        this.ledgerWriter = ledgerWriter;

        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ========== 정산 처리 (settlement.process.v1) ==========

    /**
     * 정산 이벤트 payload 문자열 리스트를 처리한다.
     */
    public void processSettlementEvents(List<String> payloads) {
        for (String payload : payloads) {
            SettlementProcessEvent event = parseSettlementEvent(payload);
            processSettlementBatch(event);
        }
    }

    /**
     * 원장 기록 이벤트 처리.
     */
    public void processLedgerEvents(List<ConsumerRecord<String, String>> records) {
        ledgerWriter.writeBatch(records);
    }

    // ========== 배치 정산 처리 ==========

    private void processSettlementBatch(SettlementProcessEvent event) {
        try {
            txTemplate.executeWithoutResult(status -> {
                List<Long> targetUserIds = event.targetUserIds();
                long amount = event.costPerUser();
                Long settlementId = event.settlementId();

                int captured = walletRepository.batchCaptureHold(targetUserIds, amount);

                if (captured != targetUserIds.size()) {
                    log.error("배치 captureHold 부분 실패: settlementId={}, expected={}, captured={}",
                            settlementId, targetUserIds.size(), captured);
                    throw new CustomException(FinanceErrorCode.WALLET_HOLD_CAPTURE_FAILED);
                }

                int marked = userSettlementRepository.batchMarkCompleted(
                        settlementId, targetUserIds, LocalDateTime.now());

                if (marked != targetUserIds.size()) {
                    log.warn("배치 markCompleted 부분 실패: settlementId={}, expected={}, marked={}",
                            settlementId, targetUserIds.size(), marked);
                }

                for (Long participantId : targetUserIds) {
                    appendSuccessOutbox(event, participantId);
                }
            });

            completeSettlement(event);
        } catch (Exception e) {
            log.error("배치 정산 처리 실패, 개별 재시도: settlementId={}", event.settlementId(), e);
            processSettlementParallel(event);
        }
    }

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
                    futures.get(i).get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
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
            List<Long> succeededParticipants = new ArrayList<>(targetUserIds);
            succeededParticipants.removeAll(failedParticipants);
            log.error("정산 부분 실패: settlementId={}, failed={}, succeeded={} — 성공 건 수동 환불 필요",
                    event.settlementId(), failedParticipants, succeededParticipants);
            revertSettlementToFailed(event.settlementId());
        }
    }

    private void appendSuccessOutbox(SettlementProcessEvent event, Long participantId) {
        Long settlementId = event.settlementId();
        String operationId = ("stl:%d:usr:%d:v1").formatted(settlementId, participantId);
        outboxAppender.append(
                "UserSettlement",
                settlementId * OUTBOX_AGGREGATE_MULTIPLIER + participantId,
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

    // ========== Parsing ==========

    public SettlementProcessEvent parseSettlementEvent(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            return objectMapper.treeToValue(payload, SettlementProcessEvent.class);
        } catch (Exception e) {
            throw new CustomException(FinanceErrorCode.INVALID_EVENT_PAYLOAD);
        }
    }
}
