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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
@Service
@Transactional
@RequiredArgsConstructor
public class PaymentService {
    private final TossPaymentClient tossPaymentClient;
    private final Set<String> processingPayments = ConcurrentHashMap.newKeySet();
    private final WalletTransactionRepository walletTransactionRepository;
    private final UserService userService;
    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;

    /* 세션에 결제 정보 임시 저장 */
    public void savePaymentInfo(SavePaymentRequestDto dto, HttpSession session) {
        session.setAttribute(dto.getOrderId(), dto.getAmount());
    }

    /* 세션에 저장한 결제 정보와 일치 여부 확인 */
    public void confirmPayment(@Valid SavePaymentRequestDto dto, HttpSession session) {
        Object saved = session.getAttribute(dto.getOrderId());
        if (saved == null) {
            throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
        }
        String savedAmount = saved.toString();
        if (!savedAmount.equals(String.valueOf(dto.getAmount()))) {
            throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
        }
        session.removeAttribute(dto.getOrderId());
    }

    /* 토스페이먼츠 결제 승인 */
    public ConfirmTossPayResponse confirm(ConfirmTossPayRequest req) {
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
