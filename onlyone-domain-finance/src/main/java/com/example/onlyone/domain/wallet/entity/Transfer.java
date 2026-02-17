package com.example.onlyone.domain.wallet.entity;

// TODO: 순환 의존성 방지 - 이벤트 기반으로 변경 필요
// import com.example.onlyone.domain.settlement.entity.UserSettlement;
import com.example.onlyone.common.BaseTimeEntity;
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
public class Transfer extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transfer_id", updatable = false)
    private Long transferId;

    @OneToOne(mappedBy = "transfer", fetch = FetchType.LAZY)
    private WalletTransaction walletTransaction;

    // TODO: 순환 의존성 방지 - Settlement 도메인과의 관계를 이벤트 기반으로 변경
    // @ManyToOne(fetch = FetchType.LAZY)
    // @JoinColumn(name = "user_settlement_id", updatable = false)
    // @NotNull
    // @JsonIgnore
    // private UserSettlement userSettlement;

    @Column(name = "user_settlement_id", updatable = false)
    @NotNull
    private Long userSettlementId;  // ID만 보관하여 순환 의존성 방지
}
