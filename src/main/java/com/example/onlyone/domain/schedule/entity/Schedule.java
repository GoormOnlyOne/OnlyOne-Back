package com.example.onlyone.domain.schedule.entity;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.global.BaseTimeEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.*;

@Entity
@Table(name = "schedule")
@Getter
@Setter
@NoArgsConstructor
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

    @Column(name = "status")
    @NotNull
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "limit")
    @NotNull
    private int limit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "club_id")
    @NotNull
    private Club club;
}