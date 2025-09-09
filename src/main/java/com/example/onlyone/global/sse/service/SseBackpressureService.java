package com.example.onlyone.global.sse.service;

import com.example.onlyone.global.sse.metrics.SseMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 백프레셔 제어 서비스
 * SSE 연결 및 메시지 전송률 제한
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SseBackpressureService {
    
    private final SseMetrics sseMetrics;
    
    @Value("${sse.max-connections:10000}")
    private int maxConnections;
    
    @Value("${sse.max-messages-per-second:50000}")
    private int maxMessagesPerSecond;
    
    @Value("${sse.user-rate-limit:100}")
    private int userRateLimit;
    
    private final Semaphore connectionSemaphore = new Semaphore(10000);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong messagesInLastSecond = new AtomicLong(0);
    private final Map<Long, RateLimiter> userRateLimiters = new ConcurrentHashMap<>();
    
    /**
     * 연결 허용 여부 확인
     */
    public boolean tryAcquireConnection() {
        if (activeConnections.get() >= maxConnections) {
            log.warn("최대 연결 수 도달: {}/{}", activeConnections.get(), maxConnections);
            sseMetrics.recordConnectionRejected();
            return false;
        }
        
        try {
            if (connectionSemaphore.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                activeConnections.incrementAndGet();
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return false;
    }
    
    /**
     * 연결 해제
     */
    public void releaseConnection() {
        connectionSemaphore.release();
        activeConnections.decrementAndGet();
    }
    
    /**
     * 메시지 전송 허용 여부 확인 (글로벌 + 사용자별)
     */
    public boolean tryAcquireMessagePermit(Long userId) {
        // 글로벌 레이트 체크
        if (messagesInLastSecond.get() >= maxMessagesPerSecond) {
            log.debug("글로벌 메시지 레이트 제한 도달");
            return false;
        }
        
        // 사용자별 레이트 리미터
        RateLimiter userLimiter = userRateLimiters.computeIfAbsent(userId, 
            k -> new RateLimiter(userRateLimit));
        
        if (userLimiter.tryAcquire()) {
            messagesInLastSecond.incrementAndGet();
            return true;
        }
        
        log.debug("사용자 {} 레이트 제한 도달", userId);
        return false;
    }
    
    /**
     * 현재 시스템 부하 상태
     */
    public SystemLoadStatus getSystemLoad() {
        double connectionUsage = (double) activeConnections.get() / maxConnections;
        double messageUsage = (double) messagesInLastSecond.get() / maxMessagesPerSecond;
        
        if (connectionUsage > 0.9 || messageUsage > 0.9) {
            return SystemLoadStatus.HIGH;
        } else if (connectionUsage > 0.7 || messageUsage > 0.7) {
            return SystemLoadStatus.MEDIUM;
        } else {
            return SystemLoadStatus.LOW;
        }
    }
    
    /**
     * 성능 기반 적응형 제한 조정
     */
    public void adjustLimitsBasedOnPerformance(double avgLatency, double errorRate) {
        if (errorRate > 0.05 || avgLatency > 1000) {
            // 성능 저하시 제한 강화
            maxMessagesPerSecond = Math.max(10000, maxMessagesPerSecond - 1000);
            log.info("성능 저하 감지, 메시지 레이트 제한 감소: {}", maxMessagesPerSecond);
        } else if (errorRate < 0.01 && avgLatency < 100) {
            // 성능 양호시 제한 완화
            maxMessagesPerSecond = Math.min(100000, maxMessagesPerSecond + 1000);
            log.info("성능 양호, 메시지 레이트 제한 증가: {}", maxMessagesPerSecond);
        }
    }
    
    public enum SystemLoadStatus {
        LOW, MEDIUM, HIGH
    }
    
    /**
     * 간단한 토큰 버킷 레이트 리미터
     */
    private static class RateLimiter {
        private final int capacity;
        private final long refillPeriodMs = 1000; // 1초
        private int tokens;
        private long lastRefillTime;
        
        public RateLimiter(int capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
        }
        
        public synchronized boolean tryAcquire() {
            refillTokens();
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }
        
        private void refillTokens() {
            long now = System.currentTimeMillis();
            long timeSinceLastRefill = now - lastRefillTime;
            
            if (timeSinceLastRefill >= refillPeriodMs) {
                tokens = capacity;
                lastRefillTime = now;
            }
        }
    }
}