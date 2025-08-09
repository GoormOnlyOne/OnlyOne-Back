package com.example.onlyone.domain.user.controller;

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

  /**
   * FCM 토큰 상태 조회
   */
  @Operation(summary = "FCM 토큰 상태 조회", description = "사용자의 FCM 토큰 등록 여부를 확인합니다")
  @GetMapping("/{userId}/fcm-token/status")
  public ResponseEntity<CommonResponse<Map<String, Object>>> getFcmTokenStatus(@PathVariable Long userId) {
    
    User user = userService.getMemberById(userId);
    
    Map<String, Object> status = new HashMap<>();
    status.put("hasToken", user.hasFcmToken());
    status.put("tokenLength", user.getFcmToken() != null ? user.getFcmToken().length() : 0);
    
    log.debug("FCM token status checked for user: {}, hasToken: {}", userId, user.hasFcmToken());
    
    return ResponseEntity.ok(CommonResponse.success(status));
  }

  /**
   * FCM 토큰 등록/업데이트
   */
  @Operation(summary = "FCM 토큰 등록", description = "사용자의 FCM 토큰을 등록하거나 업데이트합니다")
  @PutMapping("/{userId}/fcm-token")
  @Transactional
  public ResponseEntity<CommonResponse<Void>> updateFcmToken(
      @PathVariable Long userId,
      @RequestParam String fcmToken) {

    userService.updateFcmToken(userId, fcmToken);
    log.info("FCM token updated successfully for user: {}", userId);

    return ResponseEntity.ok(CommonResponse.success(null));
  }

  /**
   * FCM 토큰 삭제
   */
  @Operation(summary = "FCM 토큰 삭제", description = "사용자의 FCM 토큰을 삭제합니다 (로그아웃 시 사용)")
  @DeleteMapping("/{userId}/fcm-token")
  @Transactional
  public ResponseEntity<CommonResponse<Void>> deleteFcmToken(@PathVariable Long userId) {

    userService.clearFcmToken(userId);
    log.info("FCM token deleted successfully for user: {}", userId);

    return ResponseEntity.ok(CommonResponse.success(null));
  }
}