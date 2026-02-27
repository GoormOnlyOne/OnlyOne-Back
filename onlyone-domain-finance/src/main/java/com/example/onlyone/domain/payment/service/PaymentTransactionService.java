package com.example.onlyone.domain.payment.service;

import com.example.onlyone.domain.payment.dto.request.ConfirmTossPayRequest;
import com.example.onlyone.domain.payment.dto.response.ConfirmTossPayResponse;
import com.example.onlyone.domain.payment.entity.Method;
import com.example.onlyone.domain.payment.entity.Payment;
import com.example.onlyone.domain.payment.entity.Status;
import com.example.onlyone.domain.payment.repository.PaymentRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.entity.TransactionType;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import com.example.onlyone.domain.wallet.entity.WalletTransactionStatus;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.repository.WalletTransactionRepository;
import com.example.onlyone.domain.finance.exception.FinanceErrorCode;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.GlobalErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PaymentService의 각 Phase를 독립 트랜잭션으로 실행하는 서비스.
 * Spring AOP 프록시는 self-invocation을 인터셉트하지 않으므로,
 * REQUIRES_NEW 메서드를 별도 빈으로 분리하여 프록시를 통해 호출되도록 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private final PaymentRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final UserService userService;

    /**
     * Phase 1: CAS 기반 결제 선점 (독립 트랜잭션, 즉시 커밋)
     *
     * READ_COMMITTED 격리: gap lock 제거 → row lock만 사용.
     * void 반환: 호출부에서 반환값 미사용 → happy path SELECT 제거로 DB 호출 50% 감소.
     *
     * 1) INSERT IGNORE — 신규 결제 (DB 1회만, SELECT 불필요)
     * 2) 실패 시 SELECT (WITHOUT LOCK) + 상태 확인 + CAS UPDATE
     * unique 제약 + CAS UPDATE가 원자적으로 상태 전이 보장, 이중 결제 불가
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void claimPayment(String orderId, long amount) {
        int inserted = paymentRepository.insertIgnore(orderId, amount);
        if (inserted == 1) return; // Happy path: INSERT 1회만으로 완료, SELECT 불필요

        // Duplicate: 상태 확인 후 적절한 에러 반환
        Payment p = paymentRepository.findByTossOrderIdWithoutLock(orderId)
                .orElseThrow(() -> new CustomException(GlobalErrorCode.INTERNAL_SERVER_ERROR));

        switch (p.getStatus()) {
            case DONE -> throw new CustomException(FinanceErrorCode.ALREADY_COMPLETED_PAYMENT);
            case IN_PROGRESS -> throw new CustomException(FinanceErrorCode.PAYMENT_IN_PROGRESS);
            case CANCELED -> {
                int reactivated = paymentRepository.casReactivate(orderId);
                if (reactivated == 0) throw new CustomException(FinanceErrorCode.PAYMENT_IN_PROGRESS);
            }
        }
    }

    /**
     * Phase 3: paymentKey 저장 + 지갑 반영 + 트랜잭션 기록 (독립 트랜잭션)
     *
     * walletRepository.creditByUserId()로 원자적 잔액 증가 (락 없음).
     * UPDATE wallet SET posted_balance = posted_balance + :amount WHERE user_id = :userId
     * DB 수준에서 원자적이므로 동시 충전/정산이 겹쳐도 정확한 금액 반영.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyPaymentResult(String orderId, Long amount, ConfirmTossPayResponse response) {
        User user = userService.getCurrentUser();

        // creditByUserId는 @Modifying(clearAutomatically=true)로 영속성 컨텍스트를 클리어하므로
        // payment 조회를 그 이후에 수행해야 detached entity 문제를 방지한다.
        int updated = walletRepository.creditByUserId(user.getUserId(), amount);
        if (updated != 1) throw new CustomException(FinanceErrorCode.WALLET_NOT_FOUND);

        Payment payment = paymentRepository.findByTossOrderIdWithoutLock(orderId)
                .orElseThrow(() -> new CustomException(GlobalErrorCode.INTERNAL_SERVER_ERROR));

        Wallet wallet = walletRepository.findByUserWithoutLock(user)
                .orElseThrow(() -> new CustomException(FinanceErrorCode.WALLET_NOT_FOUND));

        WalletTransaction walletTransaction = payment.getWalletTransaction();

        if (walletTransaction != null) {
            walletTransaction.update(TransactionType.CHARGE, amount, wallet.getPostedBalance(), WalletTransactionStatus.COMPLETED, wallet, wallet);
        } else {
            walletTransaction = WalletTransaction.builder()
                    .type(TransactionType.CHARGE)
                    .amount(amount)
                    .balance(wallet.getPostedBalance())
                    .walletTransactionStatus(WalletTransactionStatus.COMPLETED)
                    .wallet(wallet)
                    .targetWallet(wallet)
                    .build();
        }
        walletTransactionRepository.save(walletTransaction);

        payment.applyConfirmResult(response.paymentKey(), Status.from(response.status()), Method.from(response.method()), walletTransaction);
        walletTransaction.updatePayment(payment);
    }

    /* 보상: Phase 3 실패 시 Payment 상태를 CANCELED로 기록 (독립 트랜잭션, 락 불필요) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPaymentAborted(String orderId) {
        try {
            Payment payment = paymentRepository.findByTossOrderIdWithoutLock(orderId)
                    .orElse(null);
            if (payment != null && payment.getStatus() != Status.DONE) {
                payment.markCanceled();
            }
        } catch (Exception e) {
            log.error("Failed to mark payment as CANCELED for orderId={}", orderId, e);
        }
    }

    /* 결제 실패 기록 (독립 트랜잭션, orderId는 유니크이므로 단일 사용자 흐름에서 락 불필요) */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reportFail(ConfirmTossPayRequest req) {
        Payment payment = paymentRepository.findByTossOrderIdWithoutLock(req.orderId())
                .orElseGet(() -> {
                    Payment p = Payment.builder()
                            .tossOrderId(req.orderId())
                            .tossPaymentKey(req.paymentKey())
                            .totalAmount(req.amount())
                            .status(Status.CANCELED)
                            .build();
                    return paymentRepository.saveAndFlush(p);
                });

        if (payment.getStatus() == Status.DONE) return;

        if (payment.getStatus() != Status.CANCELED) {
            payment.markCanceled();
        }

        WalletTransaction tx = payment.getWalletTransaction();
        if (tx != null) {
            if (tx.getWalletTransactionStatus() != WalletTransactionStatus.FAILED) {
                tx.updateStatus(WalletTransactionStatus.FAILED);
                walletTransactionRepository.saveAndFlush(tx);
            }
            return;
        }

        Wallet wallet = walletRepository.findByUserWithoutLock(userService.getCurrentUser())
                .orElseThrow(() -> new CustomException(FinanceErrorCode.WALLET_NOT_FOUND));

        WalletTransaction failTx = WalletTransaction.builder()
                .type(TransactionType.CHARGE)
                .amount(req.amount())
                .balance(wallet.getPostedBalance())
                .walletTransactionStatus(WalletTransactionStatus.FAILED)
                .wallet(wallet)
                .targetWallet(wallet)
                .build();

        failTx.updatePayment(payment);
        payment.linkWalletTransaction(failTx);

        walletTransactionRepository.saveAndFlush(failTx);
        paymentRepository.saveAndFlush(payment);
    }
}
