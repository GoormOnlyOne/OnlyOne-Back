package com.example.onlyone.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class ConfirmPaymentDto {
    @NotBlank
    private String paymentKey;

    @NotBlank
    private String orderId;

    @NotNull
    private Long amount;
}

