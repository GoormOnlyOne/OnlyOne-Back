package com.example.onlyone.performance;

import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.service.FcmService;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.notification.service.SseEmittersService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.entity.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * 🔥 극한 부하 스트레스 테스트 🔥
 * - 시스템 한계 도전
 * - 병목지점 발견
 * - 성능 한계 측정
 */
@DisplayName("🔥 극한 부하 스트레스 테스트")
class StressTest extends BasePerformanceTest {

    @Autowired
    private NotificationService notificationService;
    
    @Autowired
    private FcmService fcmService;
    
    @Autowired
    private SseEmittersService sseEmittersService;

    @Test
    @DisplayName("💥 STRESS-001: 대량 데이터 조회 스트레스 테스트")
    void massive_data_query_stress_test() {
        System.err.printf("🚀 대량 데이터 조회 스트레스 테스트 시작...%n");
        
        // given - 대량 사용자 및 알림 생성
        int massiveUserCount = 5000; // 5천명
        int notificationsPerUser = 100; // 사용자당 100개
        
        System.err.printf("📊 테스트 데이터 생성 중: 사용자 %d명, 총 알림 %d개%n", 
                         massiveUserCount, massiveUserCount * notificationsPerUser);
        
        Instant dataSetupStart = Instant.now();
        
        // 대량 사용자 생성 (기존 사용자 포함 및 추가 생성)
        List<User> massiveUsers = new ArrayList<>(testUsers);
        
        // 추가 사용자 생성
        for (int i = testUsers.size(); i < massiveUserCount; i++) {
            User user = User.builder()
                    .kakaoId((long) (100000 + i))
                    .nickname("stress_user_" + i)
                    .fcmToken("stress_token_" + i)
                    .status(Status.ACTIVE)
                    .build();
            massiveUsers.add(userRepository.save(user));
        }
        
        // 대량 알림 생성 (배치 처리)
        int batchSize = 1000;
        AtomicInteger totalNotifications = new AtomicInteger(0);
        
        for (int i = 0; i < massiveUsers.size(); i += batchSize) {
            int endIndex = Math.min(i + batchSize, massiveUsers.size());
            List<User> batchUsers = massiveUsers.subList(i, endIndex);
            
            for (User user : batchUsers) {
                for (int j = 0; j < notificationsPerUser; j++) {
                    AppNotification notification = AppNotification.create(
                        user, testNotificationTypes.get(j % testNotificationTypes.size()), 
                        "스트레스테스트_" + totalNotifications.incrementAndGet());
                    notificationRepository.save(notification);
                }
            }
            
            if (i % (batchSize * 5) == 0) {
                System.err.printf("⏳ 데이터 생성 진행률: %.1f%% (%d/%d)%n", 
                                 (double) i / massiveUsers.size() * 100, i, massiveUsers.size());
            }
        }
        
        long dataSetupTime = Duration.between(dataSetupStart, Instant.now()).toMillis();
        System.err.printf("✅ 데이터 생성 완료: %,d개 알림, 소요시간: %,dms%n", 
                         totalNotifications.get(), dataSetupTime);
        
        // when - 극한 부하 조회 테스트
        int concurrentUsers = 200; // 동시 200명
        int queriesPerUser = 50;   // 사용자당 50번 조회
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrentUsers);
        
        AtomicLong totalQueries = new AtomicLong(0);
        AtomicLong totalResponseTime = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
        AtomicLong maxResponseTime = new AtomicLong(0);
        
        ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);
        
        System.err.printf("🔥 극한 부하 테스트 시작: 동시 %d명, 총 %,d번 조회%n", 
                         concurrentUsers, concurrentUsers * queriesPerUser);
        
        Instant stressStart = Instant.now();
        
        for (int i = 0; i < concurrentUsers; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    for (int q = 0; q < queriesPerUser; q++) {
                        long queryStart = System.currentTimeMillis();
                        try {
                            User randomUser = massiveUsers.get(ThreadLocalRandom.current().nextInt(massiveUsers.size()));
                            NotificationListResponseDto result = notificationService.getNotifications(
                                randomUser.getUserId(), null, 20);
                            
                            long responseTime = System.currentTimeMillis() - queryStart;
                            totalQueries.incrementAndGet();
                            totalResponseTime.addAndGet(responseTime);
                            
                            // 최소/최대 응답시간 업데이트
                            minResponseTime.updateAndGet(current -> Math.min(current, responseTime));
                            maxResponseTime.updateAndGet(current -> Math.max(current, responseTime));
                            
                            assertThat(result).isNotNull();
                            
                        } catch (Exception e) {
                            errors.incrementAndGet();
                            System.err.printf("❌ 쿼리 실패: %s%n", e.getMessage());
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // 스트레스 테스트 시작!
        startLatch.countDown();
        
        try {
            boolean completed = endLatch.await(5, TimeUnit.MINUTES); // 최대 5분 대기
            if (!completed) {
                System.err.printf("⚠️ 테스트가 5분 내에 완료되지 않음%n");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        executor.shutdown();
        long stressTestTime = Duration.between(stressStart, Instant.now()).toMillis();
        
        // then - 결과 분석
        long totalQueriesCompleted = totalQueries.get();
        double averageResponseTime = totalQueriesCompleted > 0 ? 
            (double) totalResponseTime.get() / totalQueriesCompleted : 0;
        double throughputQPS = (double) totalQueriesCompleted / stressTestTime * 1000;
        double errorRate = (double) errors.get() / (concurrentUsers * queriesPerUser) * 100;
        
        System.err.printf("%n🔥 === 극한 부하 테스트 결과 === 🔥%n");
        System.err.printf("📊 총 데이터: %,d개 알림%n", totalNotifications.get());
        System.err.printf("🎯 목표 쿼리: %,d개%n", concurrentUsers * queriesPerUser);
        System.err.printf("✅ 완료 쿼리: %,d개%n", totalQueriesCompleted);
        System.err.printf("❌ 실패 쿼리: %,d개 (%.2f%%)%n", errors.get(), errorRate);
        System.err.printf("⚡ 평균 응답시간: %.2fms%n", averageResponseTime);
        System.err.printf("🚀 최소 응답시간: %dms%n", minResponseTime.get() == Long.MAX_VALUE ? 0 : minResponseTime.get());
        System.err.printf("🐌 최대 응답시간: %dms%n", maxResponseTime.get());
        System.err.printf("💨 처리량: %.2f QPS%n", throughputQPS);
        System.err.printf("⏱️ 총 소요시간: %,dms%n", stressTestTime);
        
        // 성능 검증
        assertThat(errorRate).isLessThan(5.0); // 오류율 5% 미만
        assertThat(averageResponseTime).isLessThan(5000); // 평균 5초 미만
        assertThat(throughputQPS).isGreaterThan(10); // 최소 10 QPS
    }

    @Test
    @DisplayName("💥 STRESS-002: SSE 대량 동시 연결 스트레스 테스트")
    void massive_sse_connections_stress_test() {
        System.err.printf("🚀 SSE 대량 동시 연결 스트레스 테스트 시작...%n");
        
        // given - 기존 사용자 사용 및 추가 생성
        int massiveConnectionCount = 1000; // 1천개 동시 연결
        List<User> connectionUsers = new ArrayList<>(testUsers);
        
        // 추가 사용자 생성
        for (int i = testUsers.size(); i < massiveConnectionCount; i++) {
            User user = User.builder()
                    .kakaoId((long) (500000 + i))
                    .nickname("sse_stress_user_" + i)
                    .fcmToken("sse_stress_token_" + i)
                    .status(Status.ACTIVE)
                    .build();
            connectionUsers.add(userRepository.save(user));
        }
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(massiveConnectionCount);
        ExecutorService executor = Executors.newFixedThreadPool(100);
        
        AtomicInteger successConnections = new AtomicInteger(0);
        AtomicInteger failedConnections = new AtomicInteger(0);
        AtomicLong totalConnectionTime = new AtomicLong(0);
        
        System.err.printf("🔥 %,d개 동시 SSE 연결 시도...%n", massiveConnectionCount);
        
        Instant connectionStart = Instant.now();
        
        // when - 대량 동시 연결
        for (User user : connectionUsers) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    long connStart = System.currentTimeMillis();
                    SseEmitter emitter = sseEmittersService.createSseConnection(user.getUserId());
                    long connTime = System.currentTimeMillis() - connStart;
                    
                    if (emitter != null) {
                        successConnections.incrementAndGet();
                        totalConnectionTime.addAndGet(connTime);
                    } else {
                        failedConnections.incrementAndGet();
                    }
                    
                } catch (Exception e) {
                    failedConnections.incrementAndGet();
                    System.err.printf("❌ 연결 실패: %s%n", e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // 연결 시작!
        startLatch.countDown();
        
        try {
            boolean completed = endLatch.await(2, TimeUnit.MINUTES);
            if (!completed) {
                System.err.printf("⚠️ 연결 테스트가 2분 내에 완료되지 않음%n");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        executor.shutdown();
        long totalConnectionTestTime = Duration.between(connectionStart, Instant.now()).toMillis();
        
        // 메모리 사용량 체크
        Runtime runtime = Runtime.getRuntime();
        long usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        int activeConnections = sseEmittersService.getActiveConnectionCount();
        
        // then - 결과 분석
        double averageConnectionTime = successConnections.get() > 0 ? 
            (double) totalConnectionTime.get() / successConnections.get() : 0;
        double successRate = (double) successConnections.get() / massiveConnectionCount * 100;
        double memoryPerConnection = activeConnections > 0 ? (double) usedMemoryMB / activeConnections : 0;
        
        System.err.printf("%n🔥 === SSE 대량 연결 테스트 결과 === 🔥%n");
        System.err.printf("🎯 시도 연결: %,d개%n", massiveConnectionCount);
        System.err.printf("✅ 성공 연결: %,d개 (%.2f%%)%n", successConnections.get(), successRate);
        System.err.printf("❌ 실패 연결: %,d개%n", failedConnections.get());
        System.err.printf("🔗 활성 연결: %,d개%n", activeConnections);
        System.err.printf("⚡ 평균 연결시간: %.2fms%n", averageConnectionTime);
        System.err.printf("💾 메모리 사용량: %,dMB%n", usedMemoryMB);
        System.err.printf("📊 연결당 메모리: %.2fMB%n", memoryPerConnection);
        System.err.printf("⏱️ 총 소요시간: %,dms%n", totalConnectionTestTime);
        
        // 성능 검증
        assertThat(successRate).isGreaterThan(80.0); // 80% 이상 성공
        assertThat(averageConnectionTime).isLessThan(1000); // 평균 1초 미만
        assertThat(memoryPerConnection).isLessThan(5.0); // 연결당 5MB 미만
    }

    @Test
    @DisplayName("💥 STRESS-003: FCM 대량 전송 스트레스 테스트")
    void massive_fcm_sending_stress_test() {
        System.err.printf("🚀 FCM 대량 전송 스트레스 테스트 시작...%n");
        
        // given
        int massiveFcmCount = 5000; // 5천개 FCM 전송
        List<AppNotification> massiveNotifications = createTestNotifications(massiveFcmCount);
        
        int concurrentSenders = 50; // 동시 50개 스레드
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrentSenders);
        ExecutorService executor = Executors.newFixedThreadPool(concurrentSenders);
        
        AtomicInteger processedNotifications = new AtomicInteger(0);
        AtomicInteger successfulSends = new AtomicInteger(0);
        AtomicInteger failedSends = new AtomicInteger(0);
        AtomicLong totalSendTime = new AtomicLong(0);
        
        // 알림을 스레드별로 분배
        int notificationsPerThread = massiveFcmCount / concurrentSenders;
        
        System.err.printf("🔥 %,d개 FCM 메시지를 %d개 스레드로 전송...%n", 
                         massiveFcmCount, concurrentSenders);
        
        Instant fcmStart = Instant.now();
        
        // when - 대량 FCM 전송
        for (int i = 0; i < concurrentSenders; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    
                    int startIdx = threadIndex * notificationsPerThread;
                    int endIdx = Math.min(startIdx + notificationsPerThread, massiveNotifications.size());
                    
                    for (int j = startIdx; j < endIdx; j++) {
                        long sendStart = System.currentTimeMillis();
                        try {
                            fcmService.sendFcmNotification(massiveNotifications.get(j));
                            successfulSends.incrementAndGet();
                            
                        } catch (Exception e) {
                            failedSends.incrementAndGet();
                        }
                        
                        long sendTime = System.currentTimeMillis() - sendStart;
                        totalSendTime.addAndGet(sendTime);
                        processedNotifications.incrementAndGet();
                        
                        // 진행률 출력
                        if (processedNotifications.get() % 1000 == 0) {
                            double progress = (double) processedNotifications.get() / massiveFcmCount * 100;
                            System.err.printf("⏳ FCM 전송 진행률: %.1f%% (%,d/%,d)%n", 
                                             progress, processedNotifications.get(), massiveFcmCount);
                        }
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }
        
        // FCM 전송 시작!
        startLatch.countDown();
        
        try {
            boolean completed = endLatch.await(10, TimeUnit.MINUTES);
            if (!completed) {
                System.err.printf("⚠️ FCM 전송 테스트가 10분 내에 완료되지 않음%n");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        executor.shutdown();
        long totalFcmTestTime = Duration.between(fcmStart, Instant.now()).toMillis();
        
        // then - 결과 분석
        double averageSendTime = processedNotifications.get() > 0 ? 
            (double) totalSendTime.get() / processedNotifications.get() : 0;
        double throughputMPS = (double) processedNotifications.get() / totalFcmTestTime * 1000; // Messages Per Second
        double successRate = (double) successfulSends.get() / processedNotifications.get() * 100;
        
        System.err.printf("%n🔥 === FCM 대량 전송 테스트 결과 === 🔥%n");
        System.err.printf("🎯 목표 전송: %,d개%n", massiveFcmCount);
        System.err.printf("📤 처리 완료: %,d개%n", processedNotifications.get());
        System.err.printf("✅ 전송 성공: %,d개 (%.2f%%)%n", successfulSends.get(), successRate);
        System.err.printf("❌ 전송 실패: %,d개%n", failedSends.get());
        System.err.printf("⚡ 평균 전송시간: %.2fms%n", averageSendTime);
        System.err.printf("💨 처리량: %.2f MPS%n", throughputMPS);
        System.err.printf("⏱️ 총 소요시간: %,dms%n", totalFcmTestTime);
        
        // 성능 검증
        assertThat(processedNotifications.get()).isGreaterThan((int)(massiveFcmCount * 0.9)); // 90% 이상 처리
        assertThat(averageSendTime).isLessThan(100.0); // 평균 100ms 미만
        assertThat(throughputMPS).isGreaterThan(50.0); // 최소 50 MPS
    }

    @Test
    @DisplayName("💥 STRESS-004: 메모리 한계 도전 테스트")
    void memory_limit_challenge_test() {
        System.err.printf("🚀 메모리 한계 도전 테스트 시작...%n");
        
        Runtime runtime = Runtime.getRuntime();
        long initialMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        
        System.err.printf("📊 초기 메모리: %,dMB%n", initialMemoryMB);
        
        List<Object> memoryConsumers = new ArrayList<>();
        int iteration = 0;
        
        try {
            // 점진적으로 메모리 사용량 증가
            while (true) {
                iteration++;
                
                // 사용자 생성 (고유한 ID로 생성)
                List<User> batchUsers = new ArrayList<>();
                int baseUserId = 700000 + (iteration - 1) * 100;
                for (int i = 0; i < 100; i++) {
                    User user = User.builder()
                            .kakaoId((long) (baseUserId + i))
                            .nickname("mem_stress_" + iteration + "_user_" + i)
                            .fcmToken("mem_stress_" + iteration + "_token_" + i)
                            .status(Status.ACTIVE)
                            .build();
                    batchUsers.add(userRepository.save(user));
                }
                memoryConsumers.add(batchUsers);
                
                // 알림 생성
                List<AppNotification> batchNotifications = createTestNotifications(500);
                memoryConsumers.add(batchNotifications);
                
                // SSE 연결 생성
                List<SseEmitter> emitters = new ArrayList<>();
                for (int i = 0; i < 50; i++) {
                    User user = batchUsers.get(i % batchUsers.size());
                    SseEmitter emitter = sseEmittersService.createSseConnection(user.getUserId());
                    if (emitter != null) {
                        emitters.add(emitter);
                    }
                }
                memoryConsumers.add(emitters);
                
                // 메모리 상태 체크
                long currentMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);
                double memoryUsagePercent = (double) currentMemoryMB / maxMemoryMB * 100;
                
                if (iteration % 10 == 0) {
                    System.err.printf("🔥 반복 %,d: 메모리 %,dMB/%.1f%%, SSE연결 %,d개%n", 
                                     iteration, currentMemoryMB, memoryUsagePercent, 
                                     sseEmittersService.getActiveConnectionCount());
                }
                
                // 메모리 사용률이 85%를 넘으면 중단
                if (memoryUsagePercent > 85.0) {
                    System.err.printf("⚠️ 메모리 사용률 85%% 초과로 테스트 중단%n");
                    break;
                }
                
                // 최대 반복 제한
                if (iteration > 1000) {
                    System.err.printf("✅ 최대 반복 1000회 도달%n");
                    break;
                }
                
                // 짧은 휴식
                Thread.sleep(10);
            }
            
        } catch (OutOfMemoryError e) {
            System.err.printf("💥 OutOfMemoryError 발생! 반복 %,d회에서 한계 도달%n", iteration);
        } catch (Exception e) {
            System.err.printf("❌ 예외 발생: %s%n", e.getMessage());
        }
        
        // 최종 메모리 상태
        long finalMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);
        double finalMemoryUsagePercent = (double) finalMemoryMB / maxMemoryMB * 100;
        
        System.err.printf("%n🔥 === 메모리 한계 테스트 결과 === 🔥%n");
        System.err.printf("🔄 총 반복: %,d회%n", iteration);
        System.err.printf("📊 초기 메모리: %,dMB%n", initialMemoryMB);
        System.err.printf("📊 최종 메모리: %,dMB (%.1f%%)%n", finalMemoryMB, finalMemoryUsagePercent);
        System.err.printf("📊 최대 메모리: %,dMB%n", maxMemoryMB);
        System.err.printf("🔗 최종 SSE 연결: %,d개%n", sseEmittersService.getActiveConnectionCount());
        System.err.printf("📈 메모리 증가량: %,dMB%n", finalMemoryMB - initialMemoryMB);
        
        // 메모리 정리 확인
        memoryConsumers.clear();
        System.gc();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long afterGcMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        System.err.printf("🧹 GC 후 메모리: %,dMB%n", afterGcMemoryMB);
        
        assertThat(iteration).isGreaterThan(50); // 최소 50회 반복
        assertThat(finalMemoryUsagePercent).isLessThan(95.0); // 95% 미만 사용
    }
}