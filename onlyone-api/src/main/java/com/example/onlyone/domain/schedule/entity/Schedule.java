package com.example.onlyone.domain.schedule.entity;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.common.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Entity
@Table(name = "schedule", indexes = {
        @Index(name = "idx_schedule_club_time", columnList = "club_id, schedule_time DESC"),
        @Index(name = "idx_schedule_status", columnList = "status")
})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
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

    /** READY 상태 + 미만료 시에만 수정/참여/삭제 가능 */
    public boolean isNotModifiable() {
        return this.scheduleStatus != ScheduleStatus.READY
                || this.scheduleTime.isBefore(LocalDateTime.now());
    }

    public void update(String name, String location, Long cost, int userLimit, LocalDateTime scheduleTime) {
        this.name = name;
        this.location = location;
        this.cost = cost;
        this.userLimit = userLimit;
        this.scheduleTime = scheduleTime;
    }

    private static final Map<ScheduleStatus, Set<ScheduleStatus>> VALID_TRANSITIONS = Map.of(
            ScheduleStatus.READY, Set.of(ScheduleStatus.ENDED),
            ScheduleStatus.ENDED, Set.of(ScheduleStatus.SETTLING, ScheduleStatus.CLOSED),
            ScheduleStatus.SETTLING, Set.of(ScheduleStatus.CLOSED)
    );

    /** 상태 전이 (유효한 전이만 허용) */
    public void transitionTo(ScheduleStatus newStatus) {
        Set<ScheduleStatus> allowed = VALID_TRANSITIONS.getOrDefault(this.scheduleStatus, Set.of());
        if (!allowed.contains(newStatus)) {
            throw new IllegalStateException(
                    String.format("잘못된 상태 전이: %s → %s", this.scheduleStatus, newStatus));
        }
        this.scheduleStatus = newStatus;
    }

}