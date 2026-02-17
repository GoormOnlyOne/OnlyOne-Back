package com.example.onlyone.domain.schedule.dto.response;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;

import java.time.LocalDateTime;

public record ScheduleResponseDto(
    Long scheduleId,
    String name,
    ScheduleStatus scheduleStatus,
    LocalDateTime scheduleTime,
    Long cost,
    int userLimit,
    int userCount,
    boolean isJoined,
    boolean isLeader,
    String dDay
) {
    public static ScheduleResponseDto from(Schedule schedule, int userCount, boolean isJoined, boolean isLeader, long dDay) {
        return new ScheduleResponseDto(
                schedule.getScheduleId(),
                schedule.getName(),
                schedule.getScheduleStatus(),
                schedule.getScheduleTime(),
                schedule.getCost(),
                schedule.getUserLimit(),
                userCount,
                isJoined,
                isLeader,
                formatDDay(dDay)
        );
    }

    private static String formatDDay(long dDay) {
        if (dDay == 0) return "D-DAY";
        if (dDay > 0) return "D-" + dDay;
        return "D+" + Math.abs(dDay);
    }
}
