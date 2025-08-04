package com.example.onlyone.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

@Getter
public class SavePaymentRequestDto {
    @NotBlank
    private String orderId;

    @NotNull
    private long amount;
}
