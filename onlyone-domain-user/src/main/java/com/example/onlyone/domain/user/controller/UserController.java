package com.example.onlyone.domain.user.controller;

import com.example.onlyone.domain.user.dto.request.ProfileUpdateRequestDto;
import com.example.onlyone.domain.user.dto.response.MyPageResponse;
import com.example.onlyone.domain.user.dto.response.ProfileResponseDto;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.common.CommonResponse;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 사용자 관리 컨트롤러
 */
@Tag(name = "사용자", description = "사용자 정보 및 설정 관리 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

  private final UserService userService;

  @GetMapping("/mypage")
  public ResponseEntity<?> getMyPage() {
    MyPageResponse myPageResponse = userService.getMyPage();
    return ResponseEntity.ok(CommonResponse.success(myPageResponse));
  }

    @GetMapping("/profile")
    public ResponseEntity<?> getUserProfile() {
        ProfileResponseDto profileResponse = userService.getUserProfile();
        return ResponseEntity.ok(CommonResponse.success(profileResponse));
    }


    @PutMapping("/profile")
    public ResponseEntity<?> updateUserProfile(@RequestBody ProfileUpdateRequestDto request) {
        userService.updateUserProfile(request);
        return ResponseEntity.ok(CommonResponse.success("프로필이 성공적으로 업데이트되었습니다."));
    }

  // TODO: 순환 의존성 방지를 위해 API 모듈로 이동 필요
  // @Operation(summary = "유저 정산 요청 조회", description = "최근 처리된 정산 / 아직 처리되지 않은 정산 목록을 조회합니다.")
  // @GetMapping("/settlement")
  // public ResponseEntity<?> getMySettlementList(@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
  //                                              Pageable pageable) {
  //   return ResponseEntity.ok(CommonResponse.success(userService.getMySettlementList(pageable)));
  // }
}