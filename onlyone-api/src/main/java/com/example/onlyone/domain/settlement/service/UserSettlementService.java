package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.settlement.event.FailedSettlementContext;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.service.WalletGateService;
import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.domain.user.exception.UserErrorCode;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class UserSettlementService {
    private static final int WALLET_GATE_TTL_SECONDS = 10;

    private final UserSettlementRepository userSettlementRepository;
    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final WalletGateService walletGateService;

    private final OutboxAppender outboxAppender;
    private final FailedEventAppender failedEventAppender;

    /**
     * 참가자별 개별 정산 처리 (독립 트랜잭션)
     * - 성공: UserSettlement.COMPLETED + SUCCESS 이벤트 Outbox
     * - 실패: UserSettlement.FAILED    + FAILED  이벤트 Outbox
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void processParticipantSettlement(Long settlementId,
                                             Long leaderId,
                                             Long leaderWalletId,
                                             Long participantId,
                                             Long amount) {
        log.info("참여자 정산 처리 시작: settlementId={}, participantId={}, amount={}", settlementId, participantId, amount);
        walletGateService.withWalletGate(participantId, "capture", WALLET_GATE_TTL_SECONDS, () -> {
            // 조회
            UserSettlement us = userSettlementRepository
                    .findBySettlement_SettlementIdAndUser_UserId(settlementId, participantId)
                    .orElseThrow(() -> new CustomException(FinanceErrorCode.USER_SETTLEMENT_NOT_FOUND));
            // 이미 처리 완료면 멱등 스킵
            if (us.getSettlementStatus() == SettlementStatus.COMPLETED) {
                return;
            }
            User participant = userRepository.findById(participantId)
                    .orElseThrow(() -> new CustomException(UserErrorCode.USER_NOT_FOUND));
            Wallet memberWallet = walletRepository.findByUserWithoutLock(participant)
                    .orElseThrow(() -> new CustomException(FinanceErrorCode.WALLET_NOT_FOUND));
            Long memberWalletId = memberWallet.getWalletId();
            String operationId = ("stl:%d:usr:%d:v1").formatted(settlementId, participantId);
            try {
                // 조건부 UPDATE
                int captured = walletRepository.captureHold(participantId, amount);
                if (captured != 1) {
                    throw new CustomException(FinanceErrorCode.WALLET_HOLD_CAPTURE_FAILED);
                }
                // 상태 변경
                us.markCompleted(LocalDateTime.now());
                userSettlementRepository.save(us);
                // 성공 이벤트 Outbox 기록
                outboxAppender.append(
                        "UserSettlement",
                        us.getUserSettlementId(),
                        "ParticipantSettlementResult",
                        String.valueOf(memberWalletId),
                        Map.of(
                                "type", "SUCCESS",
                                "operationId", operationId,
                                "occurredAt", java.time.Instant.now().toString(),
                                "settlementId", settlementId,
                                "userSettlementId", us.getUserSettlementId(),
                                "participantId", participantId,
                                "memberWalletId", memberWalletId,
                                "leaderId", leaderId,
                                "leaderWalletId", leaderWalletId,
                                "amount", amount
                        )
                );
            } catch (Exception e) {
                // Fix 5: updateStatusIfRequested를 FailedEventAppender의 REQUIRES_NEW tx로 이동
                // 같은 tx에서 상태 업데이트 후 throw하면 롤백되므로, 별도 tx에서 처리
                failedEventAppender.appendFailedUserSettlementEvent(
                        new FailedSettlementContext(
                                settlementId, us.getUserSettlementId(), participantId,
                                memberWalletId, leaderId, leaderWalletId, amount
                        )
                );
                throw e;
            }
        });
    }

    /**
     * 리더에게 총액 가산 (독립 트랜잭션)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void creditToLeader(Long leaderId, long totalAmount) {
        log.info("리더 정산 가산: leaderId={}, totalAmount={}", leaderId, totalAmount);
        walletGateService.withWalletGate(leaderId, "credit", WALLET_GATE_TTL_SECONDS, () -> {
            int credited = walletRepository.creditByUserId(leaderId, totalAmount);
            if (credited != 1) {
                throw new CustomException(FinanceErrorCode.WALLET_CREDIT_APPLY_FAILED);
            }
        });
    }
}
