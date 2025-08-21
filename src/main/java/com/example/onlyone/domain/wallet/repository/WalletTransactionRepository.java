package com.example.onlyone.domain.wallet.repository;

import com.example.onlyone.domain.wallet.dto.response.UserWalletTransactionDto;
import com.example.onlyone.domain.wallet.entity.Type;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import com.example.onlyone.domain.wallet.entity.WalletTransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Page<WalletTransaction> findByWalletAndTypeAndWalletTransactionStatus(
            Wallet wallet,
            Type type,
            WalletTransactionStatus walletTransactionStatus,
            Pageable pageable
    );
    Page<WalletTransaction> findByWalletAndTypeNotAndWalletTransactionStatus(
            Wallet wallet,
            Type type,
            WalletTransactionStatus walletTransactionStatus,
            Pageable pageable
    );
    Page<WalletTransaction> findByWalletAndWalletTransactionStatus(
            Wallet wallet,
            WalletTransactionStatus walletTransactionStatus,
            Pageable pageable
    );
}