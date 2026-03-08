package com.example.onlyone.domain.wallet.repository;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.wallet.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

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

    @Query(value = "SELECT pending_out FROM wallet WHERE user_id = :userId", nativeQuery = true)
    long getPendingOutByUserId(@Param("userId") Long userId);

    @Query(value = "select wallet_id from wallet where user_id = :userId", nativeQuery = true)
    Long findWalletIdByUserId(@Param("userId") Long userId);

    @Query(value = "SELECT user_id, wallet_id FROM wallet WHERE user_id IN (:userIds)", nativeQuery = true)
    List<Object[]> findWalletIdsByUserIds(@Param("userIds") List<Long> userIds);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
      UPDATE wallet
         SET pending_out = CASE WHEN pending_out >= :amount THEN pending_out - :amount ELSE 0 END
       WHERE user_id IN (:userIds)
         AND pending_out >= :amount
    """, nativeQuery = true)
    int batchReleaseHoldBalance(@Param("userIds") List<Long> userIds, @Param("amount") long amount);

    /**
     * 배치 captureHold — 한 번의 UPDATE로 여러 참가자의 지갑에서 동시 차감.
     * posted_balance >= amount AND pending_out >= amount 인 행만 갱신.
     * 반환값 = 실제 갱신된 행 수 (잔액 부족 참가자는 스킵됨)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
      UPDATE wallet
         SET posted_balance = posted_balance - :amount,
             pending_out    = pending_out - :amount
       WHERE user_id IN (:userIds)
         AND pending_out    >= :amount
         AND posted_balance >= :amount
    """, nativeQuery = true)
    int batchCaptureHold(@Param("userIds") List<Long> userIds, @Param("amount") long amount);
}