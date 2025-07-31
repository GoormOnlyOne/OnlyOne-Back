package com.example.onlyone.domain.settlement.entity;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.*;

@Entity
@Table(name = "settlement")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Settlement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_id", updatable = false)
    private Long settlementId;

    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "schedule_id")
    @NotNull
    private Schedule schedule;

    @Column(name = "sum")
    @NotNull
    private int sum;

    @Column(name = "total_status")
    @NotNull
    @Enumerated(EnumType.STRING)
    private TotalStatus totalStatus;

    @Column(name = "completed_time")
    private LocalDateTime completedTime;

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "user_id", updatable = false)
    @NotNull
    private User receiver;

    public void updateStatus(TotalStatus totalStatus) {
        this.totalStatus = totalStatus;
    }
}