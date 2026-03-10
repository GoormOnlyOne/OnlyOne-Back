package com.example.onlyone.global.config;

import com.example.onlyone.sse.service.SseConnectionManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

/**
 * Gauge(현재 상태) 계열 커스텀 메트릭 등록.
 * Counter/Timer(이벤트 기반) 메트릭은 {@link MonitoringAspect}에서 AOP로 처리.
 */
@Configuration
@RequiredArgsConstructor
public class CustomMetricsConfig {

    private final MeterRegistry registry;
    private final SseConnectionManager sseConnectionManager;

    @PostConstruct
    public void registerGauges() {
        // SSE 활성 연결 수 (현재 상태)
        Gauge.builder("sse.connections.active", sseConnectionManager, SseConnectionManager::getActiveConnectionCount)
                .description("Current active SSE connections")
                .register(registry);

        Gauge.builder("sse.connections.max", sseConnectionManager, SseConnectionManager::getMaxConnections)
                .description("Max allowed SSE connections")
                .register(registry);
    }
}
