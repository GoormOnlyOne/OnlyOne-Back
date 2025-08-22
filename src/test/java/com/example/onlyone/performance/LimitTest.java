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
 * 🔥 시스템 한계 도전 테스트 🔥
 * - 점진적 부하 증가
 * - 성능 한계점 발견
 * - 병목 지점 분석
 */
@DisplayName("🔥 시스템 한계 도전 테스트")
class LimitTest extends BasePerformanceTest {

    @Autowired
    private NotificationService notificationService;
    
    @Autowired
    private FcmService fcmService;
    
    @Autowired
    private SseEmittersService sseEmittersService;

    @Test
    @DisplayName("🚀 LIMIT-001: 점진적 부하 증가 테스트")
    void gradual_load_increase_test() {
        System.err.printf("🚀 점진적 부하 증가 테스트 시작...%n");
        
        // given - 테스트 데이터 준비 (BasePerformanceTest에서 이미 생성된 사용자 사용)
        int additionalUsers = 450; // 기존 50명 + 추가 450명 = 총 500명
        for (int i = testUsers.size(); i < testUsers.size() + additionalUsers; i++) {
            User user = User.builder()
                    .kakaoId((long) (100000 + i))
                    .nickname("perf_user_" + i)
                    .fcmToken("fcm_token_" + i)
                    .status(Status.ACTIVE)
                    .build();
            testUsers.add(userRepository.save(user));
        }
        
        // 각 사용자마다 50개의 알림 생성
        for (User user : testUsers) {
            for (int i = 0; i < 50; i++) {
                AppNotification notification = AppNotification.create(
                    user, testNotificationTypes.get(i % testNotificationTypes.size()), 
                    "부하테스트_" + i);
                notificationRepository.save(notification);
            }
        }
        
        System.err.printf("📊 테스트 데이터 준비 완료: 사용자 %d명, 총 알림 %,d개%n", 
                         testUsers.size(), testUsers.size() * 50);
        
        // when - 점진적 부하 증가
        int[] concurrentLevels = {1, 5, 10, 20, 50, 100}; // 동시 사용자 수
        
        for (int concurrentUsers : concurrentLevels) {
            if (concurrentUsers > testUsers.size()) continue;
            
            System.err.printf("%n🔥 동시 사용자 %d명 테스트 시작...%n", concurrentUsers);
            
            AtomicLong totalQueries = new AtomicLong(0);
            AtomicLong totalResponseTime = new AtomicLong(0);
            AtomicInteger errors = new AtomicInteger(0);
            
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(concurrentUsers);
            ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);
            
            Instant testStart = Instant.now();
            
            // 동시 쿼리 실행
            for (int i = 0; i < concurrentUsers; i++) {
                final int userIndex = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        
                        // 각 사용자가 10번 쿼리
                        for (int q = 0; q < 10; q++) {
                            long queryStart = System.currentTimeMillis();
                            try {
                                User user = testUsers.get(userIndex);
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
            double errorRate = (double) errors.get() / (concurrentUsers * 10) * 100;
            
            System.err.printf("📊 동시 사용자 %d명 결과:%n", concurrentUsers);
            System.err.printf("  ✅ 완료 쿼리: %,d개%n", totalQueries.get());
            System.err.printf("  ❌ 실패 쿼리: %d개 (%.2f%%)%n", errors.get(), errorRate);
            System.err.printf("  ⚡ 평균 응답시간: %.2fms%n", averageResponseTime);
            System.err.printf("  💨 처리량: %.2f QPS%n", throughputQPS);
            System.err.printf("  ⏱️ 소요시간: %,dms%n", testDuration);
            
            // 성능 저하 감지
            if (averageResponseTime > 2000) {
                System.err.printf("⚠️ 평균 응답시간이 2초를 초과했습니다! (%.2fms)%n", averageResponseTime);
            }
            if (errorRate > 10) {
                System.err.printf("⚠️ 오류율이 10%%를 초과했습니다! (%.2f%%)%n", errorRate);
            }
        }
        
        System.err.printf("%n🎯 점진적 부하 테스트 완료!%n");
    }

    @Test
    @DisplayName("🚀 LIMIT-002: SSE 연결 한계 테스트")
    void sse_connection_limit_test() {
        System.err.printf("🚀 SSE 연결 한계 테스트 시작...%n");
        
        // given - 기존 사용자 사용 및 추가 생성
        int maxConnections = 200; // 최대 200개 연결
        List<User> connectionUsers = new ArrayList<>(testUsers);
        
        // 추가 사용자 생성 (기존 사용자 수에서 시작)
        for (int i = testUsers.size(); i < maxConnections; i++) {
            User user = User.builder()
                    .kakaoId((long) (100000 + i))
                    .nickname("perf_user_" + i)
                    .fcmToken("fcm_token_" + i)
                    .status(Status.ACTIVE)
                    .build();
            connectionUsers.add(userRepository.save(user));
        }
        List<SseEmitter> activeEmitters = new ArrayList<>();
        
        Runtime runtime = Runtime.getRuntime();
        long initialMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        
        System.err.printf("📊 초기 메모리: %,dMB%n", initialMemoryMB);
        
        // when - 점진적 연결 증가
        int[] connectionLevels = {10, 25, 50, 100, 150, 200}; // 연결 수
        
        for (int targetConnections : connectionLevels) {
            if (targetConnections > maxConnections) continue;
            
            // 현재 연결 수에서 목표 연결 수까지 추가
            int currentConnections = activeEmitters.size();
            int connectionsToAdd = targetConnections - currentConnections;
            
            System.err.printf("%n🔥 SSE 연결 %d개로 증가 (추가: %d개)...%n", 
                             targetConnections, connectionsToAdd);
            
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
                    System.err.printf("❌ 연결 실패: %s%n", e.getMessage());
                }
            }
            
            long connectionTime = Duration.between(connectionStart, Instant.now()).toMillis();
            
            // 메모리 사용량 체크
            long currentMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            int actualConnections = sseEmittersService.getActiveConnectionCount();
            double memoryPerConnection = actualConnections > 0 ? 
                (double) (currentMemoryMB - initialMemoryMB) / actualConnections : 0;
            
            System.err.printf("📊 SSE 연결 %d개 결과:%n", targetConnections);
            System.err.printf("  ✅ 성공 연결: %d개%n", successfulConnections.get());
            System.err.printf("  ❌ 실패 연결: %d개%n", failedConnections.get());
            System.err.printf("  🔗 실제 활성 연결: %d개%n", actualConnections);
            System.err.printf("  💾 현재 메모리: %,dMB%n", currentMemoryMB);
            System.err.printf("  📊 연결당 메모리: %.2fMB%n", memoryPerConnection);
            System.err.printf("  ⏱️ 연결 소요시간: %,dms%n", connectionTime);
            
            // 메시지 전송 테스트
            if (actualConnections > 0) {
                System.err.printf("📤 %d개 연결에 메시지 전송 테스트...%n", actualConnections);
                
                Instant messageStart = Instant.now();
                AtomicInteger sentMessages = new AtomicInteger(0);
                
                // 각 연결에 메시지 전송
                for (User user : connectionUsers.subList(0, Math.min(actualConnections, connectionUsers.size()))) {
                    try {
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
                
                System.err.printf("  📤 전송된 메시지: %d개%n", sentMessages.get());
                System.err.printf("  💨 메시지 전송 속도: %.2f msg/sec%n", messagesPerSecond);
                System.err.printf("  ⏱️ 메시지 전송 시간: %,dms%n", messageTime);
            }
            
            // 한계점 감지
            if (failedConnections.get() > connectionsToAdd * 0.1) {
                System.err.printf("⚠️ 연결 실패율이 10%%를 초과했습니다!%n");
            }
            if (memoryPerConnection > 2.0) {
                System.err.printf("⚠️ 연결당 메모리 사용량이 2MB를 초과했습니다!%n");
            }
        }
        
        System.err.printf("%n🎯 SSE 연결 한계 테스트 완료! 최대 연결: %d개%n", 
                         sseEmittersService.getActiveConnectionCount());
    }

    @Test
    @DisplayName("🚀 LIMIT-003: 메모리 사용량 모니터링")
    void memory_usage_monitoring_test() {
        System.err.printf("🚀 메모리 사용량 모니터링 테스트 시작...%n");
        
        Runtime runtime = Runtime.getRuntime();
        
        // 초기 상태
        long initialMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);
        
        System.err.printf("📊 초기 메모리 상태:%n");
        System.err.printf("  💾 사용 중: %,dMB%n", initialMemoryMB);
        System.err.printf("  🎯 최대 메모리: %,dMB%n", maxMemoryMB);
        System.err.printf("  📊 사용률: %.2f%%%n", (double) initialMemoryMB / maxMemoryMB * 100);
        
        List<Object> memoryHolders = new ArrayList<>();
        
        // 점진적 메모리 사용량 증가
        for (int phase = 1; phase <= 10; phase++) {
            System.err.printf("%n🔥 Phase %d: 메모리 사용량 증가...%n", phase);
            
            // 사용자 생성 (고유한 ID로 생성)
            List<User> phaseUsers = new ArrayList<>();
            int baseUserId = 100000 + testUsers.size() + (phase - 1) * 50;
            for (int i = 0; i < 50; i++) {
                User user = User.builder()
                        .kakaoId((long) (baseUserId + i))
                        .nickname("phase_" + phase + "_user_" + i)
                        .fcmToken("phase_" + phase + "_token_" + i)
                        .status(Status.ACTIVE)
                        .build();
                phaseUsers.add(userRepository.save(user));
            }
            memoryHolders.add(phaseUsers);
            
            // 알림 생성
            List<AppNotification> phaseNotifications = createTestNotifications(100);
            memoryHolders.add(phaseNotifications);
            
            // SSE 연결 생성
            List<SseEmitter> phaseEmitters = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
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
            
            System.err.printf("📊 Phase %d 메모리 상태:%n", phase);
            System.err.printf("  💾 현재 사용: %,dMB (%.2f%%)%n", currentMemoryMB, memoryUsagePercent);
            System.err.printf("  📈 증가량: %,dMB%n", memoryIncreaseMB);
            System.err.printf("  🔗 SSE 연결: %,d개%n", sseEmittersService.getActiveConnectionCount());
            System.err.printf("  👥 생성된 사용자: %,d명%n", phase * 50);
            System.err.printf("  📮 생성된 알림: %,d개%n", phase * 100);
            
            // 메모리 사용률이 70%를 넘으면 경고
            if (memoryUsagePercent > 70.0) {
                System.err.printf("⚠️ 메모리 사용률이 70%%를 초과했습니다! (%.2f%%)%n", memoryUsagePercent);
                
                // GC 수행
                System.err.printf("🧹 가비지 컬렉션 수행...%n");
                System.gc();
                
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                long afterGcMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
                long freedMemoryMB = currentMemoryMB - afterGcMemoryMB;
                
                System.err.printf("🧹 GC 후 메모리: %,dMB (해제됨: %,dMB)%n", 
                                 afterGcMemoryMB, freedMemoryMB);
            }
            
            // 메모리 사용률이 80%를 넘으면 중단
            if (memoryUsagePercent > 80.0) {
                System.err.printf("🛑 메모리 사용률이 80%%를 초과하여 테스트를 중단합니다.%n");
                break;
            }
        }
        
        // 최종 메모리 상태
        long finalMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        double finalMemoryUsagePercent = (double) finalMemoryMB / maxMemoryMB * 100;
        
        System.err.printf("%n🎯 메모리 모니터링 테스트 완료!%n");
        System.err.printf("📊 최종 메모리 상태:%n");
        System.err.printf("  💾 최종 사용: %,dMB (%.2f%%)%n", finalMemoryMB, finalMemoryUsagePercent);
        System.err.printf("  📈 총 증가량: %,dMB%n", finalMemoryMB - initialMemoryMB);
        System.err.printf("  🔗 최종 SSE 연결: %,d개%n", sseEmittersService.getActiveConnectionCount());
        
        // 메모리 사용량 검증
        assertThat(finalMemoryUsagePercent).isLessThan(85.0); // 85% 미만 사용
        assertThat(finalMemoryMB - initialMemoryMB).isLessThan(300L); // 300MB 미만 증가
    }
}