package com.example.onlyone.domain.settlement.entity;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.*;

@Entity
@Table(name = "user_settlement")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class UserSettlement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_settlement_id",  updatable = false)
    private Long userSettlementId;

    @Column(name = "status")
    @NotNull
    @Enumerated(EnumType.STRING)
    private SettlementStatus settlementStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id")
    @NotNull
    private Settlement settlement;

    @Column(name = "completed_time")
    @NotNull
    private LocalDateTime completedTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @NotNull
    private User user;

    public void updateSettlementStatus(SettlementStatus settlementStatus) {
        this.settlementStatus = settlementStatus;
    }
}