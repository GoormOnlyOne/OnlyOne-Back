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
import java.util.concurrent.StructuredTaskScope;

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

    @Async("settlementExecutor") // 별도 스레드풀
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSettlementProcess(SettlementProcessEvent event) {
        try {
            processSettlementWithRetry(event);
        } catch (Exception e) {
            // 실패 로그 기록 처리
        }
    }

    private void processSettlementWithRetry(SettlementProcessEvent event) {
        int maxRetries = 3;
        int retryDelay = 1000;

        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            scope.fork(() -> {
                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                    try {
                        processSettlement(event);
                        return null; // 성공 시
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

    public void processSettlement(SettlementProcessEvent event) {
        List<Long> processedParticipants = new ArrayList<>();
        long totalProcessedAmount = 0;

        try {
            // 참가자별 개별 트랜잭션으로 처리
            for (Long participantId : event.getTargetUserIds()) {
                userSettlementService.processParticipantSettlement(event.getSettlementId(), event.getLeaderWalletId(), participantId, event.getCostPerUser());
                processedParticipants.add(participantId);
                totalProcessedAmount += event.getCostPerUser();
            }
            // 모든 참가자 처리 완료 후 리더에게 가산
            userSettlementService.creditToLeader(event.getLeaderId(), totalProcessedAmount);
            // 모두 성공한 경우
            Schedule completedSchedule = scheduleRepository.findById(event.getScheduleId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
            Settlement completedSettlement = settlementRepository.findById(event.getSettlementId())
                    .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
            completedSettlement.update(TotalStatus.COMPLETED, LocalDateTime.now());
            completedSchedule.updateStatus(ScheduleStatus.CLOSED);
            settlementRepository.save(completedSettlement);
            scheduleRepository.save(completedSchedule);
//            notificationService.createNotification(
//                    user,
//                    Type.SETTLEMENT,
//                    new String[]{String.valueOf(settlement.getSum())});
        } catch (Exception e) {
            log.error("Settlement failed. Processed participants: {}, Total amount: {}",
                    processedParticipants, totalProcessedAmount, e);
            throw e;
        }
    }
}
