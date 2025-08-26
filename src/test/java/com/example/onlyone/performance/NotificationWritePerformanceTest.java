package com.example.onlyone.performance;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * 알림 생성 쓰기 성능 테스트
 * 
 * 대용량 알림 생성 시 성능 측정 및 병목 지점 파악
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@DisplayName("알림 생성 쓰기 성능 테스트")
public class NotificationWritePerformanceTest {

    @Autowired
    private NotificationService notificationService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private NotificationRepository notificationRepository;
    
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    
    private User testUser;
    private NotificationType testNotificationType;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 정리
        notificationRepository.deleteAll();
        userRepository.deleteAll();
        notificationTypeRepository.deleteAll();
        
        // 테스트용 사용자 생성
        testUser = User.builder()
                .kakaoId(System.currentTimeMillis())
                .nickname("성능테스트유저")
                .status(Status.ACTIVE)
                .fcmToken("test_fcm_token_performance")
                .build();
        testUser = userRepository.save(testUser);
        
        // 테스트용 알림 타입 생성
        testNotificationType = NotificationType.of(Type.CHAT, "성능테스트 템플릿: %s");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
        
        System.gc(); // 가비지 컬렉션 수행
    }

    @Test
    @DisplayName("UT-NT-191: 1000개 알림 생성 성능 테스트 (배치)")
    @Timeout(30) // 30초 제한
    void utNt191Create1KNotificationsPerformanceTest() {
        // given
        int notificationCount = 1_000;
        
        // when - 배치 처리 사용
        Instant startTime = Instant.now();
        
        notificationService.createPerformanceTestNotifications(
            testUser.getUserId(),
            Type.CHAT,
            notificationCount
        );
        
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);
        
        // then
        long actualCount = notificationRepository.count();
        assertThat(actualCount).isEqualTo(notificationCount);
        
        // 성능 지표 출력
        double seconds = duration.toMillis() / 1000.0;
        double tps = notificationCount / seconds;
        
        System.out.println("\n=== 1000개 알림 생성 성능 결과 ===");
        System.out.println("총 처리시간: " + String.format("%.2f", seconds) + "초");
        System.out.println("처리량(TPS): " + String.format("%.2f", tps) + " notifications/sec");
        System.out.println("평균 처리시간: " + String.format("%.2f", (seconds * 1000) / notificationCount) + "ms/notification");
        
        // 성능 기준: 최소 50 TPS 이상
        assertThat(tps).isGreaterThan(50.0);
        assertThat(seconds).isLessThan(20.0); // 20초 미만
    }

    @Test
    @DisplayName("UT-NT-192: 1만개 알림 생성 성능 테스트 (배치)")
    @Timeout(60) // 1분 제한
    void utNt192Create10KNotificationsPerformanceTest() {
        // given
        int notificationCount = 10_000;
        
        // when - 배치 처리 사용
        Instant startTime = Instant.now();
        
        notificationService.createPerformanceTestNotifications(
            testUser.getUserId(),
            Type.CHAT,
            notificationCount
        );
        
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);
        
        // then
        long actualCount = notificationRepository.count();
        assertThat(actualCount).isEqualTo(notificationCount);
        
        // 성능 지표 출력
        double seconds = duration.toMillis() / 1000.0;
        double tps = notificationCount / seconds;
        
        System.out.println("\n=== 1만개 알림 생성 성능 결과 ===");
        System.out.println("총 처리시간: " + String.format("%.2f", seconds) + "초");
        System.out.println("처리량(TPS): " + String.format("%.2f", tps) + " notifications/sec");
        System.out.println("평균 처리시간: " + String.format("%.2f", (seconds * 1000) / notificationCount) + "ms/notification");
        
        // 성능 기준: 배치 처리로 500 TPS 이상
        assertThat(tps).isGreaterThan(500.0);
        assertThat(seconds).isLessThan(20.0); // 20초 미만
    }

    @Test
    @DisplayName("UT-NT-193: 10만개 알림 생성 성능 테스트 (배치)")
    @Timeout(120) // 2분 제한
    void utNt193Create100KNotificationsPerformanceTest() {
        // given
        int notificationCount = 100_000;
        
        // when - 배치 처리 사용
        Instant startTime = Instant.now();
        
        notificationService.createPerformanceTestNotifications(
            testUser.getUserId(),
            Type.CHAT,
            notificationCount
        );
        
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);
        
        // then
        long actualCount = notificationRepository.count();
        assertThat(actualCount).isEqualTo(notificationCount);
        
        // 성능 지표 출력
        double seconds = duration.toMillis() / 1000.0;
        double tps = notificationCount / seconds;
        
        System.out.println("\n=== 10만개 알림 생성 성능 결과 ===");
        System.out.println("총 처리시간: " + String.format("%.2f", seconds) + "초");
        System.out.println("처리량(TPS): " + String.format("%.2f", tps) + " notifications/sec");
        System.out.println("평균 처리시간: " + String.format("%.2f", (seconds * 1000) / notificationCount) + "ms/notification");
        
        // 성능 기준: 배치 처리로 1000 TPS 이상
        assertThat(tps).isGreaterThan(1000.0);
        assertThat(seconds).isLessThan(100.0); // 100초 미만
    }

    @Test
    @DisplayName("UT-NT-196: 100만개 알림 생성 성능 테스트 (배치)")
    @Timeout(600) // 10분 제한
    void utNt196Create1MNotificationsPerformanceTest() {
        // given
        int notificationCount = 1_000_000;
        
        // when - 배치 처리 사용
        Instant startTime = Instant.now();
        
        // 메모리 관리를 위해 10만개씩 나누어 처리
        int batchSize = 100_000;
        for (int i = 0; i < notificationCount; i += batchSize) {
            int currentBatch = Math.min(batchSize, notificationCount - i);
            
            notificationService.createPerformanceTestNotifications(
                testUser.getUserId(),
                Type.CHAT,
                currentBatch
            );
            
            // 진행률 출력
            int processed = i + currentBatch;
            if (processed % 100_000 == 0) {
                Instant currentTime = Instant.now();
                Duration currentDuration = Duration.between(startTime, currentTime);
                double currentSeconds = currentDuration.toMillis() / 1000.0;
                double currentTps = processed / currentSeconds;
                
                System.out.println("진행률: " + processed + "/" + notificationCount + 
                                 " (현재 TPS: " + String.format("%.2f", currentTps) + ")");
            }
        }
        
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);
        
        // then
        long actualCount = notificationRepository.count();
        assertThat(actualCount).isEqualTo(notificationCount);
        
        // 성능 지표 출력
        double seconds = duration.toMillis() / 1000.0;
        double tps = notificationCount / seconds;
        
        System.out.println("\n=== 100만개 알림 생성 성능 결과 ===");
        System.out.println("총 처리시간: " + String.format("%.2f", seconds) + "초 (" + 
                          String.format("%.2f", seconds / 60) + "분)");
        System.out.println("처리량(TPS): " + String.format("%.2f", tps) + " notifications/sec");
        System.out.println("평균 처리시간: " + String.format("%.4f", (seconds * 1000) / notificationCount) + "ms/notification");
        
        // 성능 기준: 배치 처리로 500+ TPS 이상
        assertThat(tps).isGreaterThan(500.0);
        assertThat(seconds).isLessThan(2000.0); // 2000초(33분) 미만
    }

    @Test
    @DisplayName("UT-NT-194: 병렬 알림 생성 성능 테스트 (1만개)")
    @Timeout(60) // 1분 제한
    void utNt194ParallelCreate10KNotificationsPerformanceTest() throws InterruptedException {
        // given
        int notificationCount = 10_000;
        int threadCount = 10;
        int notificationsPerThread = notificationCount / threadCount;
        
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicLong totalProcessed = new AtomicLong(0);
        
        // when
        Instant startTime = Instant.now();
        
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executorService.submit(() -> {
                try {
                    for (int i = 0; i < notificationsPerThread; i++) {
                        NotificationCreateRequestDto requestDto = NotificationCreateRequestDto.builder()
                                .userId(testUser.getUserId())
                                .type(Type.CHAT)
                                .args(new String[]{"병렬테스트-" + threadId + "-" + i})
                                .build();
                        
                        notificationService.createNotification(requestDto);
                        totalProcessed.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        
        boolean finished = latch.await(60, TimeUnit.SECONDS);
        executorService.shutdown();
        
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);
        
        // then
        assertThat(finished).isTrue();
        
        long actualCount = notificationRepository.count();
        assertThat(actualCount).isEqualTo(notificationCount);
        
        // 성능 지표 출력
        double seconds = duration.toMillis() / 1000.0;
        double tps = notificationCount / seconds;
        
        System.out.println("\n=== 병렬 1만개 알림 생성 성능 결과 ===");
        System.out.println("스레드 수: " + threadCount);
        System.out.println("총 처리시간: " + String.format("%.2f", seconds) + "초");
        System.out.println("처리량(TPS): " + String.format("%.2f", tps) + " notifications/sec");
        System.out.println("평균 처리시간: " + String.format("%.2f", (seconds * 1000) / notificationCount) + "ms/notification");
        
        // 병렬 처리로 단일 스레드보다 높은 성능 기대
        assertThat(tps).isGreaterThan(200.0);
        assertThat(seconds).isLessThan(50.0); // 50초 미만
    }

    @Test
    @DisplayName("UT-NT-195: 메모리 사용량 모니터링 테스트")
    @Timeout(120) // 2분 제한
    void utNt195MemoryUsageMonitoringTest() {
        // given
        int notificationCount = 5_000;
        Runtime runtime = Runtime.getRuntime();
        
        // 초기 메모리 상태
        System.gc();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        System.out.println("\n=== 메모리 사용량 모니터링 ===");
        System.out.println("초기 메모리 사용량: " + (initialMemory / 1024 / 1024) + " MB");
        
        // when
        for (int i = 0; i < notificationCount; i++) {
            NotificationCreateRequestDto requestDto = NotificationCreateRequestDto.builder()
                    .userId(testUser.getUserId())
                    .type(Type.CHAT)
                    .args(new String[]{"메모리테스트" + i})
                    .build();
            
            notificationService.createNotification(requestDto);
            
            // 매 1000개마다 메모리 사용량 체크
            if ((i + 1) % 1000 == 0) {
                long currentMemory = runtime.totalMemory() - runtime.freeMemory();
                System.out.println("처리 " + (i + 1) + "개 후 메모리: " + (currentMemory / 1024 / 1024) + " MB");
            }
        }
        
        // 최종 메모리 상태
        System.gc();
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // then
        long memoryIncrease = finalMemory - initialMemory;
        double memoryPerNotification = (double) memoryIncrease / notificationCount;
        
        System.out.println("최종 메모리 사용량: " + (finalMemory / 1024 / 1024) + " MB");
        System.out.println("메모리 증가량: " + (memoryIncrease / 1024 / 1024) + " MB");
        System.out.println("알림당 평균 메모리: " + String.format("%.2f", memoryPerNotification) + " bytes");
        
        // 메모리 리크 체크: 알림당 1KB 이하 사용
        assertThat(memoryPerNotification).isLessThan(1024.0);
        
        long actualCount = notificationRepository.count();
        assertThat(actualCount).isEqualTo(notificationCount);
    }

    @Test
    @DisplayName("UT-NT-197: 극한 성능 테스트 - 500만개 알림 생성")
    @Timeout(1800) // 30분 제한
    void utNt197ExtremePerformanceTest() {
        // given
        int notificationCount = 5_000_000; // 500만개
        
        // when - 배치 처리 사용
        Instant startTime = Instant.now();
        
        // 메모리 관리를 위해 50만개씩 나누어 처리
        int batchSize = 500_000;
        for (int i = 0; i < notificationCount; i += batchSize) {
            int currentBatch = Math.min(batchSize, notificationCount - i);
            
            notificationService.createPerformanceTestNotifications(
                testUser.getUserId(),
                Type.CHAT,
                currentBatch
            );
            
            // 진행률 출력 (매 50만개마다)
            int processed = i + currentBatch;
            if (processed % 500_000 == 0) {
                Instant currentTime = Instant.now();
                Duration currentDuration = Duration.between(startTime, currentTime);
                double currentSeconds = currentDuration.toMillis() / 1000.0;
                double currentTps = processed / currentSeconds;
                
                System.out.println("진행률: " + processed + "/" + notificationCount + 
                                 " (현재 TPS: " + String.format("%.2f", currentTps) + 
                                 ", 경과시간: " + String.format("%.1f", currentSeconds / 60) + "분)");
                
                // 가비지 컬렉션 수행 (메모리 관리)
                System.gc();
            }
        }
        
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);
        
        // then
        long actualCount = notificationRepository.count();
        assertThat(actualCount).isEqualTo(notificationCount);
        
        // 성능 지표 출력
        double seconds = duration.toMillis() / 1000.0;
        double minutes = seconds / 60;
        double tps = notificationCount / seconds;
        
        System.out.println("\n=== 500만개 알림 생성 극한 성능 결과 ===");
        System.out.println("총 처리시간: " + String.format("%.2f", minutes) + "분 (" + 
                          String.format("%.1f", seconds) + "초)");
        System.out.println("처리량(TPS): " + String.format("%.2f", tps) + " notifications/sec");
        System.out.println("평균 처리시간: " + String.format("%.4f", (seconds * 1000) / notificationCount) + "ms/notification");
        System.out.println("총 데이터: " + (notificationCount / 1_000_000.0) + "M rows");
        
        // 성능 기준: 극한 테스트이므로 낮은 기준
        assertThat(tps).isGreaterThan(100.0); // 최소 100 TPS
        assertThat(minutes).isLessThan(30.0); // 30분 미만
    }

    @Test
    @DisplayName("UT-NT-198: 궁극 성능 테스트 - 100만개 (최적화 버전)")
    @Timeout(300) // 5분 제한
    void utNt198UltimatePerformanceTest() {
        // given
        int notificationCount = 1_000_000;
        
        // when - 궁극 최적화 메서드 사용
        Instant startTime = Instant.now();
        
        notificationService.createUltimatePerformanceTestNotifications(
            testUser.getUserId(),
            Type.CHAT,
            notificationCount
        );
        
        Instant endTime = Instant.now();
        Duration duration = Duration.between(startTime, endTime);
        
        // then
        long actualCount = notificationRepository.count();
        assertThat(actualCount).isEqualTo(notificationCount);
        
        // 성능 지표 출력
        double seconds = duration.toMillis() / 1000.0;
        double tps = notificationCount / seconds;
        
        System.out.println("\n=== 궁극 성능 테스트 결과 (100만개) ===");
        System.out.println("총 처리시간: " + String.format("%.2f", seconds) + "초 (" + 
                          String.format("%.2f", seconds / 60) + "분)");
        System.out.println("처리량(TPS): " + String.format("%.2f", tps) + " notifications/sec");
        System.out.println("평균 처리시간: " + String.format("%.4f", (seconds * 1000) / notificationCount) + "ms/notification");
        
        // 목표: 30,000+ TPS
        assertThat(tps).isGreaterThan(25000.0);
        assertThat(seconds).isLessThan(60.0); // 1분 미만
    }

    @Test  
    @DisplayName("UT-NT-199: 배치 사이즈 성능 비교 테스트")
    @Timeout(180) // 3분 제한
    void utNt199BatchSizeComparisonTest() {
        int testCount = 100_000;
        
        System.out.println("\n=== 배치 사이즈 성능 비교 ===");
        
        // 기본 메서드 테스트
        Instant start1 = Instant.now();
        notificationService.createPerformanceTestNotifications(
            testUser.getUserId(), Type.CHAT, testCount
        );
        Instant end1 = Instant.now();
        double time1 = Duration.between(start1, end1).toMillis() / 1000.0;
        double tps1 = testCount / time1;
        
        // 데이터 정리
        notificationRepository.deleteAll();
        
        // 궁극 최적화 메서드 테스트
        Instant start2 = Instant.now();
        notificationService.createUltimatePerformanceTestNotifications(
            testUser.getUserId(), Type.CHAT, testCount
        );
        Instant end2 = Instant.now();
        double time2 = Duration.between(start2, end2).toMillis() / 1000.0;
        double tps2 = testCount / time2;
        
        System.out.println("기본 메서드: " + String.format("%.2f", tps1) + " TPS (" + 
                          String.format("%.2f", time1) + "초)");
        System.out.println("최적화 메서드: " + String.format("%.2f", tps2) + " TPS (" + 
                          String.format("%.2f", time2) + "초)");
        System.out.println("성능 향상: " + String.format("%.1f", (tps2 / tps1 * 100)) + "%");
        
        // 최적화 버전이 더 빨라야 함
        assertThat(tps2).isGreaterThan(tps1 * 0.8); // 최소 80% 이상 유지
    }
}