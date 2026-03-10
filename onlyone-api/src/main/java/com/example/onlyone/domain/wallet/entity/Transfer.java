package com.example.onlyone.domain.wallet.entity;

import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Entity
@Table(name = "transfer")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Transfer extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transfer_id", updatable = false)
    private Long transferId;

    @OneToOne(mappedBy = "transfer", fetch = FetchType.LAZY)
    private WalletTransaction walletTransaction;

    @Column(name = "user_settlement_id", updatable = false)
    @NotNull
    private Long userSettlementId;  // ID만 보관하여 순환 의존성 방지
}
