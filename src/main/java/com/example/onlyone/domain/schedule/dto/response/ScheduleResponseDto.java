package com.example.onlyone.domain.schedule.dto.response;

import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.Status;
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
    private Status status;
    private LocalDateTime scheduleTime;
    private int cost;
    private int userLimit;
    private int userCount;
    private boolean isJoined;
    private boolean isLeader;
    private String dDay;

    public static ScheduleResponseDto from(Schedule schedule, int userCount, boolean isJoined, boolean isLeader, String dDay) {
        return ScheduleResponseDto.builder()
                .scheduleId(schedule.getScheduleId())
                .name(schedule.getName())
                .status(schedule.getStatus())
                .scheduleTime(schedule.getScheduleTime())
                .cost(schedule.getCost())
                .userLimit(schedule.getUserLimit())
                .dDay(dDay)
                .build();
    }
}
