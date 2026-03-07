package com.example.onlyone.domain.payment.dto.response;

public record ConfirmTossPayResponse(
    String paymentKey,
    String orderId,
    String method,
    String status,
    Long totalAmount,
    String approvedAt,
    CardInfo card
) {
    public record CardInfo(
        String number,
        String cardType,
        String issuerCode,
        String acquirerCode
    ) {
    }
}
