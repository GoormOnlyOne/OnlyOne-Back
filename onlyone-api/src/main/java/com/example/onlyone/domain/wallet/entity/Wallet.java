package com.example.onlyone.domain.wallet.entity;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * @Version 미사용 이유:
 * 잔액 변경은 pessimistic lock + Redis gate (wallet_gate_acquire.lua) +
 * conditional native UPDATE (captureHold, creditByUserId 등)로 동시성을 제어한다.
 * JPA @Version(optimistic lock)을 추가하면 native query와 version 칼럼이 불일치하여
 * StaleObjectStateException이 발생하므로 의도적으로 사용하지 않는다.
 */
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

    @Builder.Default
    @OneToMany(mappedBy = "wallet", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<WalletTransaction> walletTransactions = new ArrayList<>();

    public void updateBalance(Long balance) {
        this.postedBalance = balance;
    }
}