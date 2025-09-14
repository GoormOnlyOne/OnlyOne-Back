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
        try {
            processSettlement(event);   // 🔹 정산 전체 재시도 제거
        } catch (Exception e) {
            log.error("❌ Settlement handle failed: settlementId={}", event.getSettlementId(), e);
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
        List<Long> succeeded = new ArrayList<>();
        List<Long> failed = new ArrayList<>();
        long totalProcessedAmount = 0;

        try {
            for (Long participantId : event.getTargetUserIds()) {
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
                } else {
                    failed.add(participantId);
                }
            }

            if (!failed.isEmpty()) {
                log.warn("⚠️ Some participants failed. settlementId={}, failedCount={}, failedIds={}",
                        event.getSettlementId(), failed.size(), failed);
                // 실패자 존재 시 → 리더 가산/완료 처리 금지
                throw new RuntimeException("Partial failure in participant settlements");
            }

            // 🔹 전원 성공 시에만 리더 가산
            userSettlementService.creditToLeader(event.getLeaderId(), totalProcessedAmount);

            // 스케줄 CLOSED
            Schedule completedSchedule = scheduleRepository.findById(event.getScheduleId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
            completedSchedule.updateStatus(ScheduleStatus.CLOSED);
            scheduleRepository.save(completedSchedule);

            // 정산 COMPLETED
            Settlement completedSettlement = settlementRepository.findById(event.getSettlementId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
            completedSettlement.update(TotalStatus.COMPLETED, LocalDateTime.now());
            settlementRepository.save(completedSettlement);

            log.info("✅ Settlement COMPLETED: settlementId={}, users={}, totalAmount={}",
                    event.getSettlementId(), succeeded.size(), totalProcessedAmount);

        } catch (Exception e) {
            log.error("❌ Settlement failed. succeeded={}, totalProcessedAmount={}",
                    succeeded.size(), totalProcessedAmount, e);
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

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                userSettlementService.processParticipantSettlement(
                        settlementId, leaderId, leaderWalletId, participantId, amount
                );
                return true;
            } catch (Exception e) {
                // 마지막 시도 실패면 false
                if (attempt == maxRetries) {
                    log.error("❌ Participant permanently failed: settlementId={}, participantId={}, err={}",
                            settlementId, participantId, e.getMessage());
                    return false;
                }
                log.warn("⏳ Participant retry {}/{}: settlementId={}, participantId={}, err={}",
                        attempt, maxRetries, settlementId, participantId, e.getMessage());
                try {
                    Thread.sleep((long) baseDelayMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
