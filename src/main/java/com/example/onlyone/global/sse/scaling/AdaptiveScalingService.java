package com.example.onlyone.global.sse.scaling;

import com.example.onlyone.global.sse.monitoring.SsePerformanceMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 적응형 스케일링 서비스
 * 실시간 성능 메트릭스를 기반으로 시스템 리소스 자동 조정
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdaptiveScalingService {
    
    private final SsePerformanceMonitor performanceMonitor;
    
    @Value("${sse.scaling.enabled:true}")
    private boolean scalingEnabled;
    
    @Value("${sse.batch.size.min:100}")
    private int minBatchSize;
    
    @Value("${sse.batch.size.max:2000}")
    private int maxBatchSize;
    
    @Value("${sse.thread.pool.min:4}")
    private int minThreadPoolSize;
    
    @Value("${sse.thread.pool.max:20}")
    private int maxThreadPoolSize;
    
    // 적응형 설정값들
    private final AtomicInteger currentBatchSize = new AtomicInteger(500);
    private final AtomicInteger currentThreadPoolSize = new AtomicInteger(8);
    private final AtomicInteger currentRateLimit = new AtomicInteger(10000);
    
    // 성능 히스토리 (간단한 슬라이딩 윈도우)
    private final double[] recentFailureRates = new double[5];
    private final double[] recentLatencies = new double[5];
    private int historyIndex = 0;
    
    /**
     * 실시간 성능 기반 자동 스케일링
     */
    @Scheduled(fixedRate = 30000) // 30초마다 조정
    public void adjustSystemParameters() {
        if (!scalingEnabled) {
            return;
        }
        
        SsePerformanceMonitor.PerformanceSnapshot snapshot = performanceMonitor.getCurrentPerformance();
        
        // 성능 히스토리 업데이트
        updatePerformanceHistory(snapshot.failureRate(), snapshot.averageLatencyMs());
        
        // 적응형 조정 실행
        adjustBatchSize(snapshot);
        adjustRateLimit(snapshot);
        
        log.info("Adaptive scaling completed - Batch: {}, Rate limit: {}", 
                currentBatchSize.get(), currentRateLimit.get());
    }
    
    private void updatePerformanceHistory(double failureRate, double latency) {
        recentFailureRates[historyIndex] = failureRate;
        recentLatencies[historyIndex] = latency;
        historyIndex = (historyIndex + 1) % recentFailureRates.length;
    }
    
    /**
     * 성능 기반 배치 크기 조정
     */
    private void adjustBatchSize(SsePerformanceMonitor.PerformanceSnapshot snapshot) {
        double avgFailureRate = calculateAverage(recentFailureRates);
        double avgLatency = calculateAverage(recentLatencies);
        
        int newBatchSize = currentBatchSize.get();
        
        // 성능이 좋으면 배치 크기 증가
        if (avgFailureRate < 0.02 && avgLatency < 500) {
            newBatchSize = Math.min(maxBatchSize, newBatchSize + 100);
            log.debug("Performance good, increasing batch size to {}", newBatchSize);
        }
        // 성능이 나쁘면 배치 크기 감소
        else if (avgFailureRate > 0.05 || avgLatency > 1500) {
            newBatchSize = Math.max(minBatchSize, newBatchSize - 50);
            log.debug("Performance degraded, decreasing batch size to {}", newBatchSize);
        }
        
        currentBatchSize.set(newBatchSize);
    }
    
    /**
     * 부하 기반 레이트 리미트 조정
     */
    private void adjustRateLimit(SsePerformanceMonitor.PerformanceSnapshot snapshot) {
        double avgFailureRate = calculateAverage(recentFailureRates);
        double avgLatency = calculateAverage(recentLatencies);
        
        int newRateLimit = currentRateLimit.get();
        
        // 시스템이 안정적이면 처리량 증가
        if (avgFailureRate < 0.01 && avgLatency < 300) {
            newRateLimit = Math.min(50000, newRateLimit + 1000);
            log.debug("System stable, increasing rate limit to {}", newRateLimit);
        }
        // 시스템에 부하가 있으면 처리량 감소
        else if (avgFailureRate > 0.03 || avgLatency > 1000) {
            newRateLimit = Math.max(5000, newRateLimit - 1000);
            log.debug("System under load, decreasing rate limit to {}", newRateLimit);
        }
        
        currentRateLimit.set(newRateLimit);
    }
    
    private double calculateAverage(double[] values) {
        double sum = 0;
        int count = 0;
        for (double value : values) {
            if (value > 0) {
                sum += value;
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }
    
    /**
     * Circuit Breaker 패턴: 시스템 보호 모드
     */
    public boolean isSystemInProtectionMode() {
        double avgFailureRate = calculateAverage(recentFailureRates);
        double avgLatency = calculateAverage(recentLatencies);
        
        return avgFailureRate > 0.10 || avgLatency > 2000;
    }
    
    /**
     * 현재 적응형 설정값 조회
     */
    public AdaptiveSettings getCurrentSettings() {
        return new AdaptiveSettings(
            currentBatchSize.get(),
            currentThreadPoolSize.get(),
            currentRateLimit.get(),
            isSystemInProtectionMode()
        );
    }
    
    /**
     * 긴급 상황시 수동 조정
     */
    public void emergencyScaleDown() {
        currentBatchSize.set(minBatchSize);
        currentRateLimit.set(5000);
        log.warn("Emergency scale down activated");
    }
    
    public void emergencyScaleUp() {
        currentBatchSize.set(maxBatchSize);
        currentRateLimit.set(50000);
        log.warn("Emergency scale up activated");
    }
    
    public record AdaptiveSettings(
        int batchSize,
        int threadPoolSize,
        int rateLimit,
        boolean protectionMode
    ) {}
}