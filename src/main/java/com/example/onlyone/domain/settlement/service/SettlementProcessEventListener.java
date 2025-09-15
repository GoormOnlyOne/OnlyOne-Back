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

    @Async("settlementExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSettlementProcess(SettlementProcessEvent event) {
        try {
            processSettlement(event);
        } catch (Exception e) {
            // 필요 시 실패 알림/아웃박스
        }
    }

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
                // 실패자 존재 시 → 리더 가산/완료 처리 금지
                throw new CustomException(ErrorCode.SETTLEMENT_PROCESS_FAILED);
            }

            // 전원 성공 시에만 리더 가산
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

        } catch (Exception e) {
            throw e; // 상위(비동기 핸들러)에서 로깅/알림
        }
    }

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
                    return false;
                }
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
