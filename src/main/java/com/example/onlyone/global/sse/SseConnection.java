package com.example.onlyone.global.sse;

import java.time.Duration;
import lombok.Builder;
import lombok.Getter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;

/**
 * SSE 연결 정보 모델
 * JWT 기반 인증으로 userId 저장
 */
@Getter
@Builder
public class SseConnection {

    private final Long userId;
    private final SseEmitter emitter;
    private final LocalDateTime connectionTime;
    
    /**
     * 연결 지속 시간 (밀리초)
     */
    public long getDuration() {
        return Duration.between(connectionTime, LocalDateTime.now()).toMillis();
    }
    
    /**
     * 연결 만료 여부 확인
     * 30분 이상 경과한 연결은 만료된 것으로 간주
     */
    public boolean isExpired() {
        return LocalDateTime.now().minusMinutes(30).isAfter(connectionTime);
    }
}