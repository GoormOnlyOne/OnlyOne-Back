package com.example.onlyone.domain.wallet.dto.response;

import com.example.onlyone.domain.wallet.entity.Type;
import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import com.example.onlyone.domain.wallet.entity.WalletTransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Builder
@Getter
@AllArgsConstructor
public class UserWalletTransactionDto {
    private Type type;
    private String title;
    private WalletTransactionStatus status;
    private String mainImage;
    private int amount;
    private LocalDateTime createdAt;

    public static UserWalletTransactionDto from(WalletTransaction walletTransaction, String title, String mainImage) {
        return UserWalletTransactionDto.builder()
                .type(walletTransaction.getType())
                .title(title)
                .status(walletTransaction.getWalletTransactionStatus())
                .mainImage(mainImage)
                .amount(walletTransaction.getAmount())
                .createdAt(walletTransaction.getCreatedAt())
                .build();
    }
}
