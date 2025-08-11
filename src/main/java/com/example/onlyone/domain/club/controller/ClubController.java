package com.example.onlyone.domain.club.controller;

import com.example.onlyone.domain.club.dto.request.ClubRequestDto;
import com.example.onlyone.domain.club.dto.response.ClubDetailResponseDto;
import com.example.onlyone.domain.club.dto.response.ClubNameResponseDto;
import com.example.onlyone.domain.club.service.ClubService;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Tag(name = "Club")
@RequiredArgsConstructor
@RequestMapping("/clubs")
public class ClubController {

    private final ClubService clubService;

    @Operation(summary = "모임 생성", description = "모임을 생성합니다.")
    @PostMapping
    public ResponseEntity<?> createClub(@RequestBody @Valid ClubRequestDto requestDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(CommonResponse.success(clubService.createClub(requestDto)));
    }

    @Operation(summary = "모임 수정", description = "모임을 수정합니다.")
    @PatchMapping("/{clubId}")
    public ResponseEntity<?> updateClub(@PathVariable Long clubId, @RequestBody @Valid ClubRequestDto requestDto) {
        clubService.updateClub(clubId,requestDto);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(null));
    }

    @Operation(summary = "모임 상세 조회", description = "모임을 상세하게 조회합니다.")
    @GetMapping("/{clubId}")
    public ResponseEntity<?> getClubDetail(@PathVariable Long clubId) {
        ClubDetailResponseDto clubDetailResponseDto = clubService.getClubDetail(clubId);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(clubDetailResponseDto));
    }

    @Operation(summary = "모임 가입", description = "모임에 가입한다.")
    @PostMapping("/{clubId}/join")
    public ResponseEntity<?> joinClub(@PathVariable Long clubId) {
        clubService.joinClub(clubId);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(null));
    }

    @Operation(summary = "모임 탈퇴", description = "모임을 탈퇴한다.")
    @DeleteMapping("/{clubId}/leave")
    public ResponseEntity<?> withdraw(@PathVariable Long clubId) {
        clubService.leaveClub(clubId);
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(null));
    }

    @Operation(summary = "가입하고 있는 모임 조회", description = "가입하고 있는 모임을 조회한다.")
    @GetMapping
    public ResponseEntity<?> getClubNames() {
        List<ClubNameResponseDto> clubNameResponseDto = clubService.getClubNames();
        return ResponseEntity.status(HttpStatus.OK).body(CommonResponse.success(clubNameResponseDto));
    }

}
