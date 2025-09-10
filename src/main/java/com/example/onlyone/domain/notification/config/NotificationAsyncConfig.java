package com.example.onlyone.domain.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Virtual Threads 기반 알림 시스템 비동기 설정
 * 빅테크 패턴별 전용 Executor 제공
 */
@Configuration
@EnableAsync
@Slf4j
public class NotificationAsyncConfig {

    /**
     * Netflix RENO 패턴: 고우선순위 알림 전용 Virtual Thread Executor
     * 초당 150,000개 이벤트 처리 목표
     */
    @Bean("highPriorityNotificationExecutor")
    public Executor highPriorityNotificationExecutor() {
        if (isVirtualThreadsSupported()) {
            log.info("Creating Virtual Thread executor for high priority notifications");
            return Executors.newVirtualThreadPerTaskExecutor();
        } else {
            log.warn("Virtual Threads not supported, falling back to regular ThreadPool");
            return createFallbackExecutor("HighPriority", 50, 200);
        }
    }

    /**
     * Netflix RENO 패턴: 일반우선순위 알림 전용 Virtual Thread Executor
     */
    @Bean("normalPriorityNotificationExecutor")
    public Executor normalPriorityNotificationExecutor() {
        if (isVirtualThreadsSupported()) {
            log.info("Creating Virtual Thread executor for normal priority notifications");
            return Executors.newVirtualThreadPerTaskExecutor();
        } else {
            log.warn("Virtual Threads not supported, falling back to regular ThreadPool");
            return createFallbackExecutor("NormalPriority", 20, 100);
        }
    }

    /**
     * Uber RAMEN 패턴: 하이브리드 전달 전용 Virtual Thread Executor
     * Push/Pull 전환 로직 처리
     */
    @Bean("hybridDeliveryExecutor")
    public Executor hybridDeliveryExecutor() {
        if (isVirtualThreadsSupported()) {
            log.info("Creating Virtual Thread executor for hybrid delivery");
            return Executors.newVirtualThreadPerTaskExecutor();
        } else {
            log.warn("Virtual Threads not supported, falling back to regular ThreadPool");
            return createFallbackExecutor("HybridDelivery", 30, 150);
        }
    }

    /**
     * Slack 패턴: 분산 트레이싱 전용 Virtual Thread Executor
     * 트레이스 수집 및 분석 처리
     */
    @Bean("tracingExecutor")
    public Executor tracingExecutor() {
        if (isVirtualThreadsSupported()) {
            log.info("Creating Virtual Thread executor for tracing");
            return Executors.newVirtualThreadPerTaskExecutor();
        } else {
            log.warn("Virtual Threads not supported, falling back to regular ThreadPool");
            return createFallbackExecutor("Tracing", 10, 50);
        }
    }

    /**
     * Spotify 패턴: 메트릭 수집 전용 Virtual Thread Executor
     * 성능 지표 수집 및 모니터링
     */
    @Bean("metricsCollectionExecutor")
    public Executor metricsCollectionExecutor() {
        if (isVirtualThreadsSupported()) {
            log.info("Creating Virtual Thread executor for metrics collection");
            return Executors.newVirtualThreadPerTaskExecutor();
        } else {
            log.warn("Virtual Threads not supported, falling back to regular ThreadPool");
            return createFallbackExecutor("MetricsCollection", 5, 25);
        }
    }

    /**
     * 배치 처리 전용 Virtual Thread Executor
     * 대량 알림 일괄 처리
     */
    @Bean("batchProcessingExecutor")
    public Executor batchProcessingExecutor() {
        if (isVirtualThreadsSupported()) {
            log.info("Creating Virtual Thread executor for batch processing");
            return Executors.newVirtualThreadPerTaskExecutor();
        } else {
            log.warn("Virtual Threads not supported, falling back to regular ThreadPool");
            return createFallbackExecutor("BatchProcessing", 15, 75);
        }
    }

    /**
     * Virtual Threads 지원 여부 확인
     */
    private boolean isVirtualThreadsSupported() {
        try {
            // Java 21+ Virtual Threads 지원 확인
            Class.forName("java.lang.Thread$Builder");
            int javaVersion = Runtime.version().feature();
            boolean supported = javaVersion >= 21;
            
            log.info("Java version: {}, Virtual Threads supported: {}", javaVersion, supported);
            return supported;
            
        } catch (ClassNotFoundException e) {
            log.info("Virtual Threads not available in current Java version");
            return false;
        }
    }

    /**
     * Virtual Threads 미지원시 폴백용 ThreadPoolTaskExecutor 생성
     */
    private ThreadPoolTaskExecutor createFallbackExecutor(String name, int coreSize, int maxSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("Notification-" + name + "-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        
        log.info("Created fallback ThreadPool executor: {}, core={}, max={}", 
                name, coreSize, maxSize);
        
        return executor;
    }
}