package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.schedule.repository.UserScheduleRepository;
import com.example.onlyone.domain.settlement.dto.event.SettlementProcessEvent;
import com.example.onlyone.domain.settlement.dto.response.SettlementResponseDto;
import com.example.onlyone.domain.settlement.dto.response.UserSettlementDto;
import com.example.onlyone.domain.settlement.entity.*;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.TransferRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.entity.*;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.repository.WalletTransactionRepository;
import com.example.onlyone.domain.wallet.service.WalletService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class SettlementService {
    private final UserService userService;
    private final ClubRepository clubRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserScheduleRepository userScheduleRepository;
    private final SettlementRepository settlementRepository;
    private final UserSettlementRepository userSettlementRepository;
    private final WalletRepository walletRepository;
    private final NotificationService notificationService;
    private final WalletService walletService;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxAppender outboxAppender;

    @Transactional(rollbackFor = Exception.class)
    public void automaticSettlement(Long clubId, Long scheduleId) {
        // 현재 사용자 조회
        User user = userService.getCurrentUser();
        
        // 클럽 존재 여부만 확인 (실제 엔티티는 사용하지 않음)
        if (!clubRepository.existsById(clubId)) {
            throw new CustomException(ErrorCode.CLUB_NOT_FOUND);
        }
        
        // 스케줄 조회
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        
        // 종료된 스케줄인지 확인
        if (!(schedule.getScheduleStatus() == ScheduleStatus.ENDED
                || schedule.getScheduleTime().isBefore(LocalDateTime.now()))) {
            throw new CustomException(ErrorCode.BEFORE_SCHEDULE_END);
        }
        
        // 정산 조회
        Settlement settlement = settlementRepository.findBySchedule(schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
        
        if(settlement.getTotalStatus() == TotalStatus.COMPLETED || schedule.getScheduleStatus() == ScheduleStatus.CLOSED) {
            throw new CustomException(ErrorCode.ALREADY_COMPLETED_SETTLEMENT);
        }
        
        // 선점 트랜잭션 처리: HOLDING|FAILED → IN_PROGRESS (먼저 처리하여 동시성 제어)
        int updated = settlementRepository.markProcessing(settlement.getSettlementId());
        if (updated != 1) {
            throw new CustomException(ErrorCode.ALREADY_SETTLING_SCHEDULE);
        }

        // 참가자 ID 목록과 수를 한 번에 조회 (성능 개선)
        List<Long> targetUserIds =
                userSettlementRepository.findAllUserSettlementIdsBySettlementIdAndStatus(
                        settlement.getSettlementId(), SettlementStatus.HOLD_ACTIVE);
        
        long userCount = targetUserIds.size();
        
        // 가격이 0원이거나 참여자가 리더 1명인 경우 → 스케줄 종료 처리
        if (schedule.getCost() == 0 || userCount == 0) {
            schedule.updateStatus(ScheduleStatus.CLOSED);
            schedule.removeSettlement(settlement);
            return;
        }

        // 총 금액 계산 (리더 제외)
        long totalAmount = userCount * schedule.getCost();
        settlement.updateSum(totalAmount);
        settlementRepository.save(settlement);

        // 스케줄 저장
        scheduleRepository.save(schedule);

        // 리더 지갑 조회
        Wallet leaderWallet = walletRepository.findByUserWithoutLock(user)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        // 정산 프로세스 이벤트 발행
        outboxAppender.append(
                "Settlement",
                settlement.getSettlementId(),
                "SettlementProcessEvent",
                String.valueOf(settlement.getSettlementId()),
                Map.of(
                        "eventId", java.util.UUID.randomUUID().toString(),
                        "occurredAt", java.time.Instant.now().toString(),
                        "settlementId", settlement.getSettlementId(),
                        "scheduleId", scheduleId,
                        "clubId", clubId,
                        "leaderId", user.getUserId(),
                        "leaderWalletId", leaderWallet.getWalletId(),
                        "costPerUser", schedule.getCost(),
                        "totalAmount", totalAmount,
                        "targetUserIds", targetUserIds
                )
        );
    }

    /* 스케줄 참여자 정산 목록 조회 */
    @Transactional(readOnly = true)
    public SettlementResponseDto getSettlementList(Long clubId, Long scheduleId, Pageable pageable) {
        clubRepository.findById(clubId)
                .orElseThrow(() -> new CustomException(ErrorCode.CLUB_NOT_FOUND));
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SCHEDULE_NOT_FOUND));
        Settlement settlement = settlementRepository.findBySchedule(schedule)
                .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
        Page<UserSettlementDto> userSettlementList = userSettlementRepository
                .findAllDtoBySettlement(settlement, pageable);
        return SettlementResponseDto.from(userSettlementList);
    }
}
