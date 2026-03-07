package com.example.onlyone.domain.wallet.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

public record WalletTransactionResponseDto(
    int currentPage,
    int pageSize,
    int totalPage,
    long totalElement,
    List<UserWalletTransactionDto> userWalletTransactionList
) {
    public static WalletTransactionResponseDto from(Page<UserWalletTransactionDto> userWalletTransactionList) {
        return new WalletTransactionResponseDto(
                userWalletTransactionList.getNumber(),
                userWalletTransactionList.getSize(),
                userWalletTransactionList.getTotalPages(),
                userWalletTransactionList.getTotalElements(),
                userWalletTransactionList.getContent()
        );
    }
}
