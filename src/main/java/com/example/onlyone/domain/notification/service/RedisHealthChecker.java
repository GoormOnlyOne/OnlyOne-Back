package com.example.onlyone.domain.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 상태 모니터링 및 Circuit Breaker 구현
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisHealthChecker {
    
    private final RedisTemplate<String, Object> redisTemplate;
    
    // Circuit Breaker 상태
    private final AtomicBoolean isCircuitOpen = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    
    // Circuit Breaker 설정
    private static final int FAILURE_THRESHOLD = 3;
    private static final long CIRCUIT_OPEN_DURATION_MS = 30000; // 30초
    private static final long HEALTH_CHECK_TIMEOUT_MS = 1000; // 1초
    
    /**
     * Redis 상태 확인
     */
    public boolean isHealthy() {
        // Circuit이 열려있으면 먼저 확인
        if (isCircuitOpen.get()) {
            if (shouldAttemptReset()) {
                return attemptReset();
            }
            return false;
        }
        
        // 실제 헬스체크 수행
        return performHealthCheck();
    }
    
    /**
     * 실제 Redis 연결 테스트
     */
    private boolean performHealthCheck() {
        try {
            Instant start = Instant.now();
            
            // PING 명령으로 연결 확인
            String pong = redisTemplate.execute((RedisConnection connection) -> {
                return connection.ping();
            });
            
            long responseTime = Duration.between(start, Instant.now()).toMillis();
            
            if ("PONG".equals(pong) && responseTime < HEALTH_CHECK_TIMEOUT_MS) {
                // 성공 시 failure count 리셋
                consecutiveFailures.set(0);
                return true;
            }
            
            log.warn("Redis health check slow response: {}ms", responseTime);
            recordFailure();
            return false;
            
        } catch (Exception e) {
            log.error("Redis health check failed: {}", e.getMessage());
            recordFailure();
            return false;
        }
    }
    
    /**
     * 실패 기록 및 Circuit Breaker 오픈 결정
     */
    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        if (failures >= FAILURE_THRESHOLD) {
            openCircuit();
        }
    }
    
    /**
     * Circuit Breaker 오픈
     */
    private void openCircuit() {
        if (isCircuitOpen.compareAndSet(false, true)) {
            log.error("Redis Circuit Breaker OPENED - Redis appears to be down");
        }
    }
    
    /**
     * Circuit Breaker 리셋 시도 여부 확인
     */
    private boolean shouldAttemptReset() {
        long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
        return timeSinceLastFailure > CIRCUIT_OPEN_DURATION_MS;
    }
    
    /**
     * Circuit Breaker 리셋 시도
     */
    private boolean attemptReset() {
        log.info("Attempting to reset Redis Circuit Breaker");
        
        if (performHealthCheck()) {
            isCircuitOpen.set(false);
            consecutiveFailures.set(0);
            log.info("Redis Circuit Breaker CLOSED - Redis connection restored");
            return true;
        }
        
        return false;
    }
    
    /**
     * 현재 Circuit 상태 조회
     */
    public CircuitStatus getCircuitStatus() {
        return new CircuitStatus(
            isCircuitOpen.get(),
            consecutiveFailures.get(),
            lastFailureTime.get()
        );
    }
    
    /**
     * Circuit 상태 정보
     */
    public record CircuitStatus(
        boolean isOpen,
        int failureCount,
        long lastFailureTime
    ) {}
}