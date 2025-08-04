package com.example.onlyone.domain.schedule.entity;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.*;

@Entity
@Table(name = "schedule")
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
    private int cost;

    @Column(name = "user_limit")
    @NotNull
    private int userLimit;

    @Column(name = "status")
    @NotNull
    @Enumerated(EnumType.STRING)
    private ScheduleStatus scheduleStatus;

    @Column(name = "schedule_limit")
    @NotNull
    private int scheduleLimit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "club_id")
    @NotNull
    private Club club;

    public void update(String name, String location, int cost, int userLimit, LocalDateTime scheduleTime) {
        this.name = name;
        this.location = location;
        this.cost = cost;
        this.userLimit = userLimit;
        this.scheduleTime = scheduleTime;
    }

    public void updateStatus(ScheduleStatus scheduleStatus) {
        this.scheduleStatus = scheduleStatus;
    }
}