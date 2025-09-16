package com.example.onlyone.domain.schedule.dto.request;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.schedule.entity.Schedule;
import com.example.onlyone.domain.schedule.entity.ScheduleStatus;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ScheduleRequestDto {
    @NotBlank
    @Size(max = 20, message = "정기 모임 이름은 20자 이내여야 합니다.")
    private String name;
    @NotBlank
    private String location;
    @NotNull
    @Min(value = 0, message = "정기 모임 금액은 0원 이상이어야 합니다.")
    private Long cost;
    @NotNull
    @Min(value = 1, message = "정기 모임 정원은 1명 이상이어야 합니다.")
    @Max(value = 100, message = "정기 모임 정원은 100명 이하여야 합니다.")
    private int userLimit;
    @NotNull
    @FutureOrPresent(message = "현재 시간 이후만 선택할 수 있습니다.")
    private LocalDateTime scheduleTime;

    public Schedule toEntity(Club club) {
        return Schedule.builder()
                .club(club)
                .name(name)
                .location(location)
                .cost(cost)
                .userLimit(userLimit)
                .scheduleTime(scheduleTime)
                .scheduleStatus(ScheduleStatus.READY)
                .build();
    }
}
