package com.example.onlyone.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * SSE 및 실시간 알림 처리 설정
 * Virtual Thread 기반 + Semaphore 동시실행 제한으로 안정성 확보
 */
@Slf4j
@Configuration
public class SseConfig {

    /**
     * Tomcat Virtual Thread 설정
     */
    @Bean
    public TomcatProtocolHandlerCustomizer<?> protocolHandlerVirtualThreadExecutorCustomizer() {
        return protocolHandler -> {
            log.info("Tomcat Virtual Thread Executor 설정");
            protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        };
    }

    /**
     * Spring MVC 비동기 처리용 Virtual Thread
     */
    @Bean(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    public AsyncTaskExecutor applicationTaskExecutor() {
        log.info("Spring MVC Virtual Thread Executor 설정");
        return new VirtualThreadTaskExecutor("spring-async-");
    }

    /**
     * SSE 이벤트 전송용 Virtual Thread Executor (동시실행 제한)
     * 무제한 Virtual Thread 생성으로 인한 OOM 방지
     */
    @Bean("sseEventExecutor")
    public Executor sseEventExecutor(
            @Value("${app.notification.sse-executor-permits:200}") int permits) {
        ThreadFactory tf = Thread.ofVirtual().name("sse-event-", 0).factory();
        ExecutorService delegate = Executors.newThreadPerTaskExecutor(tf);
        log.info("SSE Bounded Virtual Thread Executor 설정: permits={}", permits);
        return new AsyncConfig.BoundedVtExecutor(delegate, permits, 30);
    }

}