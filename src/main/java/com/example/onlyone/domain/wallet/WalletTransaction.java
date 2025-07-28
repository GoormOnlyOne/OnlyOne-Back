package com.example.onlyone.domain.wallet;

import com.example.onlyone.global.BaseTimeEntity;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "wallet_transaction")
@Getter
@Setter
@NoArgsConstructor
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
    private Status status;

    @Column(name = "imp_uid",  updatable = false, unique = true)
    @NotNull
    private String impUid;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "wallet_id", updatable = false)
    @NotNull
    @JsonIgnore
    private Wallet wallet;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "target_wallet_id", updatable = false)
    @NotNull
    @JsonIgnore
    private Wallet targetWallet;

}