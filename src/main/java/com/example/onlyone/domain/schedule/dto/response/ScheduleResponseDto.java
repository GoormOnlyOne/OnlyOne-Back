package com.example.onlyone.domain.schedule.dto.response;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Builder
@Getter
@AllArgsConstructor
public class ScheduleResponseDto {
    private Long scheduleId;
    private String name;
    private ScheduleStatus scheduleStatus;
    private LocalDateTime scheduleTime;
    private int cost;
    private int userLimit;
    private int userCount;
    private boolean isJoined;
    private boolean isLeader;
    private String dDay;

    public static ScheduleResponseDto from(Schedule schedule, int userCount, boolean isJoined, boolean isLeader, long dDay) {
        return ScheduleResponseDto.builder()
                .scheduleId(schedule.getScheduleId())
                .name(schedule.getName())
                .scheduleStatus(schedule.getScheduleStatus())
                .scheduleTime(schedule.getScheduleTime())
                .cost(schedule.getCost())
                .userLimit(schedule.getUserLimit())
                .userCount(userCount)
                .isJoined(isJoined)
                .isLeader(isLeader)
                .dDay(formatDDay(dDay))
                .build();
    }

    private static String formatDDay(long dDay) {
        if (dDay == 0) return "D-DAY";
        if (dDay > 0) return "D-" + dDay;
        return "D+" + Math.abs(dDay);
    }
}
