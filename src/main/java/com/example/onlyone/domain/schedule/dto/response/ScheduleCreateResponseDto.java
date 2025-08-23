package com.example.onlyone.domain.schedule.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
@AllArgsConstructor
public class ScheduleCreateResponseDto {
    private Long scheduleId;
}
