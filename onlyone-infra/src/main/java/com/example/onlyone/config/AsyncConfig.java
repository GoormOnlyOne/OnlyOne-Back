package com.example.onlyone.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.*;

/**
 * 비동기 처리 설정
 * - 기본 @Async 실행자: 플랫폼 스레드 기반 ThreadPoolTaskExecutor
 * - 커스텀 실행자: 특정 작업용 (백그라운드, 정산 등)
 * - SSE/알림 실행자는 SseConfig에서 Virtual Thread로 별도 설정
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

    /**
     * 가상 스레드 기반 정산 실행기
     * - 동시 실행 상한(permits)으로 DB/Redis 백프레셔
     * - 종료 시 작업 완료 대기(awaitSec)
     * - Redis 커넥션 팩토리보다 먼저 내려가도록 설정(@DependsOn)
     */
    @Bean(name = "settlementExecutor")
    @DependsOn("redisConnectionFactory")
    public Executor settlementExecutor(
            @Value("${app.settlement.concurrency:32}") int permits,
            @Value("${app.settlement.shutdown.await-seconds:60}") int awaitSec
    ) {
        // 스레드 이름 prefix 적용된 가상 스레드 팩토리
        ThreadFactory tf = Thread.ofVirtual().name("settlement-", 0).factory();
        ExecutorService delegate = Executors.newThreadPerTaskExecutor(tf);
        return new BoundedVtExecutor(delegate, permits, awaitSec);
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
