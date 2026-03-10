package com.example.onlyone.domain.wallet.dto.response;

import com.example.onlyone.domain.wallet.entity.TransactionType;
import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import com.example.onlyone.domain.wallet.entity.WalletTransactionStatus;

import java.time.LocalDateTime;

public record UserWalletTransactionDto(
    TransactionType type,
    String title,
    WalletTransactionStatus status,
    String mainImage,
    Long amount,
    LocalDateTime createdAt
) {
    public static UserWalletTransactionDto from(WalletTransaction walletTransaction, String title, String mainImage) {
        return new UserWalletTransactionDto(
                walletTransaction.getType(),
                title,
                walletTransaction.getWalletTransactionStatus(),
                mainImage,
                walletTransaction.getAmount(),
                walletTransaction.getCreatedAt()
        );
    }
}
