package com.example.onlyone.domain.wallet.entity;

import com.example.onlyone.common.BaseTimeEntity;
import com.example.onlyone.domain.payment.entity.Payment;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;

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

    @Column(name = "operation_id", unique = true)
    private String operationId;

    @Column(name = "type")
    @NotNull
    @Enumerated(EnumType.STRING)
    private TransactionType type;

    @Column(name = "amount")
    @NotNull
    private Long amount;

    @Column(name = "balance")
    @NotNull
    private Long balance;

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

    public void updateStatus(WalletTransactionStatus walletTransactionStatus) {
        this.walletTransactionStatus = walletTransactionStatus;
    }

    public void update(TransactionType type, Long amount, Long postedBalance, WalletTransactionStatus walletTransactionStatus, Wallet wallet) {
        this.type = type;
        this.amount = amount;
        this.balance = postedBalance;
        this.walletTransactionStatus = walletTransactionStatus;
        this.wallet = wallet;
        this.targetWallet = wallet;
    }
}