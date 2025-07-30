package com.example.onlyone.domain.settlement.controller;

import com.example.onlyone.domain.schedule.dto.request.ScheduleRequestDto;
import com.example.onlyone.domain.settlement.service.SettlementService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @Operation(summary = "참여자 정산", description = "정기 모임의 참여자가 정산을 진행합니다.")
    @PostMapping("/{settlementId}")
    public ResponseEntity<?> updateUserSettlement(@PathVariable("clubId") final Long clubId, @PathVariable("scheduleId") final Long scheduleId,
                                                  @PathVariable("settlementId") final Long settlementId) {
        settlementService.updateUserSettlement(clubId, scheduleId, settlementId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }
}
