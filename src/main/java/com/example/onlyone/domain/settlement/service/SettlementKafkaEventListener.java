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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;

@Component
@RequiredArgsConstructor
@Slf4j
public class SettlementKafkaEventListener {
    private final ObjectMapper objectMapper;

    private final UserSettlementRepository userSettlementRepository;
    private final UserSettlementService userSettlementService;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final SettlementRepository settlementRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    @PersistenceContext
    private EntityManager entityManager;

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
                processSettlementWithRetry(event);
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

    private void processSettlementWithRetry(SettlementProcessEvent event) {
        int maxRetries = 3;
        int retryDelay = 1000;

        try (StructuredTaskScope.ShutdownOnFailure scope =
                     new StructuredTaskScope.ShutdownOnFailure("settlement", Thread.ofVirtual().factory())) {
            scope.fork(() -> {
                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                    try {
                        processSettlement(event);
                        return null; // 성공
                    } catch (Exception e) {
                        log.warn("Settlement attempt {} failed for settlementId: {}", attempt, event.getSettlementId(), e);
                        if (attempt == maxRetries) throw e;
                        try {
                            Thread.sleep(retryDelay * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted during retry", ie);
                        }
                    }
                }
                return null;
            });

            scope.join();
            scope.throwIfFailed();
        } catch (Exception e) {
            throw new RuntimeException("Settlement processing failed after retries", e);
        }
    }

    @Transactional
    public void processSettlement(SettlementProcessEvent event) {
        List<Long> processedParticipants = new ArrayList<>();
        long totalProcessedAmount = 0;

        try {
            // 참가자별 개별 트랜잭션 처리 (REQUIRES_NEW + Redis Lua 게이트는 UserSettlementService 내부)
            for (Long participantId : event.getTargetUserIds()) {
                userSettlementService.processParticipantSettlement(
                        event.getSettlementId(),
                        event.getLeaderId(),
                        event.getLeaderWalletId(),
                        participantId,
                        event.getCostPerUser()
                );
                processedParticipants.add(participantId);
                totalProcessedAmount += event.getCostPerUser();
            }

            // 모든 참가자 완료 후 리더 크레딧
            userSettlementService.creditToLeader(event.getLeaderId(), totalProcessedAmount);

            // 스케줄/정산 상태 마무리
            Schedule completedSchedule = scheduleRepository.findById(event.getScheduleId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
            completedSchedule.updateStatus(ScheduleStatus.CLOSED);
            scheduleRepository.save(completedSchedule);

            Settlement completedSettlement = settlementRepository.findById(event.getSettlementId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
            completedSettlement.update(TotalStatus.COMPLETED, LocalDateTime.now());
            settlementRepository.save(completedSettlement);

        } catch (Exception e) {
            log.error("Settlement failed. Processed participants: {}, Total amount: {}",
                    processedParticipants, totalProcessedAmount, e);
            throw e;
        }
    }
}
