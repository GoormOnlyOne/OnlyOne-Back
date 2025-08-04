package com.example.onlyone.domain.wallet.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;

@Builder
@Getter
@AllArgsConstructor
public class WalletTransactionResponseDto {
    private int currentPage;
    private int pageSize;
    private int totalPage;
    private long totalElement;
    List<UserWalletTransactionDto> userWalletTransactionList;

    public static WalletTransactionResponseDto from(Page<UserWalletTransactionDto> userWalletTransactionList) {
        return WalletTransactionResponseDto.builder()
                .currentPage(userWalletTransactionList.getNumber())
                .pageSize(userWalletTransactionList.getSize())
                .totalPage(userWalletTransactionList.getTotalPages())
                .totalElement(userWalletTransactionList.getTotalElements())
                .userWalletTransactionList(userWalletTransactionList.getContent())
                .build();
    }
}
