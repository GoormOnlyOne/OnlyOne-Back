package com.example.onlyone.domain.payment.entity;

import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Entity
@Table(name = "payment")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "toss_payment_key", nullable = false, unique = true)
    private String tossPaymentKey;

    @Column(name = "toss_order_id", nullable = false,  unique = true)
    private String tossOrderId;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false)
    private Method method;

    @Enumerated(value = EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @OneToOne(mappedBy = "payment", fetch = FetchType.LAZY)
    private WalletTransaction walletTransaction;

}
