package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.common.event.SettlementCompletedEvent;
import com.example.onlyone.domain.settlement.event.SettlementProcessEvent;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.global.exception.CustomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 정산 이벤트 비즈니스 로직 프로세서.
 * Kafka 리스너에서 수신한 메시지의 비즈니스 로직을 처리한다.
 */
@Slf4j
@Component
public class SettlementEventProcessor {

    private static final long OUTBOX_AGGREGATE_MULTIPLIER = 10_000;

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
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
        List<Long> targetUserIds = event.targetUserIds();
        long amount = event.costPerUser();
        Long settlementId = event.settlementId();

        // 락 순서를 userId 오름차순으로 고정하여 데드락 방지
        List<Long> sortedUserIds = new ArrayList<>(targetUserIds);
        Collections.sort(sortedUserIds);

        try {
            txTemplate.executeWithoutResult(status -> {
                // 1. 배치 captureHold — 한 번의 UPDATE로 모든 참가자 지갑 차감
                int captured = walletRepository.batchCaptureHold(sortedUserIds, amount);
                if (captured != sortedUserIds.size()) {
                    log.error("배치 captureHold 부분 실패: settlementId={}, expected={}, captured={}",
                            settlementId, sortedUserIds.size(), captured);
                    status.setRollbackOnly();
                    return;
                }

                // 2. 배치 markCompleted — 한 번의 UPDATE로 모든 참가자 상태 전이
                userSettlementRepository.batchMarkCompleted(
                        settlementId, sortedUserIds, LocalDateTime.now());

                // 3. 배치 조회 — walletId, userSettlementId를 IN절로 한번에
                Map<Long, Long> walletIdMap = new HashMap<>();
                for (Object[] row : walletRepository.findWalletIdsByUserIds(sortedUserIds)) {
                    walletIdMap.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
                }
                Map<Long, Long> usIdMap = new HashMap<>();
                for (Object[] row : userSettlementRepository
                        .findUserSettlementIdsBySettlementIdAndUserIds(settlementId, sortedUserIds)) {
                    usIdMap.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
                }

                // 4. Outbox 이벤트 — 참가자별 append
                for (Long participantId : sortedUserIds) {
                    appendSuccessOutbox(event, participantId,
                            walletIdMap.getOrDefault(participantId, 0L),
                            usIdMap.getOrDefault(participantId, 0L));
                }
            });
            completeSettlement(event);
        } catch (Exception e) {
            log.error("배치 정산 실패: settlementId={}", settlementId, e);
            revertSettlementToFailed(settlementId);
        }
    }

    private void appendSuccessOutbox(SettlementProcessEvent event, Long participantId,
                                     Long memberWalletId, Long userSettlementId) {
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
                        "occurredAt", Instant.now().toString(),
                        "settlementId", settlementId,
                        "userSettlementId", userSettlementId,
                        "participantId", participantId,
                        "memberWalletId", memberWalletId,
                        "leaderId", event.leaderId(),
                        "leaderWalletId", event.leaderWalletId(),
                        "amount", event.costPerUser()
                )
        );
    }

    private void completeSettlement(SettlementProcessEvent event) {
        txTemplate.executeWithoutResult(status -> {
            int updated = settlementRepository.markCompleted(event.settlementId(), LocalDateTime.now());
            if (updated == 0) {
                log.info("Settlement already completed, skipping. id={}", event.settlementId());
                return;
            }

            userSettlementService.creditToLeader(event.leaderId(), event.totalAmount());

            eventPublisher.publishEvent(new SettlementCompletedEvent(
                    event.settlementId(), event.scheduleId(), event.clubId(), LocalDateTime.now()));
        });
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
