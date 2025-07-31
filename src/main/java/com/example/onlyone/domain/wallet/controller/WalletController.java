package com.example.onlyone.domain.wallet.controller;

import com.example.onlyone.domain.wallet.entity.Filter;
import com.example.onlyone.domain.wallet.service.WalletService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.xml.bind.annotation.XmlType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Wallet")
@RequiredArgsConstructor
@RequestMapping("/users/wallet")
public class WalletController {

    private final WalletService walletService;

    @Operation(summary = "정산/거래 내역 목록 조회", description = "사용자의 정산/거래 내역 목록을 조회합니다.")
    @GetMapping
    public ResponseEntity<?> getWalletTransactionList(@RequestParam(defaultValue = "ALL") final Filter filter,
            @PageableDefault(size = 20) final Pageable pageable) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(walletService.getWalletTransactionList(filter, pageable)));
    }

}
