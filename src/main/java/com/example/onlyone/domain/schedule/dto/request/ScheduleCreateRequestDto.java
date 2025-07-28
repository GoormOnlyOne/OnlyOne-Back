package com.example.onlyone.domain.schedule.dto.request;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.Status;

import java.time.LocalDateTime;

public class ScheduleCreateRequestDto {
    private String name;
    private String location;
    private int cost;
    private int userLimit;
    private LocalDateTime scheduleTime;

    public Schedule toEntity(Club club) {
        return Schedule.builder()
                .club(club)
                .name(name)
                .location(location)
                .cost(cost)
                .scheduleTime(scheduleTime)
                .status(Status.READY)
                .build();
    }
}
