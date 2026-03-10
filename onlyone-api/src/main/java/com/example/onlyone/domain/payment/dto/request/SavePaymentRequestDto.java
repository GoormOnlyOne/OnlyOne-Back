package com.example.onlyone.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SavePaymentRequestDto(
    @NotBlank String orderId,
    @NotNull long amount
) {
}
