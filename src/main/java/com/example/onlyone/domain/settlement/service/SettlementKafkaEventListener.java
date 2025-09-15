package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.settlement.dto.event.SettlementProcessEvent;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.service.WalletService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
public class SettlementKafkaEventListener {
    private final ObjectMapper objectMapper;

    // 백프레셔 제어를 위한 세마포어
    private final Semaphore concurrencyLimit;

    private final UserSettlementRepository userSettlementRepository;
    private final UserSettlementService userSettlementService;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final SettlementRepository settlementRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    @PersistenceContext
    private EntityManager entityManager;

    // 생성자에서 세마포어 초기화
    public SettlementKafkaEventListener(
            ObjectMapper objectMapper,
            UserSettlementRepository userSettlementRepository,
            UserSettlementService userSettlementService,
            WalletRepository walletRepository,
            WalletService walletService,
            SettlementRepository settlementRepository,
            ScheduleRepository scheduleRepository,
            UserRepository userRepository,
            @Value("${app.settlement.concurrency:32}") int concurrencyLimit
    ) {
        this.objectMapper = objectMapper;
        this.userSettlementRepository = userSettlementRepository;
        this.userSettlementService = userSettlementService;
        this.walletRepository = walletRepository;
        this.walletService = walletService;
        this.settlementRepository = settlementRepository;
        this.scheduleRepository = scheduleRepository;
        this.userRepository = userRepository;
        this.concurrencyLimit = new Semaphore(concurrencyLimit);
    }

    // settlement.process.v1 토픽 구독
    @KafkaListener(
            groupId = "settlement-orchestrator",
            containerFactory = "settlementProcessKafkaListenerContainerFactory",
            topics = "#{@kafkaProperties.producer.settlementProcessProducerConfig.topic}",
            concurrency = "3"
    )
    public void onSettlementProcess(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        try {
            for (ConsumerRecord<String, String> rec : records) {
                SettlementProcessEvent event = parse(rec.value());
                processSettlementWithStructuredScope(event);
            }
            ack.acknowledge(); // 성공 시 배치 커밋
        } catch (Exception e) {
            throw e;
        }
    }

    private SettlementProcessEvent parse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            return objectMapper.treeToValue(payload, SettlementProcessEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid SettlementProcessEvent: " + json, e);
        }
    }

    /**
     * StructuredTaskScope + Semaphore 조합으로 최고 성능 달성
     * - StructuredTaskScope: Java 21 최적화된 구조화된 동시성
     * - Semaphore: 백프레셔 제어로 DB/Redis 보호
     */
    private void processSettlementWithStructuredScope(SettlementProcessEvent event) {
        try (StructuredTaskScope.ShutdownOnFailure scope =
                     new StructuredTaskScope.ShutdownOnFailure("settlement-parallel", Thread.ofVirtual().factory())) {

            List<Long> targetUserIds = event.getTargetUserIds();
            AtomicLong totalProcessedAmount = new AtomicLong(0);

            log.info("Starting StructuredTaskScope parallel processing for {} participants (concurrency: {})",
                    targetUserIds.size(), concurrencyLimit.availablePermits());

            // 각 참가자별로 가상 스레드 생성 + 세마포어 백프레셔 제어
            for (Long participantId : targetUserIds) {
                scope.fork(() -> {
                    // 세마포어로 동시 실행 수 제한 (백프레셔)
                    concurrencyLimit.acquireUninterruptibly();
                    try {
                        return processParticipantWithRetry(
                                event.getSettlementId(),
                                event.getLeaderId(),
                                event.getLeaderWalletId(),
                                participantId,
                                event.getCostPerUser(),
                                totalProcessedAmount
                        );
                    } finally {
                        concurrencyLimit.release();
                    }
                });
            }

            // StructuredTaskScope의 최적화된 대기/조인
            scope.join();
            scope.throwIfFailed();

            log.info("All participants processed successfully with StructuredTaskScope. Total: {}",
                    totalProcessedAmount.get());

            // 모든 참가자 완료 후 리더 크레딧 및 상태 업데이트
            completeSettlement(event, totalProcessedAmount.get());

        } catch (Exception e) {
            log.error("StructuredTaskScope settlement processing failed for settlementId: {}",
                    event.getSettlementId(), e);
            throw new RuntimeException("Structured parallel settlement processing failed", e);
        }
    }

    private Long processParticipantWithRetry(Long settlementId, Long leaderId, Long leaderWalletId,
                                             Long participantId, Long costPerUser, AtomicLong totalAmount) {
        int maxRetries = 3;
        int retryDelay = 1000;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.debug("Processing participant {} (attempt {}) [Thread: {}]",
                        participantId, attempt, Thread.currentThread().getName());

                // 참가자별 개별 트랜잭션 처리 (REQUIRES_NEW + Redis Lua 게이트는 UserSettlementService 내부)
                userSettlementService.processParticipantSettlement(
                        settlementId,
                        leaderId,
                        leaderWalletId,
                        participantId,
                        costPerUser
                );

                // 처리된 금액을 원자적으로 누적
                totalAmount.addAndGet(costPerUser);

                log.debug("Successfully processed participant {} with amount {} [Thread: {}]",
                        participantId, costPerUser, Thread.currentThread().getName());
                return participantId;

            } catch (Exception e) {
                log.warn("Participant {} processing attempt {} failed [Thread: {}]",
                        participantId, attempt, Thread.currentThread().getName(), e);

                if (attempt == maxRetries) {
                    log.error("Participant {} processing failed after {} attempts", participantId, maxRetries);
                    throw new RuntimeException("Participant settlement failed: " + participantId, e);
                }

                try {
                    Thread.sleep(retryDelay * attempt); // 점진적 백오프
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry for participant: " + participantId, ie);
                }
            }
        }

        return participantId;
    }

    @Transactional
    public void completeSettlement(SettlementProcessEvent event, long totalProcessedAmount) {
        try {
            // 리더에게 크레딧
            log.info("Crediting {} to leader {}", totalProcessedAmount, event.getLeaderId());
            userSettlementService.creditToLeader(event.getLeaderId(), totalProcessedAmount);

            // 스케줄 상태 업데이트
            Schedule completedSchedule = scheduleRepository.findById(event.getScheduleId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
            completedSchedule.updateStatus(ScheduleStatus.CLOSED);
            scheduleRepository.save(completedSchedule);

            // 정산 상태 업데이트
            Settlement completedSettlement = settlementRepository.findById(event.getSettlementId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
            completedSettlement.update(TotalStatus.COMPLETED, LocalDateTime.now());
            settlementRepository.save(completedSettlement);

            log.info("StructuredTaskScope settlement completed successfully for settlementId: {}",
                    event.getSettlementId());

        } catch (Exception e) {
            log.error("Failed to complete settlement for settlementId: {}", event.getSettlementId(), e);
            throw e;
        }
    }
}