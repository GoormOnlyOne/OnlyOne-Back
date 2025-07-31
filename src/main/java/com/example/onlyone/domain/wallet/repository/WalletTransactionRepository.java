package com.example.onlyone.domain.wallet.repository;

import com.example.onlyone.domain.wallet.dto.response.UserWalletTransactionDto;
import com.example.onlyone.domain.wallet.entity.Type;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction,Long> {
    Page<WalletTransaction> findByWalletAndType(Wallet wallet, Type type, Pageable pageable);
    Page<WalletTransaction> findByWalletAndTypeNot(Wallet wallet, Type type, Pageable pageable);
    Page<WalletTransaction> findByWallet(Wallet wallet, Pageable pageable);
}
