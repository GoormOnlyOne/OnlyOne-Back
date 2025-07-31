package com.example.onlyone.domain.wallet.entity;

import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Entity
@Table(name = "transfer")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transfer_id", updatable = false)
    private Long transferId;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "wallet_transaction_id")
    @NotNull
    private WalletTransaction walletTransaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_settlement_id", updatable = false)
    @NotNull
    @JsonIgnore
    private UserSettlement userSettlement;
}
