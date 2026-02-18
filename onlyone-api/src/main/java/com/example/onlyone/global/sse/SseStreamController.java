package com.example.onlyone.global.sse;

import com.example.onlyone.domain.user.service.AuthService;
import com.example.onlyone.sse.service.SseConnectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/sse")
@RequiredArgsConstructor
public class SseStreamController {

    private final SseConnectionManager connectionManager;
    private final AuthService authService;

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        Long userId = authService.getCurrentUserId();
        log.info("SSE 연결 요청: userId={}", userId);
        return connectionManager.createConnection(userId);
    }
}
