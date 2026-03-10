package com.example.onlyone.domain.schedule.dto.response;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

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
    /** ScheduleListRow → DTO 변환 (D-Day 자동 계산) */
    public static ScheduleResponseDto from(ScheduleListRow row) {
        Schedule schedule = row.schedule();
        long dDayValue = ChronoUnit.DAYS.between(
                LocalDate.now(), schedule.getScheduleTime().toLocalDate());
        return new ScheduleResponseDto(
                schedule.getScheduleId(),
                schedule.getName(),
                schedule.getScheduleStatus(),
                schedule.getScheduleTime(),
                schedule.getCost(),
                schedule.getUserLimit(),
                (int) row.userCount(),
                row.isJoined(),
                row.isLeader(),
                formatDDay(dDayValue)
        );
    }

    private static String formatDDay(long dDay) {
        if (dDay == 0) return "D-DAY";
        if (dDay > 0) return "D-" + dDay;
        return "D+" + Math.abs(dDay);
    }
}
