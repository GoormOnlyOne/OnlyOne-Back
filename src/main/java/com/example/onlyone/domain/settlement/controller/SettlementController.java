package com.example.onlyone.domain.settlement.controller;

import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
import com.example.onlyone.domain.settlement.service.SettlementService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Settlement")
@RequiredArgsConstructor
@RequestMapping("/clubs/{clubId}/schedules/{scheduleId}/settlements")
public class SettlementController {
    private final SettlementService settlementService;

    @Operation(summary = "정산 요청 생성", description = "정기 모임의 정산 요청을 생성합니다.")
    @PostMapping
    public ResponseEntity<?> createSettlement(@PathVariable("clubId") final Long clubId, @PathVariable("scheduleId") final Long scheduleId) {
        settlementService.createSettlement(clubId, scheduleId);
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(null));
    }

    @Operation(summary = "스케줄 참여자 정산", description = "정기 모임의 참여자가 정산을 진행합니다.")
    @PostMapping("/user")
    public ResponseEntity<?> updateUserSettlement(@PathVariable("clubId") final Long clubId, @PathVariable("scheduleId") final Long scheduleId) {
        settlementService.updateUserSettlement(clubId, scheduleId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "스케줄 참여자 정산 조회", description = "정기 모임 모든 참여자의 정산 상태를 조회합니다.")
    @GetMapping
    public ResponseEntity<?> getSettlementList(@PathVariable("clubId") final Long clubId, @PathVariable("scheduleId") final Long scheduleId,
                                               @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                               Pageable pageable) {
        return ResponseEntity.ok(CommonResponse.success(settlementService.getSettlementList(clubId, scheduleId,  pageable)));
    }

    @Operation(summary = "유저 정산 요청 조회", description = "최근 처리된 정산 / 아직 처리되지 않은 정산 목록을 조회합니다.")
    @GetMapping("/me")
    public ResponseEntity<?> getMySettlementList(@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
                                               Pageable pageable) {
        return ResponseEntity.ok(CommonResponse.success(settlementService.getMySettlementList(pageable)));
    }
}
