package com.example.onlyone.domain.schedule.dto.request;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.Status;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ScheduleRequestDto {
    @NotBlank
    @Size(max = 20, message = "정기 모임 이름은 20자 이내여야 합니다.")
    private String name;
    private String location;
    @NotNull
    private int cost;
    @NotNull
    private int userLimit;
    @NotNull
    private LocalDateTime scheduleTime;

    public Schedule toEntity(Club club) {
        return Schedule.builder()
                .club(club)
                .name(name)
                .location(location)
                .cost(cost)
                .userLimit(userLimit)
                .scheduleTime(scheduleTime)
                .status(Status.READY)
                .build();
    }
}
