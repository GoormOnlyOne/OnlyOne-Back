package com.example.onlyone.domain.schedule.dto.response;

import com.example.onlyone.domain.schedule.entity.Schedule;

import java.time.LocalDateTime;

public record ScheduleDetailResponseDto(
    Long scheduleId,
    String name,
    LocalDateTime scheduleTime,
    Long cost,
    int userLimit,
    String location
) {
    public static ScheduleDetailResponseDto from(Schedule schedule) {
        return new ScheduleDetailResponseDto(
                schedule.getScheduleId(),
                schedule.getName(),
                schedule.getScheduleTime(),
                schedule.getCost(),
                schedule.getUserLimit(),
                schedule.getLocation()
        );
    }
}
