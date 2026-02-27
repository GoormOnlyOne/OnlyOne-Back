package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.common.event.SettlementCompletedEvent;
import com.example.onlyone.domain.settlement.event.SettlementProcessEvent;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.global.exception.CustomException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;

@Component
@Slf4j
@ConditionalOnProperty(name = "spring.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class SettlementKafkaEventListener {
    private final ObjectMapper objectMapper;

    // 백프레셔 제어를 위한 세마포어
    private final Semaphore concurrencyLimit;

    private final UserSettlementRepository userSettlementRepository;
    private final UserSettlementService userSettlementService;
    private final SettlementRepository settlementRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate txTemplate;

    // 생성자에서 세마포어 초기화
    public SettlementKafkaEventListener(
            ObjectMapper objectMapper,
            UserSettlementRepository userSettlementRepository,
            UserSettlementService userSettlementService,
            SettlementRepository settlementRepository,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager,
            @Value("${app.settlement.concurrency:32}") int concurrencyLimit
    ) {
        this.objectMapper = objectMapper;
        this.userSettlementRepository = userSettlementRepository;
        this.userSettlementService = userSettlementService;
        this.settlementRepository = settlementRepository;
        this.eventPublisher = eventPublisher;
        this.concurrencyLimit = new Semaphore(concurrencyLimit);

        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // settlement.process.v1 토픽 구독
    @KafkaListener(
            groupId = "settlement-orchestrator",
            containerFactory = "settlementProcessKafkaListenerContainerFactory",
            topics = "#{@kafkaProperties.producer.settlementProcessProducerConfig.topic}",
            concurrency = "3"
    )
    public void onSettlementProcess(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        for (ConsumerRecord<String, String> rec : records) {
            SettlementProcessEvent event = parse(rec.value());
            processSettlementWithStructuredScope(event);
        }
        ack.acknowledge(); // 성공 시 배치 커밋
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

    // StructuredTaskScope + Semaphore(백프레셔 제어용)
    // Fix 3: catch에서 revertToFailed 호출
    // Fix 6: ShutdownOnFailure 제거 → 모든 참가자 완료까지 대기, 부분 실패 수집
    private void processSettlementWithStructuredScope(SettlementProcessEvent event) {
        try (var scope = new StructuredTaskScope<Long>("settlement-parallel", Thread.ofVirtual().factory())) {

            List<Long> targetUserIds = event.targetUserIds();
            CopyOnWriteArrayList<Long> failedParticipants = new CopyOnWriteArrayList<>();

            // 각 참가자별로 가상 스레드 생성 + 세마포어 백프레셔 제어
            for (Long participantId : targetUserIds) {
                scope.fork(() -> {
                    // 세마포어로 동시 실행 수 제한
                    try {
                        concurrencyLimit.acquire();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        failedParticipants.add(participantId);
                        return null;
                    }

                    try {
                        processParticipantWithRetry(
                                event.settlementId(),
                                event.leaderId(),
                                event.leaderWalletId(),
                                participantId,
                                event.costPerUser()
                        );
                        return participantId;
                    } catch (Exception e) {
                        log.error("Participant settlement failed after retries. settlementId={}, participantId={}",
                                event.settlementId(), participantId, e);
                        failedParticipants.add(participantId);
                        return null;
                    } finally {
                        concurrencyLimit.release();
                    }
                });
            }
            scope.join();

            // 전원 성공 → 리더 크레딧 + COMPLETED / 실패 있음 → FAILED 복원
            if (failedParticipants.isEmpty()) {
                completeSettlement(event);
            } else {
                log.error("Settlement partially failed. settlementId={}, failedParticipants={}",
                        event.settlementId(), failedParticipants);
                revertSettlementToFailed(event.settlementId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            revertSettlementToFailed(event.settlementId());
            throw new CustomException(FinanceErrorCode.SETTLEMENT_PROCESS_FAILED);
        } catch (Exception e) {
            revertSettlementToFailed(event.settlementId());
            throw new CustomException(FinanceErrorCode.SETTLEMENT_PROCESS_FAILED);
        }
    }

    private void processParticipantWithRetry(Long settlementId, Long leaderId, Long leaderWalletId,
                                             Long participantId, Long costPerUser) {
        int maxRetries = 3;
        int retryDelay = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // 참가자별 개별 트랜잭션 처리
                userSettlementService.processParticipantSettlement(
                        settlementId,
                        leaderId,
                        leaderWalletId,
                        participantId,
                        costPerUser
                );
                return;
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    throw new CustomException(FinanceErrorCode.SETTLEMENT_PROCESS_FAILED);
                }
                try {
                    Thread.sleep(retryDelay * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CustomException(FinanceErrorCode.SETTLEMENT_PROCESS_FAILED);
                }
            }
        }
    }

    // Fix 4: CAS 기반 markCompleted — IN_PROGRESS에서만 COMPLETED로 전이 (멱등)
    @Transactional
    public void completeSettlement(SettlementProcessEvent event) {
        int updated = settlementRepository.markCompleted(event.settlementId(), LocalDateTime.now());
        if (updated == 0) {
            log.info("Settlement already completed, skipping. id={}", event.settlementId());
            return;  // 멱등 스킵
        }

        // 리더에게 전체 금액 크레딧 (markCompleted CAS 성공 시에만 실행 → 이중 지급 방지)
        userSettlementService.creditToLeader(event.leaderId(), event.totalAmount());

        // 스케줄 상태 업데이트 (이벤트 기반)
        eventPublisher.publishEvent(new SettlementCompletedEvent(
                event.settlementId(), event.scheduleId(), event.clubId(), LocalDateTime.now()));
    }

    // Fix 3: Settlement IN_PROGRESS → FAILED 복원 (독립 트랜잭션)
    private void revertSettlementToFailed(Long settlementId) {
        try {
            txTemplate.executeWithoutResult(status ->
                    settlementRepository.revertToFailed(settlementId));
        } catch (Exception e) {
            log.error("Failed to revert settlement to FAILED. id={}", settlementId, e);
        }
    }
}
