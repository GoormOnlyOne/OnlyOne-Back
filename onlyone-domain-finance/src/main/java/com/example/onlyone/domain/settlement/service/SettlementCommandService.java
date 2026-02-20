package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.common.event.SettlementCompletedEvent;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 정산(Settlement) 커맨드 서비스 — 정산 요청
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SettlementCommandService {

    private final UserService userService;
    private final ClubRepository clubRepository;
    private final SettlementRepository settlementRepository;
    private final UserSettlementRepository userSettlementRepository;
    private final WalletRepository walletRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final OutboxAppender outboxAppender;

    /** 자동 정산 요청 */
    public void automaticSettlement(Long clubId, Long scheduleId, Long costPerUser) {
        log.info("정산 시작: clubId={}, scheduleId={}, costPerUser={}", clubId, scheduleId, costPerUser);
        User user = userService.getCurrentUser();

        if (!clubRepository.existsById(clubId)) {
            throw new CustomException(ErrorCode.CLUB_NOT_FOUND);
        }

        if (!settlementRepository.existsScheduleInClub(scheduleId, clubId)) {
            throw new CustomException(ErrorCode.SCHEDULE_NOT_FOUND);
        }

        Settlement settlement = settlementRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new CustomException(ErrorCode.SETTLEMENT_NOT_FOUND));

        if (!settlement.getReceiver().getUserId().equals(user.getUserId())) {
            throw new CustomException(ErrorCode.MEMBER_CANNOT_CREATE_SETTLEMENT);
        }

        if (settlement.getTotalStatus() == TotalStatus.COMPLETED) {
            throw new CustomException(ErrorCode.ALREADY_COMPLETED_SETTLEMENT);
        }

        int updated = settlementRepository.markProcessing(settlement.getSettlementId());
        if (updated != 1) {
            throw new CustomException(ErrorCode.ALREADY_SETTLING_SCHEDULE);
        }

        List<Long> targetUserIds =
                userSettlementRepository.findUserIdsBySettlementIdAndStatus(
                        settlement.getSettlementId(), SettlementStatus.HOLD_ACTIVE);

        long userCount = targetUserIds.size();

        if (costPerUser == 0 || userCount == 0) {
            eventPublisher.publishEvent(new SettlementCompletedEvent(
                    settlement.getSettlementId(), scheduleId, clubId, LocalDateTime.now()));
            return;
        }

        long totalAmount = userCount * costPerUser;
        settlement.updateSum(totalAmount);
        settlementRepository.save(settlement);

        Wallet leaderWallet = walletRepository.findByUserWithoutLock(user)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        log.info("정산 Outbox 발행: settlementId={}, targetUsers={}, totalAmount={}", settlement.getSettlementId(), userCount, totalAmount);
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
}
