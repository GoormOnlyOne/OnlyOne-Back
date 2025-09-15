package com.example.onlyone.domain.settlement.service;

import com.example.onlyone.domain.schedule.repository.ScheduleRepository;
import com.example.onlyone.domain.settlement.dto.event.WalletCaptureFailedEvent;
import com.example.onlyone.domain.settlement.dto.event.WalletCaptureSucceededEvent;
import com.example.onlyone.domain.settlement.entity.SettlementStatus;
import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.domain.settlement.repository.SettlementRepository;
import com.example.onlyone.domain.settlement.repository.UserSettlementRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.service.RedisLuaService;
import com.example.onlyone.domain.wallet.service.WalletService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.Map;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class UserSettlementService {
    private final UserSettlementRepository userSettlementRepository;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final SettlementRepository settlementRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final RedisLuaService  redisLuaService;

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


        redisLuaService.withWalletGate(participantId, "capture", 10, () -> {
            
            // 조회
            UserSettlement us = userSettlementRepository
                    .findBySettlement_SettlementIdAndUser_UserId(settlementId, participantId)
                    .orElseThrow(() -> {
                        return new CustomException(ErrorCode.USER_SETTLEMENT_NOT_FOUND);
                    });

            
            // 이미 처리 완료면 멱등 스킵
            if (us.getSettlementStatus() == SettlementStatus.COMPLETED) {
                log.warn("[SETTLEMENT_DEBUG] ⏭️ 이미 처리 완료된 정산 - participantId: {}, userSettlementId: {}", 
                        participantId, us.getUserSettlementId());
                return;
            }
            
            User participant = userRepository.findById(participantId)
                    .orElseThrow(() -> {
                        return new CustomException(ErrorCode.USER_NOT_FOUND);
                    });
            
            Wallet memberWallet = walletRepository.findByUserWithoutLock(participant)
                    .orElseThrow(() -> {
                        return new CustomException(ErrorCode.WALLET_NOT_FOUND);
                    });
            
            Long memberWalletId = memberWallet.getWalletId();
            String operationId = ("stl:%d:usr:%d:v1").formatted(settlementId, participantId);
            
            try {
                // 조건부 UPDATE
                int captured = walletRepository.captureHold(participantId, amount);
                if (captured != 1) {
                    log.error("[SETTLEMENT_DEBUG] ❌ 홀드 캡처 실패 - participantId: {}, captured: {}, amount: {}", 
                            participantId, captured, amount);
                    throw new CustomException(ErrorCode.WALLET_HOLD_CAPTURE_FAILED);
                }
                
                // 상태 변경
                us.updateUserSettlement(SettlementStatus.COMPLETED, LocalDateTime.now());
                UserSettlement savedUs = userSettlementRepository.save(us);
                
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
                failedEventAppender.appendFailedUserSettlementEvent(
                        settlementId, us.getUserSettlementId(), participantId,
                        memberWalletId, leaderId, leaderWalletId, amount,
                        e.getClass().getSimpleName()
                );
                log.error("[SETTLEMENT_DEBUG] ❌ 정산 처리 실패 처리 완료 - participantId: {}, userSettlementId: {}", 
                        participantId, us.getUserSettlementId());
                throw e;
            }
        });
    }

//
//    /**
//     * 참가자별 개별 정산 처리 (독립 트랜잭션)
//     */
//    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
//    public void processParticipantSettlement(Long settlementId, Long leaderWalletId, Long participantId, Long amount) {
//        redisLuaService.withWalletGate(participantId, "capture", 10, () -> {
//            UserSettlement userSettlement = userSettlementRepository
//                    .findBySettlement_SettlementIdAndUser_UserId(settlementId, participantId)
//                    .orElseThrow(() -> new CustomException(ErrorCode.USER_SETTLEMENT_NOT_FOUND));
//
//            // 이미 처리된 경우 스킵 (멱등성)
//            if (userSettlement.getSettlementStatus() == SettlementStatus.COMPLETED) {
//                return;
//            }
//            // 홀드 캡처 (차감)
//            int captured = walletRepository.captureHold(participantId, amount);
//            if (captured != 1) {
//                throw new CustomException(ErrorCode.WALLET_HOLD_CAPTURE_FAILED);
//            }
//            // 상태 변경
//            userSettlement.updateUserSettlement(SettlementStatus.COMPLETED, LocalDateTime.now());
//            userSettlementRepository.save(userSettlement);
//            // 트랜잭션 기록
//            User participant = userRepository.findById(participantId)
//                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
//            Wallet memberWallet = walletRepository.findByUserWithoutLock(participant)
//                    .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
//            walletService.createSuccessfulWalletTransactions(memberWallet.getWalletId(), leaderWalletId, amount, userSettlement);
//        });
//    }

    /**
     * 리더에게 총액 가산 (독립 트랜잭션)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void creditToLeader(Long leaderId, long totalAmount) {
        log.error("[SETTLEMENT_DEBUG] 🚀 리더 크레딧 처리 시작 - leaderId: {}, totalAmount: {}", 
                leaderId, totalAmount);
        
        redisLuaService.withWalletGate(leaderId, "credit", 10, () -> {
            log.error("[SETTLEMENT_DEBUG] 🔐 리더 Redis 락 획득 성공 - leaderId: {}", leaderId);
            
            log.error("[SETTLEMENT_DEBUG] 💰 리더 크레딧 시도 - leaderId: {}, totalAmount: {}", 
                    leaderId, totalAmount);
            int credited = walletRepository.creditByUserId(leaderId, totalAmount);
            log.error("[SETTLEMENT_DEBUG] 🎯 리더 크레딧 결과 - leaderId: {}, credited: {}", leaderId, credited);
            
            if (credited != 1) {
                log.error("[SETTLEMENT_DEBUG] ❌ 리더 크레딧 실패 - leaderId: {}, credited: {}, totalAmount: {}", 
                        leaderId, credited, totalAmount);
                throw new CustomException(ErrorCode.WALLET_CREDIT_APPLY_FAILED);
            }
            
            log.error("[SETTLEMENT_DEBUG] ✅ 리더 크레딧 처리 성공 - leaderId: {}, totalAmount: {}", 
                    leaderId, totalAmount);
        });
        
        log.error("[SETTLEMENT_DEBUG] 🏁 리더 크레딧 처리 메서드 종료 - leaderId: {}, totalAmount: {}", 
                leaderId, totalAmount);
    }
}
