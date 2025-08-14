package com.example.onlyone.global.sse;

import com.example.onlyone.domain.notification.service.SseEmittersService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE(Server-Sent Events) 전용 컨트롤러
 * JWT 토큰 기반 인증으로 실시간 스트림 연결 관리 - Last-Event-ID 지원
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
   * SSE 스트림 연결 - JWT 토큰 기반 인증 + Last-Event-ID 지원
   */
  @Operation(
      summary = "실시간 알림 스트림 연결", 
      description = "JWT 토큰 기반 인증을 통한 Server-Sent Events 실시간 알림 수신. Last-Event-ID 헤더로 재연결 시 놓친 메시지 복구 가능",
      security = @SecurityRequirement(name = "bearerAuth")
  )
  @GetMapping(value = "/subscribe", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
  public SseEmitter subscribe(
      @Parameter(description = "마지막으로 받은 이벤트 ID (재연결 시 사용)")
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    
    // JWT 토큰에서 사용자 정보 추출
    User currentUser = userService.getCurrentUser();
    Long userId = currentUser.getUserId();
    
    log.info("SSE stream connection requested: userId={}, lastEventId={}", userId, lastEventId);
    return sseEmittersService.createSseConnection(userId, lastEventId);
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
  public SseConnectionStatusDto getConnectionStatus() {
    User currentUser = userService.getCurrentUser();
    Long userId = currentUser.getUserId();
    
    boolean isConnected = sseEmittersService.isUserConnected(userId);
    
    return SseConnectionStatusDto.builder()
        .userId(userId)
        .connected(isConnected)
        .totalConnections(sseEmittersService.getActiveConnectionCount())
        .build();
  }

  /**
   * SSE 연결 상태 DTO
   */
  @lombok.Builder
  @lombok.Getter
  public static class SseConnectionStatusDto {
    private final Long userId;
    private final boolean connected;
    private final int totalConnections;
  }
}