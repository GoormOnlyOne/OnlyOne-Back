package com.example.onlyone.domain.schedule.entity;

import com.example.onlyone.domain.club.entity.Club;
// TODO: 순환 의존성 방지 - Settlement 도메인 의존성 제거
// import com.example.onlyone.domain.settlement.entity.Settlement;
import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "schedule", indexes = {
        @Index(name = "idx_schedule_club_time", columnList = "club_id, schedule_time DESC"),
        @Index(name = "idx_schedule_status", columnList = "status")
})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Schedule extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id", updatable = false)
    private Long scheduleId;

    @Column(name = "schedule_time")
    @NotNull
    private LocalDateTime scheduleTime;

    @Column(name = "name")
    @NotNull
    private String name;

    @Column(name = "location")
    @NotNull
    private String location;

            @Column(name = "cost")
            @NotNull
    private Long cost;

    @Column(name = "user_limit")
    @NotNull
    private int userLimit;

    @Column(name = "status")
    @NotNull
    @Enumerated(EnumType.STRING)
    private ScheduleStatus scheduleStatus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "club_id")
    @NotNull
    private Club club;

    @Builder.Default
    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<UserSchedule> userSchedules = new ArrayList<>();

    // TODO: 순환 의존성 방지 - Settlement 관계 제거
    // Settlement은 scheduleId로 Schedule을 참조하도록 변경됨
    // @OneToOne(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    // private Settlement settlement;

    public void update(String name, String location, Long cost, int userLimit, LocalDateTime scheduleTime) {
        this.name = name;
        this.location = location;
        this.cost = cost;
        this.userLimit = userLimit;
        this.scheduleTime = scheduleTime;
    }

    public void updateStatus(ScheduleStatus scheduleStatus) {
        this.scheduleStatus = scheduleStatus;
    }

    // TODO: 순환 의존성 방지 - Settlement 메서드 제거
    // Settlement 관련 로직은 Settlement 도메인에서 scheduleId로 처리
    // public void updateSettlement(Settlement settlement) { ... }
    // public void removeSettlement(Settlement settlement) { ... }
}