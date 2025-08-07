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
public class ScheduleDetailResponseDto {
    private Long scheduleId;
    private String name;
    private LocalDateTime scheduleTime;
    private int cost;
    private int userLimit;

    public static ScheduleDetailResponseDto from(Schedule schedule) {
        return ScheduleDetailResponseDto.builder()
                .scheduleId(schedule.getScheduleId())
                .name(schedule.getName())
                .scheduleTime(schedule.getScheduleTime())
                .cost(schedule.getCost())
                .userLimit(schedule.getUserLimit())
                .build();
    }
}
