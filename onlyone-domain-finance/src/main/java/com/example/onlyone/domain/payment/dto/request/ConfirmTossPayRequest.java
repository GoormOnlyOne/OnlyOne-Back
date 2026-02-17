package com.example.onlyone.domain.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ConfirmTossPayRequest(
    @NotBlank @JsonProperty("paymentKey") String paymentKey,
    @NotBlank @JsonProperty("orderId") String orderId,
    @NotNull @JsonProperty("amount") Long amount
) {
}
