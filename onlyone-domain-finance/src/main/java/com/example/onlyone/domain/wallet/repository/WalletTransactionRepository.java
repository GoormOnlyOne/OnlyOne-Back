package com.example.onlyone.domain.wallet.repository;

import com.example.onlyone.domain.wallet.dto.response.UserWalletTransactionDto;
import com.example.onlyone.domain.wallet.entity.TransactionType;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import com.example.onlyone.domain.wallet.entity.WalletTransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    Page<WalletTransaction> findByWalletAndTypeAndWalletTransactionStatus(
            Wallet wallet,
            TransactionType type,
            WalletTransactionStatus walletTransactionStatus,
            Pageable pageable
    );
    Page<WalletTransaction> findByWalletAndTypeNotAndWalletTransactionStatus(
            Wallet wallet,
            TransactionType type,
            WalletTransactionStatus walletTransactionStatus,
            Pageable pageable
    );
    Page<WalletTransaction> findByWalletAndWalletTransactionStatus(
            Wallet wallet,
            WalletTransactionStatus walletTransactionStatus,
            Pageable pageable
    );

    @Query("select wt.operationId from WalletTransaction wt where wt.operationId in :operationIds")
    Set<String> findExistingOperationIds(@Param("operationIds") Collection<String> operationIds);

}