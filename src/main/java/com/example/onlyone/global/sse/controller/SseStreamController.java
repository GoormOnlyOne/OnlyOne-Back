package com.example.onlyone.global.sse.controller;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.sse.dto.SseConnectionStatusResponseDto;
import com.example.onlyone.global.common.CommonResponse;
import com.example.onlyone.global.sse.service.SseEmittersService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE(Server-Sent Events) 전용 컨트롤러
 * 쿠키 또는 헤더 기반 JWT 인증으로 실시간 스트림 연결 관리 - Last-Event-ID 지원
 */
@Tag(name = "SSE", description = "실시간 스트림 API")
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
@Slf4j
public class SseStreamController {

  private final SseEmittersService sseEmittersService;
  private final UserService userService;

  /**
   * SSE 스트림 연결 - JWT 기반 인증 (쿠키/헤더) + Last-Event-ID 지원
   */
  @Operation(
      summary = "실시간 알림 스트림 연결", 
      description = "JWT 기반 인증을 통한 Server-Sent Events 실시간 알림 수신. 토큰은 Authorization 헤더 또는 access_token 쿠키로 전달 가능. Last-Event-ID 헤더로 재연결 시 놓친 메시지 복구 가능",
      security = @SecurityRequirement(name = "bearerAuth")
  )
  @GetMapping(value = "/subscribe", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
  public SseEmitter subscribe(
      @Parameter(description = "마지막으로 받은 이벤트 ID (재연결 시 사용)")
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
      HttpServletRequest request) {
    
    // JWT에서 kakaoId 추출 (Security Context에서)
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Long kakaoId = Long.valueOf(authentication.getName());
    
    // SSE는 kakaoId를 userId로 사용 (JWT 기반)
    Long userId = kakaoId;
    
    log.info("SSE stream connection requested: userId={}, lastEventId={}, NO DB QUERY", userId, lastEventId);
    
    // SSE 연결 생성 - userId만 사용 (DB 조회 없음)
    SseEmitter emitter = sseEmittersService.createSseConnectionByUserId(userId, lastEventId);
    
    // 이 시점에서 DB 연결은 이미 반환되고 SSE만 유지됨
    return emitter;
  }

  /**
   * 연결 상태 확인
   */
  @Operation(
      summary = "SSE 연결 상태 확인",
      description = "현재 사용자의 SSE 연결 상태를 확인합니다.",
      security = @SecurityRequirement(name = "bearerAuth")
  )
  @GetMapping("/status")
  public ResponseEntity<CommonResponse<SseConnectionStatusResponseDto>> getConnectionStatus(HttpServletRequest request) {
    // JWT에서 kakaoId 추출 (Security Context에서)
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    Long kakaoId = Long.valueOf(authentication.getName());
    
    // SSE는 kakaoId를 userId로 사용 (JWT 기반)
    Long userId = kakaoId;
    boolean isConnected = sseEmittersService.isUserConnected(userId);
    
    SseConnectionStatusResponseDto response = SseConnectionStatusResponseDto.builder()
        .userId(userId)
        .connected(isConnected)
        .totalConnections(sseEmittersService.getActiveConnectionCount())
        .lastConnectedAt(sseEmittersService.getLastConnectedTime(userId))
        .connectionDuration(sseEmittersService.getConnectionDuration(userId))
        .build();
        
    return ResponseEntity.ok(CommonResponse.success(response));
  }

}