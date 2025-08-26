package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.service.RedisHealthChecker;
import com.example.onlyone.domain.notification.service.RedisHealthChecker.CircuitStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * 알림 시스템 헬스체크 컨트롤러
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/notifications/health")
@RequiredArgsConstructor
@Tag(name = "Notification Health", description = "알림 시스템 상태 확인 API")
public class NotificationHealthController {
    
    private final RedisHealthChecker redisHealthChecker;
    
    /**
     * Redis 및 알림 시스템 전체 상태 확인
     */
    @GetMapping
    @Operation(summary = "알림 시스템 상태 확인", description = "Redis 및 알림 시스템 전체 상태를 확인합니다")
    public ResponseEntity<Map<String, Object>> checkHealth() {
        Map<String, Object> healthStatus = new HashMap<>();
        
        // Redis 상태 확인
        boolean redisHealthy = redisHealthChecker.isHealthy();
        CircuitStatus circuitStatus = redisHealthChecker.getCircuitStatus();
        
        healthStatus.put("status", redisHealthy ? "UP" : "DOWN");
        healthStatus.put("timestamp", LocalDateTime.now());
        
        // Redis 상세 정보
        Map<String, Object> redisInfo = new HashMap<>();
        redisInfo.put("healthy", redisHealthy);
        redisInfo.put("circuitOpen", circuitStatus.isOpen());
        redisInfo.put("failureCount", circuitStatus.failureCount());
        
        if (circuitStatus.lastFailureTime() > 0) {
            LocalDateTime lastFailure = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(circuitStatus.lastFailureTime()),
                ZoneId.systemDefault()
            );
            redisInfo.put("lastFailureTime", lastFailure);
        }
        
        healthStatus.put("redis", redisInfo);
        
        // 폴백 모드 정보
        if (!redisHealthy) {
            Map<String, String> fallbackInfo = new HashMap<>();
            fallbackInfo.put("mode", "FCM_FALLBACK");
            fallbackInfo.put("message", "SSE is unavailable due to Redis issues. Using FCM as fallback.");
            healthStatus.put("fallback", fallbackInfo);
        }
        
        // HTTP 상태 코드 결정
        HttpStatus httpStatus = redisHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        
        log.info("Health check performed: redisHealthy={}, circuitOpen={}", 
                 redisHealthy, circuitStatus.isOpen());
        
        return ResponseEntity.status(httpStatus).body(healthStatus);
    }
    
    /**
     * Redis Circuit Breaker 상태만 확인
     */
    @GetMapping("/circuit")
    @Operation(summary = "Circuit Breaker 상태", description = "Redis Circuit Breaker 상태를 확인합니다")
    public ResponseEntity<CircuitStatus> getCircuitStatus() {
        CircuitStatus status = redisHealthChecker.getCircuitStatus();
        return ResponseEntity.ok(status);
    }
}