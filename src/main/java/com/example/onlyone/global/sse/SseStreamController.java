package com.example.onlyone.global.sse;

import com.example.onlyone.domain.notification.service.SseEmittersService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE(Server-Sent Events) 전용 컨트롤러
 * 실시간 스트림 연결 관리 - Last-Event-ID 지원
 */
@Tag(name = "SSE", description = "실시간 스트림 API")
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
@Slf4j
public class SseStreamController {

  private final SseEmittersService sseEmittersService;

  /**
   * SSE 스트림 연결 - Last-Event-ID 지원
   */
  @Operation(
      summary = "실시간 알림 스트림 연결", 
      description = "Server-Sent Events를 통한 실시간 알림 수신. Last-Event-ID 헤더로 재연결 시 놓친 메시지 복구 가능"
  )
  @GetMapping(value = "/subscribe/{userId}", produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
  public SseEmitter subscribe(
      @PathVariable Long userId,
      @Parameter(description = "마지막으로 받은 이벤트 ID (재연결 시 사용)")
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    
    log.info("SSE stream connection requested: userId={}, lastEventId={}", userId, lastEventId);
    return sseEmittersService.createSseConnection(userId, lastEventId);
  }
}