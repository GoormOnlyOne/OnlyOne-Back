package com.example.onlyone.domain.payment.controller;

import com.example.onlyone.domain.payment.dto.request.ConfirmTossPayRequest;
import com.example.onlyone.domain.payment.dto.request.SavePaymentRequestDto;
import com.example.onlyone.domain.payment.dto.response.ConfirmTossPayResponse;
import com.example.onlyone.domain.payment.service.PaymentService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@Tag(name = "Payment")
@RequiredArgsConstructor
@RequestMapping("/payments")
public class PaymentController {
    private final PaymentService paymentService;

    @Operation(summary = "결제 정보 임시 저장", description = "결제 승인 API 호출 전 결제 정보를 세션에 임시 저장합니다.")
    @PostMapping("/save")
    public ResponseEntity<?> savePayment(@RequestBody @Valid SavePaymentRequestDto dto, HttpSession session) {
        paymentService.savePaymentInfo(dto, session);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "결제 정보 검증", description = "결제 승인 전 세션에 저장된 정보와 금액을 비교합니다.")
    @PostMapping(value = "/success")
    public ResponseEntity<?> paymentSuccess(@RequestBody @Valid SavePaymentRequestDto dto, HttpSession session) throws IOException, InterruptedException {
        paymentService.confirmPayment(dto, session);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "결제 승인", description = "결제 승인 요청을 토스페이먼츠에 전달합니다.")
    @PostMapping(value = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> confirmPayment(@RequestBody ConfirmTossPayRequest req) {
        ConfirmTossPayResponse response = paymentService.confirm(req);
        return ResponseEntity.ok(CommonResponse.success(response));
    }

//    // 결제 취소 요청
//    @PostMapping("/cancel/{paymentKey}")
//    public ResponseEntity<?> cancelPayment(
//            @PathVariable String paymentKey,
//            @RequestBody @Valid CancelTossPayRequest req) {
//        CancelTossPayResponse response = paymentService.cancel(paymentKey, req);
//        return ResponseEntity.ok(CommonResponse.success(response));
//    }
}
