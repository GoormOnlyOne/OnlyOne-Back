package com.example.onlyone.domain.payment.repository;

import com.example.onlyone.domain.payment.entity.Payment;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment,Long> {
}
