package com.example.onlyone.domain.club.controller;

import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubCreateResponseDto;
import com.example.onlyone.domain.club.dto.response.ClubDetailResponseDto;
import com.example.onlyone.domain.club.service.ClubCommandService;
import com.example.onlyone.domain.club.service.ClubQueryService;
import com.example.onlyone.global.common.CommonResponse;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Club")
@RequiredArgsConstructor
@RequestMapping("/api/v1/clubs")
public class ClubController {

    private final ClubCommandService clubCommandService;
    private final ClubQueryService clubQueryService;

    @Operation(summary = "모임 생성", description = "모임을 생성합니다.")
    @PostMapping
    public ResponseEntity<CommonResponse<ClubCreateResponseDto>> createClub(
            @RequestBody @Valid ClubRequestDto requestDto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CommonResponse.success(clubCommandService.createClub(requestDto)));
    }

    @Operation(summary = "모임 수정", description = "모임을 수정합니다.")
    @PatchMapping("/{clubId}")
    public ResponseEntity<CommonResponse<ClubCreateResponseDto>> updateClub(
            @PathVariable Long clubId, @RequestBody @Valid ClubRequestDto requestDto) {
        return ResponseEntity.ok(CommonResponse.success(clubCommandService.updateClub(clubId, requestDto)));
    }

    @Operation(summary = "모임 상세 조회", description = "모임을 상세하게 조회합니다.")
    @GetMapping("/{clubId}")
    public ResponseEntity<CommonResponse<ClubDetailResponseDto>> getClubDetail(@PathVariable Long clubId) {
        return ResponseEntity.ok(CommonResponse.success(clubQueryService.getClubDetail(clubId)));
    }

    @Operation(summary = "모임 가입", description = "모임에 가입한다.")
    @PostMapping("/{clubId}/join")
    public ResponseEntity<CommonResponse<Void>> joinClub(@PathVariable Long clubId) {
        clubCommandService.joinClub(clubId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @Operation(summary = "모임 탈퇴", description = "모임을 탈퇴한다.")
    @DeleteMapping("/{clubId}/leave")
    public ResponseEntity<CommonResponse<Void>> leaveClub(@PathVariable Long clubId) {
        clubCommandService.leaveClub(clubId);
        return ResponseEntity.ok(CommonResponse.success(null));
    }
}
