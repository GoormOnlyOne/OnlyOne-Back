package com.example.onlyone.domain.schedule.dto.response;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;

import java.time.LocalDateTime;

public record ScheduleDetailResponseDto(
    Long scheduleId,
    String name,
    ScheduleStatus scheduleStatus,
    LocalDateTime scheduleTime,
    Long cost,
    int userLimit,
    String location
) {
    public static ScheduleDetailResponseDto from(Schedule schedule) {
        return new ScheduleDetailResponseDto(
                schedule.getScheduleId(),
                schedule.getName(),
                schedule.getScheduleStatus(),
                schedule.getScheduleTime(),
                schedule.getCost(),
                schedule.getUserLimit(),
                schedule.getLocation()
        );
    }
}
