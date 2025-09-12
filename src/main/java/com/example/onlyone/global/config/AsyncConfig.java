package com.example.onlyone.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 처리 설정 - 최적화된 플랫폼 스레드 기반
 * 안정적인 ThreadPoolTaskExecutor 사용
 * DAU 35K+ 지원을 위한 최적화
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /**
     * 기본 비동기 실행자 - 최적화된 ThreadPool
     * 안정적이고 예측 가능한 성능 제공
     */
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        executor.setCorePoolSize(30);
        executor.setMaxPoolSize(80);
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(45);
        executor.setThreadNamePrefix("async-optimized-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        executor.initialize();
        
        log.info("Optimized async executor initialized: core={}, max={}, queue={}", 
                30, 80, 500);
        
        return executor;
    }


    /**
     * 백그라운드 작업용 ThreadPool
     * - 로그 처리, 통계, 정리 작업 등
     * - 효율적인 백그라운드 처리
     */
    @Bean("backgroundExecutor")
    public Executor backgroundExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(25);
        executor.setQueueCapacity(300);
        executor.setKeepAliveSeconds(150);
        executor.setThreadNamePrefix("bg-task-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        
        executor.initialize();
        
        log.info("Background executor initialized: core={}, max={}, queue={}",
                8, 25, 300);
        
        return executor;
    }
}