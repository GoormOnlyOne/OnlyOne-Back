package com.example.onlyone.global.feign;

import com.example.onlyone.domain.payment.dto.request.CancelTossPayRequest;
import com.example.onlyone.domain.payment.dto.response.CancelTossPayResponse;
import com.example.onlyone.domain.payment.dto.request.ConfirmTossPayRequest;
import com.example.onlyone.domain.payment.dto.response.ConfirmTossPayResponse;
import feign.FeignException;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Retryable;
import org.springframework.web.bind.annotation.*;
import com.example.onlyone.global.config.TossFeignConfig;

@FeignClient(
        name = "tossClient",
        url = "${payment.toss.base-url}",
        configuration = TossFeignConfig.class
)
public interface TossPaymentClient {

    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    ConfirmTossPayResponse confirmPayment(@RequestBody ConfirmTossPayRequest paymentConfirmRequest);

//    @PostMapping(value = "/{paymentKey}/cancel", consumes = MediaType.APPLICATION_JSON_VALUE)
//    CancelTossPayResponse cancelPayment(
//            @RequestHeader("Idempotency-Key") String idempotencyKey,
//            @PathVariable("paymentKey") String paymentKey,
//            @RequestBody CancelTossPayRequest request
//    );
}

