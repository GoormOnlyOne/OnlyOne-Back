package com.example.onlyone.domain.wallet.entity;

import com.example.onlyone.domain.chat.entity.Message;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "wallet")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Wallet extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wallet_id", updatable = false)
    private Long walletId;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "user_id", unique = true)
    @NotNull
    private User user;

//    @Column(name = "balance")
//    @NotNull
//    private int balance;

    @Column(name = "posted_balance")
    private Long postedBalance;

    @Column(name = "pending_out")
    private Long pendingOut;

    @OneToMany(mappedBy = "wallet", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WalletTransaction> walletTransactions = new ArrayList<>();

    public void updateBalance(Long balance) {
        this.postedBalance = balance;
    }
}