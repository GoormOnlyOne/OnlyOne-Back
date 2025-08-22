package com.example.onlyone.performance;

import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.notification.service.SseEmittersService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.entity.Status;
import org.junit.jupiter.api.BeforeEach;
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
 * 🔥 간단한 부하 테스트 🔥
 * - 현실적인 부하 수준
 * - 시스템 한계 탐지
 * - 성능 병목 발견
 */
@DisplayName("🔥 간단한 부하 테스트")
class SimpleLoadTest extends BasePerformanceTest {

    @Autowired
    private NotificationService notificationService;
    
    @Autowired
    private SseEmittersService sseEmittersService;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll(); // 테스트 전 데이터 초기화
    }

    @Test
    @DisplayName("🚀 LOAD-001: 알림 조회 부하 테스트 (동시 사용자 증가)")
    void notification_query_load_test() {
        System.err.printf("🚀 알림 조회 부하 테스트 시작...%n");
        
        // given - 테스트 데이터 준비 (기존 사용자 사용 및 추가 생성)
        int testUserCount = 100;
        List<User> loadTestUsers = new ArrayList<>(testUsers);
        
        // 추가 사용자 생성
        for (int i = testUsers.size(); i < testUserCount; i++) {
            User user = User.builder()
                    .kakaoId((long) (100000 + i))
                    .nickname("load_user_" + i)
                    .fcmToken("load_token_" + i)
                    .status(Status.ACTIVE)
                    .build();
            loadTestUsers.add(userRepository.save(user));
        }
        
        // 각 사용자에게 20개씩 알림 생성
        for (User user : loadTestUsers) {
            for (int i = 0; i < 20; i++) {
                AppNotification notification = AppNotification.create(
                    user, testNotificationTypes.get(i % testNotificationTypes.size()), 
                    "부하테스트_" + i);
                notificationRepository.save(notification);
            }
        }
        
        System.err.printf("📊 테스트 데이터: 사용자 %d명, 총 알림 %,d개%n", 
                         testUserCount, testUserCount * 20);
        
        // when - 점진적 부하 증가
        int[] concurrentLevels = {1, 5, 10, 20, 30}; // 동시 사용자 수
        
        for (int concurrentUsers : concurrentLevels) {
            System.err.printf("%n🔥 동시 사용자 %d명 테스트...%n", concurrentUsers);
            
            AtomicInteger totalQueries = new AtomicInteger(0);
            AtomicLong totalResponseTime = new AtomicLong(0);
            AtomicInteger errors = new AtomicInteger(0);
            
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(concurrentUsers);
            ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);
            
            Instant testStart = Instant.now();
            
            for (int i = 0; i < concurrentUsers; i++) {
                final int userIndex = i % testUserCount;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        
                        // 각 사용자가 5번 조회
                        for (int q = 0; q < 5; q++) {
                            long queryStart = System.currentTimeMillis();
                            try {
                                User user = loadTestUsers.get(userIndex);
                                NotificationListResponseDto result = notificationService.getNotifications(
                                    user.getUserId(), null, 20);
                                
                                long responseTime = System.currentTimeMillis() - queryStart;
                                totalQueries.incrementAndGet();
                                totalResponseTime.addAndGet(responseTime);
                                
                                assertThat(result).isNotNull();
                                
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
            
            startLatch.countDown();
            
            try {
                endLatch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            executor.shutdown();
            long testDuration = Duration.between(testStart, Instant.now()).toMillis();
            
            // 결과 분석
            double averageResponseTime = totalQueries.get() > 0 ? 
                (double) totalResponseTime.get() / totalQueries.get() : 0;
            double throughputQPS = (double) totalQueries.get() / testDuration * 1000;
            double errorRate = (double) errors.get() / (concurrentUsers * 5) * 100;
            
            System.err.printf("📊 동시 사용자 %d명 결과:%n", concurrentUsers);
            System.err.printf("  ✅ 완료: %d쿼리%n", totalQueries.get());
            System.err.printf("  ❌ 실패: %d쿼리 (%.1f%%)%n", errors.get(), errorRate);
            System.err.printf("  ⚡ 평균 응답시간: %.1fms%n", averageResponseTime);
            System.err.printf("  💨 처리량: %.1f QPS%n", throughputQPS);
            System.err.printf("  ⏱️ 소요시간: %,dms%n", testDuration);
            
            // 성능 검증
            assertThat(errorRate).isLessThan(10.0); // 오류율 10% 미만
            assertThat(averageResponseTime).isLessThan(1000); // 평균 1초 미만
        }
        
        System.err.printf("%n🎯 알림 조회 부하 테스트 완료!%n");
    }

    @Test
    @DisplayName("🚀 LOAD-002: SSE 연결 부하 테스트")
    void sse_connection_load_test() {
        System.err.printf("🚀 SSE 연결 부하 테스트 시작...%n");
        
        // given - 기존 사용자 사용 및 추가 생성
        int maxConnections = 50; // 50개 연결
        List<User> connectionUsers = new ArrayList<>(testUsers);
        
        // 추가 사용자 생성 필요시
        if (connectionUsers.size() < maxConnections) {
            for (int i = connectionUsers.size(); i < maxConnections; i++) {
                User user = User.builder()
                        .kakaoId((long) (200000 + i))
                        .nickname("sse_user_" + i)
                        .fcmToken("sse_token_" + i)
                        .status(Status.ACTIVE)
                        .build();
                connectionUsers.add(userRepository.save(user));
            }
        }
        List<SseEmitter> activeEmitters = new ArrayList<>();
        
        Runtime runtime = Runtime.getRuntime();
        long initialMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        
        System.err.printf("📊 초기 메모리: %,dMB%n", initialMemoryMB);
        
        // when - 점진적 연결 증가
        int[] connectionLevels = {5, 10, 20, 30, 50}; // 연결 수
        
        for (int targetConnections : connectionLevels) {
            System.err.printf("%n🔥 SSE 연결 %d개 테스트...%n", targetConnections);
            
            // 현재 연결 수에서 목표까지 추가
            int currentConnections = activeEmitters.size();
            int connectionsToAdd = targetConnections - currentConnections;
            
            Instant connectionStart = Instant.now();
            AtomicInteger successfulConnections = new AtomicInteger(0);
            AtomicInteger failedConnections = new AtomicInteger(0);
            
            // 추가 연결 생성
            for (int i = 0; i < connectionsToAdd; i++) {
                try {
                    User user = connectionUsers.get(currentConnections + i);
                    SseEmitter emitter = sseEmittersService.createSseConnection(user.getUserId());
                    
                    if (emitter != null) {
                        activeEmitters.add(emitter);
                        successfulConnections.incrementAndGet();
                    } else {
                        failedConnections.incrementAndGet();
                    }
                } catch (Exception e) {
                    failedConnections.incrementAndGet();
                }
            }
            
            long connectionTime = Duration.between(connectionStart, Instant.now()).toMillis();
            
            // 메모리 사용량 체크
            long currentMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            int actualConnections = sseEmittersService.getActiveConnectionCount();
            double memoryPerConnection = actualConnections > 0 ? 
                (double) (currentMemoryMB - initialMemoryMB) / actualConnections : 0;
            
            System.err.printf("📊 SSE 연결 %d개 결과:%n", targetConnections);
            System.err.printf("  ✅ 성공: %d개%n", successfulConnections.get());
            System.err.printf("  ❌ 실패: %d개%n", failedConnections.get());
            System.err.printf("  🔗 실제 활성: %d개%n", actualConnections);
            System.err.printf("  💾 메모리: %,dMB%n", currentMemoryMB);
            System.err.printf("  📊 연결당 메모리: %.2fMB%n", memoryPerConnection);
            System.err.printf("  ⏱️ 연결 시간: %,dms%n", connectionTime);

            int successThreshold = (int) Math.round(connectionsToAdd * 0.8);
            // 성능 검증
            assertThat(successfulConnections.get()).isGreaterThan(successThreshold); // 80% 이상 성공
            assertThat(memoryPerConnection).isLessThan(3.0); // 연결당 3MB 미만
        }
        
        // 메시지 전송 테스트
        if (sseEmittersService.getActiveConnectionCount() > 0) {
            System.err.printf("%n📤 메시지 전송 테스트...%n");
            
            Instant messageStart = Instant.now();
            AtomicInteger sentMessages = new AtomicInteger(0);
            
            for (int i = 0; i < Math.min(activeEmitters.size(), connectionUsers.size()); i++) {
                try {
                    User user = connectionUsers.get(i);
                    AppNotification testNotification = AppNotification.create(
                        user, testNotificationTypes.get(0), "연결테스트메시지");
                    sseEmittersService.sendSseNotification(user.getUserId(), testNotification);
                    sentMessages.incrementAndGet();
                } catch (Exception e) {
                    // 메시지 전송 실패는 무시
                }
            }
            
            long messageTime = Duration.between(messageStart, Instant.now()).toMillis();
            double messagesPerSecond = sentMessages.get() > 0 ? 
                (double) sentMessages.get() / messageTime * 1000 : 0;
            
            System.err.printf("📤 메시지 전송 결과:%n");
            System.err.printf("  📤 전송됨: %d개%n", sentMessages.get());
            System.err.printf("  💨 전송 속도: %.1f msg/sec%n", messagesPerSecond);
            System.err.printf("  ⏱️ 전송 시간: %,dms%n", messageTime);
        }
        
        System.err.printf("%n🎯 SSE 연결 부하 테스트 완료! 최대 연결: %d개%n", 
                         sseEmittersService.getActiveConnectionCount());
    }

    @Test
    @DisplayName("🚀 LOAD-003: 메모리 사용량 간단 테스트")
    void simple_memory_usage_test() {
        System.err.printf("🚀 메모리 사용량 간단 테스트 시작...%n");
        
        Runtime runtime = Runtime.getRuntime();
        
        // 초기 상태
        long initialMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);
        
        System.err.printf("📊 초기 메모리: %,dMB / %,dMB (%.1f%%)%n", 
                         initialMemoryMB, maxMemoryMB, 
                         (double) initialMemoryMB / maxMemoryMB * 100);
        
        List<Object> memoryHolders = new ArrayList<>();
        
        // 5단계로 메모리 사용량 증가
        for (int phase = 1; phase <= 5; phase++) {
            System.err.printf("%n🔥 Phase %d: 메모리 사용량 증가...%n", phase);
            
            // 사용자 생성 (20명씩)
            // 사용자 생성 (고유한 ID로 생성)
            List<User> phaseUsers = new ArrayList<>();
            int baseUserId = 300000 + (phase - 1) * 20;
            for (int j = 0; j < 20; j++) {
                User user = User.builder()
                        .kakaoId((long) (baseUserId + j))
                        .nickname("mem_phase_" + phase + "_user_" + j)
                        .fcmToken("mem_phase_" + phase + "_token_" + j)
                        .status(Status.ACTIVE)
                        .build();
                phaseUsers.add(userRepository.save(user));
            }
            memoryHolders.add(phaseUsers);
            
            // 알림 생성 (50개씩)
            List<AppNotification> phaseNotifications = createTestNotifications(50);
            memoryHolders.add(phaseNotifications);
            
            // SSE 연결 생성 (10개씩)
            List<SseEmitter> phaseEmitters = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                User user = phaseUsers.get(i % phaseUsers.size());
                try {
                    SseEmitter emitter = sseEmittersService.createSseConnection(user.getUserId());
                    if (emitter != null) {
                        phaseEmitters.add(emitter);
                    }
                } catch (Exception e) {
                    // 연결 실패 무시
                }
            }
            memoryHolders.add(phaseEmitters);
            
            // 메모리 상태 체크
            long currentMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            double memoryUsagePercent = (double) currentMemoryMB / maxMemoryMB * 100;
            long memoryIncreaseMB = currentMemoryMB - initialMemoryMB;
            
            System.err.printf("📊 Phase %d 결과:%n", phase);
            System.err.printf("  💾 메모리: %,dMB (%.1f%%)%n", currentMemoryMB, memoryUsagePercent);
            System.err.printf("  📈 증가량: %,dMB%n", memoryIncreaseMB);
            System.err.printf("  🔗 SSE 연결: %d개%n", sseEmittersService.getActiveConnectionCount());
            System.err.printf("  👥 사용자: %d명%n", phase * 20);
            System.err.printf("  📮 알림: %d개%n", phase * 50);
            
            // 메모리 사용률 검증
            assertThat(memoryUsagePercent).isLessThan(70.0); // 70% 미만 사용
        }
        
        // 최종 상태
        long finalMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        double finalMemoryUsagePercent = (double) finalMemoryMB / maxMemoryMB * 100;
        
        System.err.printf("%n🎯 메모리 테스트 완료!%n");
        System.err.printf("📊 최종 메모리: %,dMB (%.1f%%)%n", finalMemoryMB, finalMemoryUsagePercent);
        System.err.printf("📈 총 증가량: %,dMB%n", finalMemoryMB - initialMemoryMB);
        System.err.printf("🔗 최종 SSE 연결: %d개%n", sseEmittersService.getActiveConnectionCount());
        
        // 성능 검증
        assertThat(finalMemoryUsagePercent).isLessThan(80.0); // 80% 미만 사용
        assertThat(finalMemoryMB - initialMemoryMB).isLessThan(200L); // 200MB 미만 증가
    }
}