package com.example.onlyone.domain.schedule.controller;

import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
import com.example.onlyone.domain.schedule.service.ScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Schedule")
@RequiredArgsConstructor
@RequestMapping("/clubs/{clubId}/schedules")
public class ScheduleController {
    private final ScheduleService scheduleService;

    @Operation(summary = "정기 모임 생성", description = "정기 모임을 생성합니다.")
    @PostMapping
    public ResponseEntity<?> createSchedule(@PathVariable("clubId") final Long clubId,
                                            @RequestBody @Valid ScheduleRequestDto requestDto) {
        scheduleService.createSchedule(clubId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @Operation(summary = "정기 모임 수정", description = "정기 모임을 수정합니다.")
    @PatchMapping("/{scheduleId}")
    public ResponseEntity<?> updateSchedule(@PathVariable("clubId") final Long clubId,
                                            @PathVariable("scheduleId") final Long scheduleId,
                                            @RequestBody @Valid ScheduleRequestDto requestDto) {
        scheduleService.updateSchedule(clubId, scheduleId, requestDto);
        return ResponseEntity.ok().build();
    }
}
