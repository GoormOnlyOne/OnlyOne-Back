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

    @Operation(summary = "정기 모임 참여", description = "정기 모임에 참여합니다.")
    @PatchMapping("/{scheduleId}/users")
    public ResponseEntity<?> joinSchedule(@PathVariable("clubId") final Long clubId,
                                            @PathVariable("scheduleId") final Long scheduleId) {
        scheduleService.joinSchedule(clubId, scheduleId);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "정기 모임 참여 취소", description = "정기 모임 참여를 취소합니다.")
    @DeleteMapping("/{scheduleId}/users")
    public ResponseEntity<?> leaveSchedule(@PathVariable("clubId") final Long clubId,
                                          @PathVariable("scheduleId") final Long scheduleId) {
        scheduleService.leaveSchedule(clubId, scheduleId);
        return ResponseEntity.noContent().build();
    }


}
