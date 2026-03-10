package com.example.onlyone.domain.payment.repository;

import com.example.onlyone.domain.payment.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** CAS Step 1: INSERT IGNORE — 신규 결제 생성 (락 없이 원자적, unique 제약으로 중복 방지) */
    @Modifying
    @Query(nativeQuery = true, value =
        "INSERT IGNORE INTO payment (toss_order_id, total_amount, status, created_at, modified_at) " +
        "VALUES (:orderId, :amount, 'IN_PROGRESS', NOW(), NOW())")
    int insertIgnore(@Param("orderId") String orderId, @Param("amount") long amount);

    /** CAS Step 2: CANCELED → IN_PROGRESS 원자적 전환 (락 없이 CAS) */
    @Modifying
    @Query(nativeQuery = true, value =
        "UPDATE payment SET status = 'IN_PROGRESS', modified_at = NOW() " +
        "WHERE toss_order_id = :orderId AND status = 'CANCELED'")
    int casReactivate(@Param("orderId") String orderId);

    /** 락 없이 조회 (Phase 2.5 / Phase 3 / 보상 / CAS 후 엔티티 로딩용) */
    @Query("SELECT p FROM Payment p WHERE p.tossOrderId = :orderId")
    Optional<Payment> findByTossOrderIdWithoutLock(@Param("orderId") String orderId);
}
