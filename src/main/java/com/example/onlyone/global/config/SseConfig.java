package com.example.onlyone.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * SSE 관련 설정 및 전용 스레드 풀 구성
 */
@Slf4j
@Configuration
@EnableScheduling
public class SseConfig {

    /**
     * SSE 이벤트 전송 전용 고성능 ThreadPool
     * - 실시간 알림 전송에 특화
     * - 높은 우선순위와 큰 처리량 지원
     */
    @Bean("sseEventExecutor")
    public Executor sseEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        executor.setCorePoolSize(50);  // SSE 이벤트 전송 전담
        executor.setMaxPoolSize(150); // 피크 시간 대량 알림 처리
        executor.setQueueCapacity(1000); // 대량 이벤트 버퍼링
        executor.setKeepAliveSeconds(30); // 빠른 스레드 회수
        executor.setThreadNamePrefix("sse-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        
        // 스레드 우선순위 설정 (높은 우선순위)
        executor.setTaskDecorator(runnable -> () -> {
            Thread currentThread = Thread.currentThread();
            int originalPriority = currentThread.getPriority();
            currentThread.setPriority(Thread.MAX_PRIORITY);
            try {
                runnable.run();
            } finally {
                currentThread.setPriority(originalPriority);
            }
        });
        
        executor.initialize();
        
        log.info("SSE Event Executor initialized: core={}, max={}, queue={}", 
                50, 150, 1000);
        
        return executor;
    }

    /**
     * SSE 연결 관리 전용 ThreadPool
     * - 연결 생성, 정리, 하트비트 처리
     * - 안정성 중심의 설정
     */
    @Bean("sseConnectionExecutor")
    public Executor sseConnectionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        executor.setCorePoolSize(15);  // 연결 관리 작업 전담
        executor.setMaxPoolSize(40);   // 연결 급증 시 확장
        executor.setQueueCapacity(300); // 연결 요청 버퍼링
        executor.setKeepAliveSeconds(60); // 안정적인 스레드 유지
        executor.setThreadNamePrefix("sse-connection-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        executor.initialize();
        
        log.info("SSE Connection Executor initialized: core={}, max={}, queue={}", 
                15, 40, 300);
        
        return executor;
    }

    /**
     * SSE 배칭 처리 전용 ThreadPool
     * - 이벤트 배칭 및 집계 처리
     * - 효율적인 일괄 처리 지원
     */
    @Bean("sseBatchExecutor")
    public Executor sseBatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        executor.setCorePoolSize(8);   // 배칭 처리 최적화
        executor.setMaxPoolSize(20);   // 배칭 작업 확장성
        executor.setQueueCapacity(200); // 배치 작업 대기열
        executor.setKeepAliveSeconds(90); // 배치 간격 고려
        executor.setThreadNamePrefix("sse-batch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false); // 배치는 중단 가능
        
        executor.initialize();
        
        log.info("SSE Batch Executor initialized: core={}, max={}, queue={}", 
                8, 20, 200);
        
        return executor;
    }

    /**
     * SSE 정리 작업 전용 ThreadPool
     * - 만료된 연결 정리
     * - 메모리 정리 및 가비지 수집
     * - 백그라운드 유지보수 작업
     */
    @Bean("sseCleanupExecutor")
    public Executor sseCleanupExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        executor.setCorePoolSize(3);   // 정리 작업 전담
        executor.setMaxPoolSize(8);    // 정리 작업 확장
        executor.setQueueCapacity(50); // 정리 작업 대기열
        executor.setKeepAliveSeconds(300); // 긴 유지 시간
        executor.setThreadNamePrefix("sse-cleanup-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        
        // 낮은 우선순위 설정 (백그라운드 작업)
        executor.setTaskDecorator(runnable -> () -> {
            Thread currentThread = Thread.currentThread();
            int originalPriority = currentThread.getPriority();
            currentThread.setPriority(Thread.MIN_PRIORITY);
            try {
                runnable.run();
            } finally {
                currentThread.setPriority(originalPriority);
            }
        });
        
        executor.initialize();
        
        log.info("SSE Cleanup Executor initialized: core={}, max={}, queue={}", 
                3, 8, 50);
        
        return executor;
    }
}