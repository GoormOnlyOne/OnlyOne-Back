package com.example.onlyone.domain.wallet.repository;

import com.example.onlyone.domain.wallet.entity.WalletTransactionType;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction,Long> {
    Page<WalletTransaction> findByWalletAndType(Wallet wallet, WalletTransactionType walletTransactionType, Pageable pageable);
    Page<WalletTransaction> findByWalletAndTypeNot(Wallet wallet, WalletTransactionType walletTransactionType, Pageable pageable);
    Page<WalletTransaction> findByWallet(Wallet wallet, Pageable pageable);
}
