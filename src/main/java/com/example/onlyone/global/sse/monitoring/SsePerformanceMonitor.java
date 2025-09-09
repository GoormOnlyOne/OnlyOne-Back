package com.example.onlyone.global.sse.monitoring;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 실시간 SSE 성능 모니터링 서비스
 * 디바이스별 세분화 수준에서 실시간 이벤트 추적
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SsePerformanceMonitor {
    
    // 성능 메트릭스
    private final LongAdder totalNotificationsSent = new LongAdder();
    private final LongAdder totalNotificationsFailed = new LongAdder();
    private final LongAdder totalConnectionsEstablished = new LongAdder();
    private final LongAdder totalConnectionsClosed = new LongAdder();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final AtomicLong latencyMeasurements = new AtomicLong();
    
    // 성능 임계값
    private static final double MAX_FAILURE_RATE = 0.05; // 5%
    private static final long MAX_AVERAGE_LATENCY_MS = 1000; // 1초
    private static final int MIN_CONNECTIONS_FOR_ALERT = 100;
    
    /**
     * 알림 전송 성공 이벤트
     */
    public void recordNotificationSent(Long userId, String notificationType, long latencyMs) {
        totalNotificationsSent.increment();
        recordLatency(latencyMs);
        
        log.debug("Notification sent: userId={}, type={}, latency={}ms", 
                userId, notificationType, latencyMs);
    }
    
    /**
     * 알림 전송 실패 이벤트
     */
    public void recordNotificationFailed(Long userId, String notificationType, String reason) {
        totalNotificationsFailed.increment();
        
        log.warn("Notification failed: userId={}, type={}, reason={}", 
                userId, notificationType, reason);
    }
    
    /**
     * SSE 연결 성공 이벤트
     */
    public void recordConnectionEstablished(Long userId) {
        totalConnectionsEstablished.increment();
        log.debug("SSE connection established: userId={}", userId);
    }
    
    /**
     * SSE 연결 종료 이벤트
     */
    public void recordConnectionClosed(Long userId, String reason) {
        totalConnectionsClosed.increment();
        log.debug("SSE connection closed: userId={}, reason={}", userId, reason);
    }
    
    private void recordLatency(long latencyMs) {
        totalLatencyMs.addAndGet(latencyMs);
        latencyMeasurements.incrementAndGet();
    }
    
    /**
     * 실시간 메트릭스 수집 및 알림
     */
    @Scheduled(fixedRate = 10000) // 10초마다
    public void collectAndReportMetrics() {
        long sent = totalNotificationsSent.sumThenReset();
        long failed = totalNotificationsFailed.sumThenReset();
        long established = totalConnectionsEstablished.sumThenReset();
        long closed = totalConnectionsClosed.sumThenReset();
        long totalLatency = totalLatencyMs.getAndSet(0);
        long measurements = latencyMeasurements.getAndSet(0);
        
        // 메트릭스 계산
        double failureRate = (sent + failed) > 0 ? (double) failed / (sent + failed) : 0.0;
        double averageLatency = measurements > 0 ? (double) totalLatency / measurements : 0.0;
        long activeConnections = established - closed;
        
        // 성능 로깅
        log.info("SSE Metrics [10s] - Sent: {}, Failed: {}, Failure Rate: {:.2%}, " +
                "Avg Latency: {:.0f}ms, Connections: +{} -{} (net: +{})",
                sent, failed, failureRate, averageLatency, 
                established, closed, activeConnections);
        
        // 임계값 기반 알림
        checkThresholds(failureRate, averageLatency, activeConnections);
    }
    
    private void checkThresholds(double failureRate, double averageLatency, long activeConnections) {
        // 높은 실패율 감지
        if (failureRate > MAX_FAILURE_RATE && activeConnections > MIN_CONNECTIONS_FOR_ALERT) {
            log.error("🚨 HIGH FAILURE RATE ALERT: {:.2%} (threshold: {:.2%})", 
                    failureRate, MAX_FAILURE_RATE);
            triggerFailureRateAlert(failureRate);
        }
        
        // 높은 지연시간 감지
        if (averageLatency > MAX_AVERAGE_LATENCY_MS && activeConnections > MIN_CONNECTIONS_FOR_ALERT) {
            log.error("🚨 HIGH LATENCY ALERT: {:.0f}ms (threshold: {}ms)", 
                    averageLatency, MAX_AVERAGE_LATENCY_MS);
            triggerLatencyAlert(averageLatency);
        }
        
        // 연결 급증 감지
        if (activeConnections > 1000) {
            log.warn("⚠️ HIGH CONNECTION VOLUME: {} new connections in 10s", activeConnections);
        }
    }
    
    private void triggerFailureRateAlert(double failureRate) {
        // 실패율 알림 전송 (실제 구현에서는 Slack, PagerDuty 등으로 알림)
        log.error("Triggering failure rate alert - Current rate: {:.2%}", failureRate);
    }
    
    private void triggerLatencyAlert(double averageLatency) {
        // 지연시간 알림 전송
        log.error("Triggering latency alert - Current latency: {:.0f}ms", averageLatency);
    }
    
    /**
     * 현재 성능 상태 조회
     */
    public PerformanceSnapshot getCurrentPerformance() {
        long sent = totalNotificationsSent.sum();
        long failed = totalNotificationsFailed.sum();
        long totalLatency = totalLatencyMs.get();
        long measurements = latencyMeasurements.get();
        
        double failureRate = (sent + failed) > 0 ? (double) failed / (sent + failed) : 0.0;
        double averageLatency = measurements > 0 ? (double) totalLatency / measurements : 0.0;
        
        return new PerformanceSnapshot(
            sent, failed, failureRate, averageLatency, LocalDateTime.now()
        );
    }
    
    public record PerformanceSnapshot(
        long totalSent,
        long totalFailed, 
        double failureRate,
        double averageLatencyMs,
        LocalDateTime timestamp
    ) {}
}