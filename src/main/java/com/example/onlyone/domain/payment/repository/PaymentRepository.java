package com.example.onlyone.domain.payment.repository;

import com.example.onlyone.domain.payment.entity.Payment;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByTossOrderId(String tossOrderId);
    Optional<Payment> findByTossPaymentKey(String tossPaymentKey);
}
