package com.example.onlyone.domain.settlement.entity;

// TODO: 순환 의존성 방지 - Schedule 도메인과의 관계를 ID로 변경
// import com.example.onlyone.domain.schedule.entity.Schedule;
// import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "settlement")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Settlement extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_id", updatable = false)
    private Long settlementId;

    // TODO: 순환 의존성 방지 - Schedule 엔티티 대신 ID만 저장
    // @OneToOne(fetch = FetchType.LAZY)
    // @JoinColumn(name = "schedule_id")
    // @NotNull
    // private Schedule schedule;

    @Column(name = "schedule_id", updatable = false)
    @NotNull
    private Long scheduleId;  // ID만 보관하여 순환 의존성 방지

    @Column(name = "sum")
    @NotNull
    private Long sum;

    @Column(name = "total_status")
    @NotNull
    @Enumerated(EnumType.STRING)
    private TotalStatus totalStatus;

    @Column(name = "completed_time")
    private LocalDateTime completedTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", updatable = false)
    @NotNull
    private User receiver;

    @OneToMany(mappedBy = "settlement", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<UserSettlement> userSettlements = new ArrayList<>();

    public void updateSum(Long sum) {
        this.sum = sum;
    }
}