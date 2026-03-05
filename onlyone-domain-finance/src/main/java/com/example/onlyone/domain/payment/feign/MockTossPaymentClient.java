package com.example.onlyone.domain.payment.feign;

import com.example.onlyone.domain.payment.dto.request.CancelTossPayRequest;
import com.example.onlyone.domain.payment.dto.request.ConfirmTossPayRequest;
import com.example.onlyone.domain.payment.dto.response.CancelTossPayResponse;
import com.example.onlyone.domain.payment.dto.response.ConfirmTossPayResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Profile({"local"})
@Component
public class MockTossPaymentClient implements TossPaymentClient {

    @Override
    public ConfirmTossPayResponse confirmPayment(ConfirmTossPayRequest req) {
        return new ConfirmTossPayResponse(
                req.paymentKey(),
                req.orderId(),
                "카드",
                "DONE",
                req.amount(),
                Instant.now().toString(),
                new ConfirmTossPayResponse.CardInfo("4321-****-****-1234", "신용", "3K", "11")
        );
    }

    @Override
    public CancelTossPayResponse cancelPayment(String paymentKey, CancelTossPayRequest req) {
        return new CancelTossPayResponse(paymentKey, "CANCELED");
    }
}
