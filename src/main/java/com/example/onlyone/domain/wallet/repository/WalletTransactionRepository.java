package com.example.onlyone.domain.wallet.repository;

import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction,Long> {
}
