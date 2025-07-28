package com.example.onlyone.domain.schedule.controller;

import com.example.onlyone.domain.club.dto.request.ClubCreateRequestDto;
import com.example.onlyone.domain.schedule.dto.request.ScheduleCreateRequestDto;
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
                                            @RequestBody @Valid ScheduleCreateRequestDto requestDto) {
        scheduleService.createSchedule(clubId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
