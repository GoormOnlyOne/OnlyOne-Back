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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SettlementProcessEventListener {

    private final UserSettlementRepository userSettlementRepository;
    private final UserSettlementService userSettlementService;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final SettlementRepository settlementRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;

    @PersistenceContext
    private EntityManager em;

    @Async("settlementExecutor") // 가상스레드 기반 처리 (정산 단위 비동기만 유지)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSettlementProcess(SettlementProcessEvent event) {
        log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] 🎯 정산 이벤트 리스너 시작 - settlementId: {}, targetUsers: {}, costPerUser: {}", 
                event.getSettlementId(), event.getTargetUserIds().size(), event.getCostPerUser());
        
        try {
            processSettlement(event);   // 🔹 정산 전체 재시도 제거
            log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] ✅ 정산 이벤트 처리 완료 - settlementId: {}", event.getSettlementId());
        } catch (Exception e) {
            log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] ❌ 정산 이벤트 처리 실패 - settlementId: {}, error: {}", 
                    event.getSettlementId(), e.getMessage(), e);
            // 필요 시 실패 알림/아웃박스
        }
    }

    /**
     * 정산 오케스트레이션
     * - 참가자별 REQUIRES_NEW 트랜잭션 호출
     * - 참가자 단위 재시도(최대 3회)
     * - 1명이라도 실패하면 리더 가산/완료 처리하지 않음
     */
    @Transactional
    public void processSettlement(SettlementProcessEvent event) {
        log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] 🚀 정산 오케스트레이션 시작 - settlementId: {}, 총 참가자: {}, 1인당 비용: {}", 
                event.getSettlementId(), event.getTargetUserIds().size(), event.getCostPerUser());
        
        List<Long> succeeded = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        long totalProcessedAmount = 0;
        int processedCount = 0;

        try {
            for (Long participantId : event.getTargetUserIds()) {
                processedCount++;
                log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] 📋 참가자 처리 시작 ({}/{}) - participantId: {}", 
                        processedCount, event.getTargetUserIds().size(), participantId);
                
                boolean ok = processParticipantWithRetry(
                        event.getSettlementId(),
                        event.getLeaderId(),
                        event.getLeaderWalletId(),
                        participantId,
                        event.getCostPerUser()
                );

                if (ok) {
                    succeeded.add(participantId);
                    totalProcessedAmount += event.getCostPerUser();
                    log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] ✅ 참가자 처리 성공 ({}/{}) - participantId: {}, 누적 금액: {}", 
                            processedCount, event.getTargetUserIds().size(), participantId, totalProcessedAmount);
                } else {
                    failed.add(participantId);
                    log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] ❌ 참가자 처리 실패 ({}/{}) - participantId: {}, 실패 수: {}", 
                            processedCount, event.getTargetUserIds().size(), participantId, failed.size());
                }
            }

            log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] 📊 참가자 처리 완료 - 성공: {}, 실패: {}, 총 금액: {}", 
                    succeeded.size(), failed.size(), totalProcessedAmount);

            if (!failed.isEmpty()) {
                log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] ❌ 일부 참가자 처리 실패 - settlementId: {}, 실패 수: {}, 실패자 ID: {}", 
                        event.getSettlementId(), failed.size(), failed);
                // 실패자 존재 시 → 리더 가산/완료 처리 금지
                throw new RuntimeException("Partial failure in participant settlements");
            }

            // 🔹 전원 성공 시에만 리더 가산
            log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] 💰 리더 크레딧 시작 - leaderId: {}, totalProcessedAmount: {}", 
                    event.getLeaderId(), totalProcessedAmount);
            userSettlementService.creditToLeader(event.getLeaderId(), totalProcessedAmount);
            log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] ✅ 리더 크레딧 완료 - leaderId: {}", event.getLeaderId());

            // 스케줄 CLOSED
            log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] 📅 스케줄 상태 변경 시작 - scheduleId: {}", event.getScheduleId());
            Schedule completedSchedule = scheduleRepository.findById(event.getScheduleId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
            completedSchedule.updateStatus(ScheduleStatus.CLOSED);
            scheduleRepository.save(completedSchedule);
            log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] ✅ 스케줄 상태 변경 완료 - scheduleId: {}", event.getScheduleId());

            // 정산 COMPLETED
            log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] 🏁 정산 상태 변경 시작 - settlementId: {}", event.getSettlementId());
            Settlement completedSettlement = settlementRepository.findById(event.getSettlementId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
            completedSettlement.update(TotalStatus.COMPLETED, LocalDateTime.now());
            settlementRepository.save(completedSettlement);
            log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] ✅ 정산 상태 변경 완료 - settlementId: {}", event.getSettlementId());

            log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] 🎉 정산 오케스트레이션 완료 - settlementId: {}, 성공 사용자: {}, 총 금액: {}", 
                    event.getSettlementId(), succeeded.size(), totalProcessedAmount);

        } catch (Exception e) {
            log.error("[SETTLEMENT_ORCHESTRATION_DEBUG] ❌ 정산 오케스트레이션 실패 - 성공: {}, 실패: {}, 총 금액: {}, error: {}", 
                    succeeded.size(), failed.size(), totalProcessedAmount, e.getMessage(), e);
            throw e; // 상위(비동기 핸들러)에서 로깅/알림
        }
    }

    /**
     * 참가자 단위 재시도 로직 (간단한 선형 backoff)
     * - 내부 호출은 REQUIRES_NEW 트랜잭션
     * - UserSettlementService에서 멱등(이미 COMPLETED이면 스킵) 처리함
     */
    private boolean processParticipantWithRetry(Long settlementId,
                                                Long leaderId,
                                                Long leaderWalletId,
                                                Long participantId,
                                                Long amount) {
        final int maxRetries = 3;
        final int baseDelayMs = 400;

        log.error("[SETTLEMENT_RETRY_DEBUG] 🔄 참가자 재시도 시작 - participantId: {}, maxRetries: {}", 
                participantId, maxRetries);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.error("[SETTLEMENT_RETRY_DEBUG] 🎯 참가자 처리 시도 ({}/{}) - participantId: {}", 
                        attempt, maxRetries, participantId);
                
                userSettlementService.processParticipantSettlement(
                        settlementId, leaderId, leaderWalletId, participantId, amount
                );
                
                log.error("[SETTLEMENT_RETRY_DEBUG] ✅ 참가자 처리 성공 ({}/{}) - participantId: {}", 
                        attempt, maxRetries, participantId);
                return true;
                
            } catch (Exception e) {
                // 마지막 시도 실패면 false
                if (attempt == maxRetries) {
                    log.error("[SETTLEMENT_RETRY_DEBUG] ❌ 참가자 영구 실패 ({}/{}) - settlementId: {}, participantId: {}, error: {}", 
                            attempt, maxRetries, settlementId, participantId, e.getMessage(), e);
                    return false;
                }
                log.warn("[SETTLEMENT_RETRY_DEBUG] ⏳ 참가자 재시도 ({}/{}) - settlementId: {}, participantId: {}, error: {}, 대기시간: {}ms", 
                        attempt, maxRetries, settlementId, participantId, e.getMessage(), (long) baseDelayMs * attempt);
                
                try {
                    Thread.sleep((long) baseDelayMs * attempt);
                    log.error("[SETTLEMENT_RETRY_DEBUG] ⏰ 재시도 대기 완료 - participantId: {}, 다음 시도: {}", 
                            participantId, attempt + 1);
                } catch (InterruptedException ie) {
                    log.error("[SETTLEMENT_RETRY_DEBUG] ❌ 재시도 대기 중 인터럽트 - participantId: {}", participantId);
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
