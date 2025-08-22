package com.example.onlyone.performance;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 성능 테스트 베이스 클래스
 * - 공통 설정 및 유틸리티 메서드 제공
 * - 성능 측정 및 검증 로직 포함
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
public abstract class BasePerformanceTest {

    @Autowired
    protected UserRepository userRepository;
    
    @Autowired
    protected NotificationRepository notificationRepository;
    
    @Autowired
    protected NotificationTypeRepository notificationTypeRepository;

    protected ExecutorService executorService;
    protected List<User> testUsers;
    protected List<NotificationType> testNotificationTypes;

    @BeforeEach
    void setUpPerformanceTest() {

      userRepository.deleteAll();
      notificationRepository.deleteAll();
      userRepository.flush();
      notificationRepository.flush();
      executorService = Executors.newFixedThreadPool(PerformanceTestConfig.ConcurrencyTest.THREAD_COUNT_HEAVY);
      setupTestData();

    }

    /**
     * 테스트 데이터 설정
     */
    protected void setupTestData() {
        // 알림 타입 생성
        testNotificationTypes = createNotificationTypes();
        
        // 테스트 사용자 생성 (중규모 데이터셋)
        testUsers = createTestUsers(PerformanceTestConfig.TestDataVolume.MEDIUM_DATASET_USERS);
    }

    /**
     * 알림 타입 생성
     */
    protected List<NotificationType> createNotificationTypes() {
        List<NotificationType> types = new ArrayList<>();
        types.add(notificationTypeRepository.save(NotificationType.of(Type.CHAT, "채팅 알림: %s")));
        types.add(notificationTypeRepository.save(NotificationType.of(Type.LIKE, "좋아요 알림: %s")));
        types.add(notificationTypeRepository.save(NotificationType.of(Type.SETTLEMENT, "정산 알림: %s")));
        return types;
    }

    /**
     * 테스트 사용자 생성
     */
    protected List<User> createTestUsers(int count) {
        List<User> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User user = User.builder()
                    .kakaoId((long) (100000 + i))
                    .nickname("perf_user_" + i)
                    .fcmToken("fcm_token_" + i)
                    .status(Status.ACTIVE)
                    .build();
            users.add(userRepository.save(user));
        }
        return users;
    }

    /**
     * 테스트 알림 생성
     */
    protected List<AppNotification> createTestNotifications(int count) {
        List<AppNotification> notifications = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User user = testUsers.get(i % testUsers.size());
            NotificationType type = testNotificationTypes.get(i % testNotificationTypes.size());
            
            AppNotification notification = AppNotification.create(user, type, "성능테스트 알림 " + i);
            notifications.add(notificationRepository.save(notification));
        }
        return notifications;
    }

    // ========================
    // 성능 측정 유틸리티
    // ========================

    /**
     * 응답 시간 측정
     */
    protected long measureResponseTime(Runnable operation) {
        Instant start = Instant.now();
        operation.run();
        return Duration.between(start, Instant.now()).toMillis();
    }

    /**
     * 처리량 측정 (TPS)
     */
    protected double measureThroughput(Runnable operation, int iterations, int durationSeconds) {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(iterations);
        AtomicInteger completedOperations = new AtomicInteger(0);

        // 워커 스레드 생성
        for (int i = 0; i < iterations; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    operation.run();
                    completedOperations.incrementAndGet();
                } catch (Exception e) {
                    // 오류는 로그로만 기록
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 측정 시작
        Instant start = Instant.now();
        startLatch.countDown();

        try {
            // 지정된 시간만큼 대기
            endLatch.await(durationSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        return (completedOperations.get() * 1000.0) / elapsedMs; // TPS 계산
    }

    /**
     * 동시성 테스트 실행
     */
    protected PerformanceResult executeConcurrentTest(Runnable operation, int threadCount, int durationSeconds) {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicLong totalOperations = new AtomicLong(0);
        AtomicLong totalResponseTime = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);

        // 워커 스레드 생성
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    startLatch.await();
                    
                    long threadStartTime = System.currentTimeMillis();
                    long endTime = threadStartTime + (durationSeconds * 1000L);
                    
                    while (System.currentTimeMillis() < endTime) {
                        long operationStart = System.currentTimeMillis();
                        try {
                            operation.run();
                            totalOperations.incrementAndGet();
                            totalResponseTime.addAndGet(System.currentTimeMillis() - operationStart);
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 측정 시작
        Instant start = Instant.now();
        startLatch.countDown();

        try {
            endLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        
        return PerformanceResult.builder()
                .totalOperations(totalOperations.get())
                .elapsedTimeMs(elapsedMs)
                .averageResponseTimeMs(totalOperations.get() > 0 ? totalResponseTime.get() / totalOperations.get() : 0)
                .throughputTps((totalOperations.get() * 1000.0) / elapsedMs)
                .errorCount(errors.get())
                .successRate(totalOperations.get() > 0 ? 
                    (double) (totalOperations.get() - errors.get()) / totalOperations.get() : 0.0)
                .build();
    }

    /**
     * 성능 결과 객체
     */
    public static class PerformanceResult {
        private final long totalOperations;
        private final long elapsedTimeMs;
        private final long averageResponseTimeMs;
        private final double throughputTps;
        private final int errorCount;
        private final double successRate;

        private PerformanceResult(Builder builder) {
            this.totalOperations = builder.totalOperations;
            this.elapsedTimeMs = builder.elapsedTimeMs;
            this.averageResponseTimeMs = builder.averageResponseTimeMs;
            this.throughputTps = builder.throughputTps;
            this.errorCount = builder.errorCount;
            this.successRate = builder.successRate;
        }

        public static Builder builder() {
            return new Builder();
        }

        // Getters
        public long getTotalOperations() { return totalOperations; }
        public long getElapsedTimeMs() { return elapsedTimeMs; }
        public long getAverageResponseTimeMs() { return averageResponseTimeMs; }
        public double getThroughputTps() { return throughputTps; }
        public int getErrorCount() { return errorCount; }
        public double getSuccessRate() { return successRate; }

        @Override
        public String toString() {
            return String.format(
                "PerformanceResult{총작업=%d, 소요시간=%dms, 평균응답시간=%dms, 처리량=%.2f TPS, 오류=%d, 성공률=%.2f%%}",
                totalOperations, elapsedTimeMs, averageResponseTimeMs, throughputTps, errorCount, successRate * 100
            );
        }

        public static class Builder {
            private long totalOperations;
            private long elapsedTimeMs;
            private long averageResponseTimeMs;
            private double throughputTps;
            private int errorCount;
            private double successRate;

            public Builder totalOperations(long totalOperations) {
                this.totalOperations = totalOperations;
                return this;
            }

            public Builder elapsedTimeMs(long elapsedTimeMs) {
                this.elapsedTimeMs = elapsedTimeMs;
                return this;
            }

            public Builder averageResponseTimeMs(long averageResponseTimeMs) {
                this.averageResponseTimeMs = averageResponseTimeMs;
                return this;
            }

            public Builder throughputTps(double throughputTps) {
                this.throughputTps = throughputTps;
                return this;
            }

            public Builder errorCount(int errorCount) {
                this.errorCount = errorCount;
                return this;
            }

            public Builder successRate(double successRate) {
                this.successRate = successRate;
                return this;
            }

            public PerformanceResult build() {
                return new PerformanceResult(this);
            }
        }
    }
}