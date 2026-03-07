package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.common.event.SettlementCompletedEvent;
import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.TotalStatus;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.club.exception.ClubErrorCode;
import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            throw new CustomException(ClubErrorCode.CLUB_NOT_FOUND);
        }

        if (settlementRepository.existsScheduleInClub(scheduleId, clubId) == 0) {
            throw new CustomException(FinanceErrorCode.SCHEDULE_NOT_FOUND);
        }

        Settlement settlement = settlementRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new CustomException(FinanceErrorCode.SETTLEMENT_NOT_FOUND));

        if (!settlement.getReceiver().getUserId().equals(user.getUserId())) {
            throw new CustomException(FinanceErrorCode.MEMBER_CANNOT_CREATE_SETTLEMENT);
        }

        if (settlement.getTotalStatus() == TotalStatus.COMPLETED) {
            throw new CustomException(FinanceErrorCode.ALREADY_COMPLETED_SETTLEMENT);
        }

        Long settlementId = settlement.getSettlementId();
        int updated = settlementRepository.markProcessing(settlementId);
        if (updated != 1) {
            throw new CustomException(FinanceErrorCode.ALREADY_SETTLING_SCHEDULE);
        }

        // markProcessing의 clearAutomatically로 영속성 컨텍스트가 클리어되므로 재조회
        settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new CustomException(FinanceErrorCode.SETTLEMENT_NOT_FOUND));

        List<Long> targetUserIds =
                userSettlementRepository.findUserIdsBySettlementIdAndStatus(
                        settlementId, SettlementStatus.HOLD_ACTIVE);

        long userCount = targetUserIds.size();

        if (costPerUser == 0 || userCount == 0) {
            eventPublisher.publishEvent(new SettlementCompletedEvent(
                    settlementId, scheduleId, clubId, LocalDateTime.now()));
            return;
        }

        long totalAmount = userCount * costPerUser;
        settlement.updateSum(totalAmount);
        settlementRepository.save(settlement);

        Long leaderWalletId = walletRepository.findWalletIdByUserId(user.getUserId());
        if (leaderWalletId == null) {
            throw new CustomException(FinanceErrorCode.WALLET_NOT_FOUND);
        }

        log.info("정산 Outbox 발행: settlementId={}, targetUsers={}, totalAmount={}", settlement.getSettlementId(), userCount, totalAmount);
        outboxAppender.append(
                "Settlement",
                settlement.getSettlementId(),
                "SettlementProcessEvent",
                String.valueOf(settlement.getSettlementId()),
                Map.of(
                        "eventId", UUID.randomUUID().toString(),
                        "occurredAt", Instant.now().toString(),
                        "settlementId", settlement.getSettlementId(),
                        "scheduleId", scheduleId,
                        "clubId", clubId,
                        "leaderId", user.getUserId(),
                        "leaderWalletId", leaderWalletId,
                        "costPerUser", costPerUser,
                        "totalAmount", totalAmount,
                        "targetUserIds", targetUserIds
                )
        );
    }
}
