package com.example.onlyone.global.sse.dto;

import com.example.onlyone.domain.user.entity.User;
import java.time.Duration;
import lombok.Builder;
import lombok.Getter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;

/**
 * SSE 연결 정보 모델
 * SSE 연결 상태 관리를 위한 도메인 모델
 */
@Getter
@Builder
public class SseConnection {
    private final Long userId;
    private final SseEmitter emitter;
    private final LocalDateTime connectionTime;
    
    // 캐시된 사용자 정보 - DB 조회 최소화
    private final User cachedUser;
    
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