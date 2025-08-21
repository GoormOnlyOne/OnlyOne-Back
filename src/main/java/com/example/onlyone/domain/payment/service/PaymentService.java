package com.example.onlyone.domain.payment.service;

import com.example.onlyone.domain.payment.dto.request.ConfirmTossPayRequest;
import com.example.onlyone.domain.payment.dto.response.ConfirmTossPayResponse;
import com.example.onlyone.domain.payment.dto.request.SavePaymentRequestDto;
import com.example.onlyone.domain.payment.entity.Method;
import com.example.onlyone.domain.payment.entity.Payment;
import com.example.onlyone.domain.payment.entity.Status;
import com.example.onlyone.domain.payment.repository.PaymentRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.wallet.entity.Type;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import com.example.onlyone.domain.wallet.entity.WalletTransactionStatus;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.domain.wallet.repository.WalletTransactionRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.feign.*;
import feign.FeignException;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class PaymentService {
    private final TossPaymentClient tossPaymentClient;
    private final WalletTransactionRepository walletTransactionRepository;
    private final UserService userService;
    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String REDIS_PAYMENT_KEY_PREFIX = "payment:";
    private static final long PAYMENT_INFO_TTL_SECONDS = 30 * 60;

    /* 세션에 결제 정보 임시 저장 */
    public void savePaymentInfo(SavePaymentRequestDto dto, HttpSession session) {
//        session.setAttribute(dto.getOrderId(), dto.getAmount());
        String redisKey = REDIS_PAYMENT_KEY_PREFIX + dto.getOrderId();
        // 값과 함께 TTL 설정
        redisTemplate.opsForValue()
                .set(redisKey, dto.getAmount(), PAYMENT_INFO_TTL_SECONDS, TimeUnit.SECONDS);
    }

    /* 세션에 저장한 결제 정보와 일치 여부 확인 */
    public void confirmPayment(@Valid SavePaymentRequestDto dto, HttpSession session) {
        String redisKey = REDIS_PAYMENT_KEY_PREFIX + dto.getOrderId();
        Object saved = redisTemplate.opsForValue().get(redisKey);
        if (saved == null) {
            throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
        }
        String savedAmount = saved.toString();
        if (!savedAmount.equals(String.valueOf(dto.getAmount()))) {
            throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
        }
        // 검증 완료 후에는 Redis에서 제거
        log.info("REDIS key 등록 완료");
        redisTemplate.delete(redisKey);
    }

    /* 토스페이먼츠 결제 승인 */
    public ConfirmTossPayResponse confirm(ConfirmTossPayRequest req) {
        // 1) 단일 Payment 객체 확보(생성 또는 재사용)
        Payment payment = claimPayment(req.getOrderId(), req.getAmount());
        // 2) 토스페이먼츠 결제 호출
        final ConfirmTossPayResponse response;
        try {
            response = tossPaymentClient.confirmPayment(req);
        } catch (FeignException.BadRequest e) {
            // 실패 기록은 reportFail에서 일괄 처리
            throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
        } catch (FeignException e) {
            throw new CustomException(ErrorCode.TOSS_PAYMENT_FAILED);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        // 3) 지갑 반영 + 트랜잭션 기록
        User user = userService.getCurrentUser();
        Wallet wallet = walletRepository.findByUser(user)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        int amount = Math.toIntExact(req.getAmount());
        wallet.updateBalance(wallet.getBalance() + amount);

        WalletTransaction walletTransaction = WalletTransaction.builder()
                .type(Type.CHARGE)
                .amount(amount)
                .balance(wallet.getBalance())
                .walletTransactionStatus(WalletTransactionStatus.COMPLETED)
                .wallet(wallet)
                .targetWallet(wallet)
                .build();
        walletTransactionRepository.save(walletTransaction);
        // 4) 단일 객체 업데이트 (상태/수단/연결)
        payment.updateOnConfirm(response.getPaymentKey(), Status.from(response.getStatus()), Method.from(response.getMethod()), walletTransaction);
        walletTransaction.updatePayment(payment);
        return response;
    }

    /* 동일 주문을 단일 Payment 엔티티로 선점/반환 */
    private Payment claimPayment(String orderId, long amount) {
        // 비관적 락 사용
        Optional<Payment> found = paymentRepository.findByTossOrderId(orderId);
        if (found.isPresent()) {
            Payment p = found.get();
            switch (p.getStatus()) {
                case DONE -> throw new CustomException(ErrorCode.ALREADY_COMPLETED_PAYMENT);
                case IN_PROGRESS -> throw new CustomException(ErrorCode.PAYMENT_IN_PROGRESS);
                case CANCELED -> {
                    p.updateStatus(Status.IN_PROGRESS);
                    return p;
                }
                default -> { return p; }
            }
        } else {
            try {
                Payment p = Payment.builder()
                        .tossOrderId(orderId)
                        .status(Status.IN_PROGRESS)
                        .totalAmount(amount)
                        .build();
                return paymentRepository.saveAndFlush(p);
            } catch (DataIntegrityViolationException dup) {
                // 다른 트랜잭션이 먼저 만들었다면 재조회 후 동일 분기 처리
                Payment p = paymentRepository.findByTossOrderId(orderId)
                        .orElseThrow(() -> new CustomException(ErrorCode.INTERNAL_SERVER_ERROR));
                switch (p.getStatus()) {
                    case DONE -> throw new CustomException(ErrorCode.ALREADY_COMPLETED_PAYMENT);
                    case IN_PROGRESS -> throw new CustomException(ErrorCode.PAYMENT_IN_PROGRESS);
                    case CANCELED -> {
                        p.updateStatus(Status.IN_PROGRESS);
                        return p;
                    }
                    default -> { return p; }
                }
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reportFail(ConfirmTossPayRequest req) {
        // 멱등성, 동시성 보호: paymentKey로 행 잠금
        Payment payment = paymentRepository.findByTossOrderId(req.getOrderId())
                .orElseGet(() -> {
                    Payment p = Payment.builder()
                            .tossOrderId(req.getOrderId())
                            .tossPaymentKey(req.getPaymentKey())
                            .totalAmount(req.getAmount())
                            .status(Status.IN_PROGRESS)
                            .build();
                    return paymentRepository.save(p);
                });
        // 이미 완료된 결제면 기록하지 않음
        if (payment.getStatus() == Status.DONE) {
            return;
        }
        if (req.getPaymentKey() != null &&
                payment.getTossPaymentKey() != null &&
                payment.getTossPaymentKey().equals(req.getPaymentKey())) {
            return;
        }
        // 실패 기록
        User user = userService.getCurrentUser();
        Wallet wallet = walletRepository.findByUser(user)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
        WalletTransaction walletTransaction = WalletTransaction.builder()
                .type(Type.CHARGE)
                .amount(Math.toIntExact(req.getAmount()))
                .balance(wallet.getBalance())
                .walletTransactionStatus(WalletTransactionStatus.FAILED)
                .wallet(wallet)
                .targetWallet(wallet)
                .build();
        walletTransactionRepository.save(walletTransaction);
        // Payment 갱신
        payment.updateStatus(Status.CANCELED);
        walletTransaction.updatePayment(payment);
    }
}