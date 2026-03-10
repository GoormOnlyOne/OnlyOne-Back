package com.example.onlyone.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 처리 설정
 * - 기본 @Async 실행자: 플랫폼 스레드 기반 ThreadPoolTaskExecutor
 * - 커스텀 실행자: 특정 작업용 (DB 저장, 메일 발송 등)
 * - SSE 이벤트 전송: Virtual Thread (무제한, JDK 관리)
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    private static final int CORE_POOL_SIZE = 30;
    private static final int MAX_POOL_SIZE = 80;
    private static final int QUEUE_CAPACITY = 500;

    /**
     * 기본 비동기 실행자 - 최적화된 ThreadPool
     * 안정적이고 예측 가능한 성능 제공
     */
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(45);
        executor.setThreadNamePrefix("async-optimized-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();

        log.info("Optimized async executor initialized: core={}, max={}, queue={}",
                CORE_POOL_SIZE, MAX_POOL_SIZE, QUEUE_CAPACITY);

        return executor;
    }

    /**
     * 커스텀 비동기 처리 전용 스레드풀 (DB 저장, 메일 발송 등)
     * CallerRunsPolicy: 큐 포화 시 호출 스레드가 직접 실행 → 자연스러운 백프레셔
     */
    @Bean(name = "customAsyncExecutor")
    public Executor customAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(64);
        executor.setMaxPoolSize(200);
        executor.setQueueCapacity(10000);
        executor.setThreadNamePrefix("Async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();
        return executor;
    }

    /**
     * 댓글 카운트 비동기 갱신 전용 — 소규모 풀로 커넥션 소비 제한
     * CallerRunsPolicy: 큐 포화 시 호출 스레드가 실행 → 자연 백프레셔
     */
    @Bean(name = "commentCountExecutor")
    public Executor commentCountExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("comment-cnt-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * SSE 이벤트 전송용 Platform Thread Pool.
     * SseEmitter.send() 내부 synchronized(sendMutex) + blocking I/O로
     * Virtual Thread에서 carrier thread pinning이 발생하므로 platform thread 사용.
     */
    @Bean(name = "sseEventExecutor")
    public Executor sseEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(64);
        executor.setMaxPoolSize(256);
        executor.setQueueCapacity(5000);
        executor.setThreadNamePrefix("sse-event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        log.info("SSE Platform Thread Executor initialized: core=64, max=256, queue=5000");
        return executor;
    }
}
