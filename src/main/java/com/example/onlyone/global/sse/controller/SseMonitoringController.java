package com.example.onlyone.global.sse.controller;

import com.example.onlyone.global.common.CommonResponse;
import com.example.onlyone.global.sse.monitoring.SsePerformanceMonitor;
import com.example.onlyone.global.sse.scaling.AdaptiveScalingService;
import com.example.onlyone.global.sse.SseEmittersService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * SSE 시스템 모니터링 API
 * 실시간 성능 메트릭스 및 시스템 상태 조회
 */
@Tag(name = "SSE 모니터링", description = "SSE 시스템 성능 모니터링")
@RestController
@RequestMapping("/api/sse/monitoring")
@RequiredArgsConstructor
public class SseMonitoringController {
    
    private final SsePerformanceMonitor performanceMonitor;
    private final AdaptiveScalingService scalingService;
    private final SseEmittersService sseEmittersService;
    
    @Operation(summary = "현재 성능 메트릭스", description = "실시간 성능 데이터")
    @GetMapping("/metrics")
    public ResponseEntity<CommonResponse<Map<String, Object>>> getCurrentMetrics() {
        SsePerformanceMonitor.PerformanceSnapshot performance = 
            performanceMonitor.getCurrentPerformance();
        
        Map<String, Object> metrics = Map.of(
            "performance", performance,
            "connections", Map.of(
                "active", sseEmittersService.getActiveConnectionCount(),
                "activeUserIds", sseEmittersService.getActiveUserIds().size()
            ),
            "scaling", scalingService.getCurrentSettings(),
            "system", Map.of(
                "protectionMode", scalingService.isSystemInProtectionMode(),
                "timestamp", System.currentTimeMillis()
            )
        );
        
        return ResponseEntity.ok(CommonResponse.success(metrics));
    }
    
    @Operation(summary = "적응형 스케일링 상태", description = "자동 스케일링 현황")
    @GetMapping("/scaling")
    public ResponseEntity<CommonResponse<AdaptiveScalingService.AdaptiveSettings>> getScalingStatus() {
        AdaptiveScalingService.AdaptiveSettings settings = scalingService.getCurrentSettings();
        return ResponseEntity.ok(CommonResponse.success(settings));
    }
    
    @Operation(summary = "긴급 스케일 다운", description = "시스템 보호를 위한 긴급 성능 제한")
    @PostMapping("/emergency/scale-down")
    public ResponseEntity<CommonResponse<String>> emergencyScaleDown() {
        scalingService.emergencyScaleDown();
        return ResponseEntity.ok(CommonResponse.success("Emergency scale down activated"));
    }
    
    @Operation(summary = "긴급 스케일 업", description = "대용량 트래픽 처리를 위한 긴급 성능 증대")
    @PostMapping("/emergency/scale-up")
    public ResponseEntity<CommonResponse<String>> emergencyScaleUp() {
        scalingService.emergencyScaleUp();
        return ResponseEntity.ok(CommonResponse.success("Emergency scale up activated"));
    }
    
    @Operation(summary = "연결 상태 상세 조회", description = "활성 SSE 연결들의 상세 정보")
    @GetMapping("/connections")
    public ResponseEntity<CommonResponse<Map<String, Object>>> getConnectionDetails() {
        Map<String, Object> connections = Map.of(
            "totalActive", sseEmittersService.getActiveConnectionCount(),
            "activeUsers", sseEmittersService.getActiveUserIds(),
            "details", sseEmittersService.getActiveUserIds().stream()
                .limit(10) // 최대 10개만 상세 정보 제공
                .map(userId -> Map.of(
                    "userId", userId,
                    "lastConnected", sseEmittersService.getLastConnectedTime(userId),
                    "duration", sseEmittersService.getConnectionDuration(userId)
                ))
                .toList()
        );
        
        return ResponseEntity.ok(CommonResponse.success(connections));
    }
    
    @Operation(summary = "시스템 상태 체크", description = "전체 SSE 시스템의 건강 상태")
    @GetMapping("/health")
    public ResponseEntity<CommonResponse<Map<String, Object>>> getSystemHealth() {
        SsePerformanceMonitor.PerformanceSnapshot performance = 
            performanceMonitor.getCurrentPerformance();
        
        boolean isHealthy = performance.failureRate() < 0.05 && 
                           performance.averageLatencyMs() < 1000 && 
                           !scalingService.isSystemInProtectionMode();
        
        Map<String, Object> health = Map.of(
            "status", isHealthy ? "HEALTHY" : "DEGRADED",
            "failureRate", performance.failureRate(),
            "averageLatency", performance.averageLatencyMs(),
            "protectionMode", scalingService.isSystemInProtectionMode(),
            "connections", sseEmittersService.getActiveConnectionCount(),
            "timestamp", System.currentTimeMillis()
        );
        
        return ResponseEntity.ok(CommonResponse.success(health));
    }
}