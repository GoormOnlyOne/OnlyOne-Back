package com.example.onlyone.domain.user.controller;

import com.example.onlyone.domain.user.dto.request.ProfileUpdateRequestDto;
import com.example.onlyone.domain.user.dto.response.MyPageResponse;
import com.example.onlyone.domain.user.dto.response.ProfileResponseDto;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.common.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequestMapping("/users")
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

  /**
   * FCM 토큰 상태 조회 - 인증된 사용자 본인만
   */
  @Operation(summary = "FCM 토큰 상태 조회", description = "현재 로그인한 사용자의 FCM 토큰 등록 여부를 확인합니다")
  @GetMapping("/fcm-token/status")
  public ResponseEntity<CommonResponse<Map<String, Object>>> getFcmTokenStatus() {


    // JWT에서 사용자 정보 추출
    User currentUser = userService.getCurrentUser();

    Map<String, Object> status = new HashMap<>();
    status.put("hasToken", currentUser.hasFcmToken());
    status.put("tokenLength", currentUser.getFcmToken() != null ? currentUser.getFcmToken().length() : 0);

    log.debug("FCM token status checked for user: {}", currentUser.getUserId());

    return ResponseEntity.ok(CommonResponse.success(status));
  }

  /**
   * FCM 토큰 등록/업데이트 - 인증된 사용자 본인만
   */
  @Operation(summary = "FCM 토큰 등록", description = "현재 로그인한 사용자의 FCM 토큰을 등록하거나 업데이트합니다")
  @PutMapping("/fcm-token")
  public ResponseEntity<CommonResponse<Void>> updateFcmToken(
      @RequestParam String fcmToken) {

    // JWT에서 사용자 정보 추출
    User currentUser = userService.getCurrentUser();

    userService.updateFcmToken(currentUser.getUserId(), fcmToken);
    log.info("FCM token updated successfully for user: {}", currentUser.getUserId());

    return ResponseEntity.ok(CommonResponse.success(null));
  }

    @PutMapping("/profile")
    public ResponseEntity<?> updateUserProfile(@RequestBody ProfileUpdateRequestDto request) {
        userService.updateUserProfile(request);
        return ResponseEntity.ok(CommonResponse.success("프로필이 성공적으로 업데이트되었습니다."));
    }
  /**
   * FCM 토큰 삭제 - 인증된 사용자 본인만
   */
  @Operation(summary = "FCM 토큰 삭제", description = "현재 로그인한 사용자의 FCM 토큰을 삭제합니다 (로그아웃 시 사용)")
  @DeleteMapping("/fcm-token")
  public ResponseEntity<CommonResponse<Void>> deleteFcmToken() {

    // JWT에서 사용자 정보 추출
    User currentUser = userService.getCurrentUser();

    userService.clearFcmToken(currentUser.getUserId());
    log.info("FCM token deleted successfully for user: {}", currentUser.getUserId());

    return ResponseEntity.ok(CommonResponse.success(null));
  }
}