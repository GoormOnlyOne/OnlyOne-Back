package com.example.onlyone.domain.payment.dto.response;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ConfirmTossPayResponse {
    private String paymentKey;
    private String orderId;
    private String method;
    private String status;
    private Long totalAmount;
    private String approvedAt;
    private CardInfo card;

    @Getter
    @NoArgsConstructor
    public static class CardInfo {
        private String number;
        private String cardType;
        private String issuerCode;
        private String acquirerCode;
    }
}
