package com.example.onlyone.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 비동기 처리 설정
 * - 기본 @Async 실행자: 플랫폼 스레드 기반 ThreadPoolTaskExecutor
 * - 커스텀 실행자: 특정 작업용 (DB 저장, 메일 발송 등)
 * - SSE/알림 실행자는 SseConfig에서 Virtual Thread로 별도 설정
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
     */
    @Bean(name = "customAsyncExecutor")
    public Executor customAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(32);      // I/O 바운드 작업: CPU 코어 * 8
        executor.setMaxPoolSize(200);      // DB 쓰기 대기 시간 동안 다른 태스크 처리
        executor.setQueueCapacity(5000);   // 버스트 트래픽 흡수
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
     * 무제한 가상 스레드에 세마포어로 동시 실행 상한을 주고,
     * 종료 시 graceful shutdown을 보장하는 래퍼.
     * execute() 호출 스레드를 블로킹하지 않기 위해,
     * 실제 대기는 가상 스레드 안에서 수행한다.
     */
    static class BoundedVtExecutor implements Executor, DisposableBean {
        private final ExecutorService es;
        private final Semaphore sem;
        private final int awaitSec;

        BoundedVtExecutor(ExecutorService es, int permits, int awaitSec) {
            this.es = es;
            this.sem = new Semaphore(permits);
            this.awaitSec = awaitSec;
        }

        @Override
        public void execute(Runnable task) {
            // 제출 스레드는 즉시 반환, 가상 스레드 내에서 상한 대기
            es.execute(() -> {
                sem.acquireUninterruptibly();
                try {
                    task.run();
                } finally {
                    sem.release();
                }
            });
        }

        @Override
        public void destroy() throws Exception {
            es.shutdown(); // 새 작업 받지 않음
            if (!es.awaitTermination(awaitSec, TimeUnit.SECONDS)) {
                es.shutdownNow();
            }
        }
    }
}
