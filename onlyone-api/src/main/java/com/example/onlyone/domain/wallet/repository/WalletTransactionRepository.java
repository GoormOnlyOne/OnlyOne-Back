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

    /** 전체 거래: Payment LEFT JOIN FETCH로 N+1 제거 */
    @Query(value = "SELECT wt FROM WalletTransaction wt LEFT JOIN FETCH wt.payment " +
           "WHERE wt.wallet = :wallet AND wt.walletTransactionStatus = :status",
           countQuery = "SELECT COUNT(wt) FROM WalletTransaction wt " +
           "WHERE wt.wallet = :wallet AND wt.walletTransactionStatus = :status")
    Page<WalletTransaction> findByWalletAndStatusFetch(
            @Param("wallet") Wallet wallet,
            @Param("status") WalletTransactionStatus status,
            Pageable pageable
    );

    /** 타입 필터 거래: Payment LEFT JOIN FETCH로 N+1 제거 */
    @Query(value = "SELECT wt FROM WalletTransaction wt LEFT JOIN FETCH wt.payment " +
           "WHERE wt.wallet = :wallet AND wt.type = :type AND wt.walletTransactionStatus = :status",
           countQuery = "SELECT COUNT(wt) FROM WalletTransaction wt " +
           "WHERE wt.wallet = :wallet AND wt.type = :type AND wt.walletTransactionStatus = :status")
    Page<WalletTransaction> findByWalletAndTypeAndStatusFetch(
            @Param("wallet") Wallet wallet,
            @Param("type") TransactionType type,
            @Param("status") WalletTransactionStatus status,
            Pageable pageable
    );

    /** 타입 제외 거래: Payment LEFT JOIN FETCH로 N+1 제거 */
    @Query(value = "SELECT wt FROM WalletTransaction wt LEFT JOIN FETCH wt.payment " +
           "WHERE wt.wallet = :wallet AND wt.type <> :type AND wt.walletTransactionStatus = :status",
           countQuery = "SELECT COUNT(wt) FROM WalletTransaction wt " +
           "WHERE wt.wallet = :wallet AND wt.type <> :type AND wt.walletTransactionStatus = :status")
    Page<WalletTransaction> findByWalletAndTypeNotAndStatusFetch(
            @Param("wallet") Wallet wallet,
            @Param("type") TransactionType type,
            @Param("status") WalletTransactionStatus status,
            Pageable pageable
    );

    @Query("select wt.operationId from WalletTransaction wt where wt.operationId in :operationIds")
    Set<String> findExistingOperationIds(@Param("operationIds") Collection<String> operationIds);

}