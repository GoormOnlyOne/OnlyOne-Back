package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.common.event.SettlementCompletedEvent;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.settlement.dto.response.SettlementResponseDto;
import com.example.onlyone.domain.settlement.dto.response.UserSettlementDto;
import com.example.onlyone.domain.settlement.entity.*;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.entity.*;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final SettlementRepository settlementRepository;
    private final UserSettlementRepository userSettlementRepository;
    private final WalletRepository walletRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxAppender outboxAppender;

    @Transactional(rollbackFor = Exception.class)
    public void automaticSettlement(Long clubId, Long scheduleId, Long costPerUser) {
        // 현재 사용자 조회
        User user = userService.getCurrentUser();

        // 클럽 존재 여부만 확인
        if (!clubRepository.existsById(clubId)) {
            throw new CustomException(ErrorCode.CLUB_NOT_FOUND);
        }

        // 정산 조회
        Settlement settlement = settlementRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));

        if (settlement.getTotalStatus() == TotalStatus.COMPLETED) {
            throw new CustomException(ErrorCode.ALREADY_COMPLETED_SETTLEMENT);
        }

        // 선점 트랜잭션 처리: HOLDING|FAILED → IN_PROGRESS
        int updated = settlementRepository.markProcessing(settlement.getSettlementId());
        if (updated != 1) {
            throw new CustomException(ErrorCode.ALREADY_SETTLING_SCHEDULE);
        }

        // 참가자 ID 목록 조회
        List<Long> targetUserIds =
                userSettlementRepository.findAllUserSettlementIdsBySettlementIdAndStatus(
                        settlement.getSettlementId(), SettlementStatus.HOLD_ACTIVE);

        long userCount = targetUserIds.size();

        // 가격이 0원이거나 참여자가 없는 경우 → 정산 완료 이벤트 발행
        if (costPerUser == 0 || userCount == 0) {
            eventPublisher.publishEvent(new SettlementCompletedEvent(
                    settlement.getSettlementId(), scheduleId, clubId, LocalDateTime.now()));
            return;
        }

        // 총 금액 계산
        long totalAmount = userCount * costPerUser;
        settlement.updateSum(totalAmount);
        settlementRepository.save(settlement);

        // 리더 지갑 조회
        Wallet leaderWallet = walletRepository.findByUserWithoutLock(user)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        // 정산 프로세스 이벤트 발행 (Outbox)
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
                        "costPerUser", costPerUser,
                        "totalAmount", totalAmount,
                        "targetUserIds", targetUserIds
                )
        );
    }

    /* 스케줄 참여자 정산 목록 조회 */
    @Transactional(readOnly = true)
    public SettlementResponseDto getSettlementList(Long scheduleId, Pageable pageable) {
        Settlement settlement = settlementRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));
        Page<UserSettlementDto> userSettlementList = userSettlementRepository
                .findAllDtoBySettlement(settlement, pageable);
        return SettlementResponseDto.from(userSettlementList);
    }
}
