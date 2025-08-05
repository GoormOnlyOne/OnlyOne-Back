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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
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
        redisTemplate.delete(redisKey);
//        Object saved = session.getAttribute(dto.getOrderId());
//        if (saved == null) {
//            throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
//        }
//        String savedAmount = saved.toString();
//        if (!savedAmount.equals(String.valueOf(dto.getAmount()))) {
//            throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
//        }
//        session.removeAttribute(dto.getOrderId());
    }

    /* 토스페이먼츠 결제 승인 */
    public ConfirmTossPayResponse confirm(ConfirmTossPayRequest req) {
        // 멱등성 체크: 이미 처리된 주문인지 확인
        Optional<Payment> existingPayment = paymentRepository.findByTossOrderId(req.getOrderId());
        if (existingPayment.isPresent()) {
            throw new CustomException(ErrorCode.ALREADY_COMPLETED_PAYMENT);
        }
        ConfirmTossPayResponse response;
        try {
            // tossPaymentClient를 통해 호출
            response = tossPaymentClient.confirmPayment(req);
        } catch (FeignException.BadRequest e) {
            throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
        } catch (FeignException e) {
            throw new CustomException(ErrorCode.TOSS_PAYMENT_FAILED);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
        User user = userService.getCurrentUser();
        Wallet wallet = walletRepository.findByUser(user)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
        int amount = Math.toIntExact(req.getAmount());
        // 포인트 업데이트
        wallet.updateBalance(wallet.getBalance() + amount);
        // 충전(결제) 기록
        WalletTransaction walletTransaction = WalletTransaction.builder()
                .type(Type.CHARGE)
                .amount(amount)
                .balance(wallet.getBalance())
                .walletTransactionStatus(WalletTransactionStatus.COMPLETED)
                .wallet(wallet)
                .targetWallet(wallet)
                .build();
        walletTransactionRepository.save(walletTransaction);
        paymentRepository.save(Payment.builder()
                .tossPaymentKey(response.getPaymentKey())
                .tossOrderId(response.getOrderId())
                .totalAmount(response.getTotalAmount())
                .method(Method.from(response.getMethod()))
                .status(Status.from(response.getStatus()))
                .walletTransaction(walletTransaction)
                .build());
        return response;
    }
}
