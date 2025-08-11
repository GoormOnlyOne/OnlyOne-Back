package com.example.onlyone.domain.payment.repository;

import com.example.onlyone.domain.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findByTossPaymentKey(String tossPaymentKey);

    boolean existsByTossPaymentKey(String paymentKey);
}
