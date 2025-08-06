package com.example.onlyone.domain.wallet.entity;

import com.example.onlyone.domain.payment.entity.Payment;
import com.example.onlyone.global.BaseTimeEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Entity
@Table(name = "wallet_transaction")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class WalletTransaction extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wallet_transaction_id", updatable = false)
    private Long walletTransactionId;

    @Column(name = "type")
    @NotNull
    @Enumerated(EnumType.STRING)
    private Type type;

    @Column(name = "amount")
    @NotNull
    private int amount;

    @Column(name = "balance")
    @NotNull
    private int balance;

    @Column(name = "status")
    @NotNull
    @Enumerated(EnumType.STRING)
    private WalletTransactionStatus walletTransactionStatus;

//    @Column(name = "imp_uid",  updatable = false, unique = true)
//    private String impUid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", updatable = false)
    @NotNull
    @JsonIgnore
    private Wallet wallet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_wallet_id", updatable = false)
    @NotNull
    @JsonIgnore
    private Wallet targetWallet;

    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "transfer_id")
    private Transfer transfer;

    public void updateTransfer(Transfer transfer) {
        this.transfer = transfer;
    }

    public void updatePayment(Payment payment) {
        this.payment = payment;
    }
}