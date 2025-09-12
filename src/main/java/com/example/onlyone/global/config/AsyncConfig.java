package com.example.onlyone.global.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableAsync
public class AsyncConfig {

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
    static final class BoundedVtExecutor implements Executor, DisposableBean {
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
