package com.example.onlyone.domain.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ConfirmTossPayRequest {
    @NotBlank
    @JsonProperty("paymentKey")
    private String paymentKey;

    @NotBlank
    @JsonProperty("orderId")
    private String orderId;

    @NotNull
    @JsonProperty("amount")
    private Long amount;
}

