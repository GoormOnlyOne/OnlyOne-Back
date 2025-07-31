package com.example.onlyone.domain.payment.entity;

import com.example.onlyone.domain.wallet.entity.WalletTransaction;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Entity
@Table(name = "payment")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Payment {
    @Id
    byte[] paymentId;

    @Column(nullable = false, unique = true)
    String tossPaymentKey;

    @Column(nullable = false)
    String tossOrderId;

    long totalAmount;

    @Enumerated(value = EnumType.STRING)
    @Column(nullable = false)
    Method method;

    @Enumerated(value = EnumType.STRING)
    @Column(nullable = false)
    Status status;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "wallet_transaction_id")
    @NotNull
    private WalletTransaction walletTransaction;
}
