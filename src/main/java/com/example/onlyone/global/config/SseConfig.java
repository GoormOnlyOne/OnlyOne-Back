package com.example.onlyone.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * SSE 관련 설정
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableAsync
public class SseConfig {

    /**
     * Tomcat에서 Virtual Thread 사용 설정 (5,000+ 동시 사용자 대응)
     */
    @Bean
    public TomcatProtocolHandlerCustomizer<?> protocolHandlerVirtualThreadExecutorCustomizer() {
        return protocolHandler -> {
            log.info("🚀 Tomcat Virtual Thread Executor 활성화 - 무제한 동시성 지원");
            protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        };
    }

    /**
     * Spring MVC 비동기 요청 처리를 위한 Virtual Thread Executor
     */
    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    public AsyncTaskExecutor applicationTaskExecutor() {
        log.info("🚀 Spring Async Task Virtual Thread Executor 설정");
        return new VirtualThreadTaskExecutor("spring-async-vt-");
    }

    /**
     * SSE 이벤트 전송 전용 Virtual Thread Executor (기존 ThreadPool 대체)
     * Virtual Thread로 무제한 동시 SSE 연결 지원
     */
    @Bean("sseEventExecutor")
    public Executor sseEventExecutor() {
        log.info("🚀 SSE Virtual Thread Executor 생성 - 대용량 SSE 연결 지원");
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 알림 처리 전용 Virtual Thread Executor
     */
    @Bean("notificationVirtualThreadExecutor")
    public Executor notificationVirtualThreadExecutor() {
        log.info("🚀 Notification Virtual Thread Executor 생성 - 대용량 알림 처리 지원");
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 데이터베이스 작업 전용 Virtual Thread Executor
     */
    @Bean("dbVirtualThreadExecutor")
    public Executor dbVirtualThreadExecutor() {
        log.info("🚀 Database Virtual Thread Executor 생성 - 비동기 DB 작업 지원");
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * CPU 집약적 작업용 Traditional Thread Pool (M3 8코어 최적화)
     * Virtual Thread는 I/O 바운드 작업에 최적, CPU 작업은 Platform Thread 사용
     */
    @Bean("cpuIntensiveExecutor")
    public Executor cpuIntensiveExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int coreCount = Runtime.getRuntime().availableProcessors(); // M3: 8 cores
        
        executor.setCorePoolSize(coreCount);
        executor.setMaxPoolSize(coreCount * 2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("cpu-intensive-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        
        executor.initialize();
        
        log.info("🚀 CPU Intensive Platform Thread Pool 생성 - 코어: {}, 최대: {}", 
                coreCount, coreCount * 2);
        return executor;
    }
}