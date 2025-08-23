package com.example.onlyone.domain.wallet.repository;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.wallet.entity.Wallet;
import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Wallet> findByUser(User user);

    @Query("select w from Wallet w where w.user = :user")
    Optional<Wallet> findByUserWithoutLock(@Param("user") User user);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
      UPDATE wallet
         SET pending_out = pending_out + :amount
       WHERE user_id = :userId
         AND posted_balance - pending_out >= :amount
    """, nativeQuery = true)
    int holdBalanceIfEnough(@Param("userId") Long userId, @Param("amount") long amount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
      UPDATE wallet
         SET pending_out = pending_out - :amount
       WHERE user_id = :userId
         AND pending_out >= :amount
    """, nativeQuery = true)
    int releaseHoldBalance(@Param("userId") Long userId, @Param("amount") long amount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
      UPDATE wallet
         SET posted_balance = posted_balance - :amount,
             pending_out    = pending_out - :amount
       WHERE user_id        = :userId
         AND pending_out    >= :amount
         AND posted_balance >= :amount
    """, nativeQuery = true)
    int captureHold(@Param("userId") Long userId, @Param("amount") long amount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
      UPDATE wallet
         SET posted_balance = posted_balance + :amount
       WHERE user_id = :userId
    """, nativeQuery = true)
    int creditByUserId(@Param("userId") Long userId, @Param("amount") long amount);

//    User user(User user);
}