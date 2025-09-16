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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * SSE 및 비동기 처리 설정 - Virtual Thread 기반 최적화
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableAsync
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
     * SSE 이벤트 전송용 Virtual Thread Executor
     */
    @Bean("sseEventExecutor")
    public Executor sseEventExecutor() {
        log.info("SSE Virtual Thread Executor 설정");
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * 알림 처리용 Virtual Thread Executor
     */
    @Bean("notificationExecutor")
    public Executor notificationExecutor() {
        log.info("Notification Virtual Thread Executor 설정");
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}