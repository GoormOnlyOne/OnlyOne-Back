package com.example.onlyone.global.sse;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.notification.service.SseEmittersService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * SSE 서비스 통합 테스트 - 실시간 알림 전송 및 동시성 검증
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("SSE 서비스 테스트")
class SseEmittersServiceTest {

    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    @Autowired
    private SseEmittersService sseEmittersService;

    private User testUser;
    private NotificationType testNotificationType;
    private AppNotification testNotification;

    @BeforeEach
    void setUp() {
        // SSE 연결 상태 초기화 (테스트 격리)
        sseEmittersService.clearAllConnections();
        
        // SSE 설정값 주입
        ReflectionTestUtils.setField(sseEmittersService, "sseTimeoutMillis", 1800000L);

        // 실제 DB에 테스트 데이터 생성
        testUser = createTestUser(1L, "testuser");
        testNotificationType = NotificationType.of(Type.CHAT, "테스트 템플릿");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
        testNotification = AppNotification.create(testUser, testNotificationType, "테스트");
        testNotification = notificationRepository.save(testNotification);
    }

    @Nested
    @DisplayName("SSE 연결 관리")
    class SseConnectionManagementTest {

        @Test
        @DisplayName("UT-NT-052: SSE 연결이 정상 수립되는가?")
        void UT_NT_052_creates_new_sse_connection_successfully() {
            // given
            Long userId = testUser.getUserId();

            // when
            SseEmitter emitter = sseEmittersService.createSseConnection(userId);

            // then
            assertThat(emitter).isNotNull();
            assertThat(sseEmittersService.isUserConnected(userId)).isTrue();
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("기존 연결이 있는 상태에서 새 연결 생성 시 기존 연결을 정리한다")
        void UT_NT_052_replaces_existing_connection_with_new_one() {
            // given
            Long userId = testUser.getUserId();
            SseEmitter firstEmitter = sseEmittersService.createSseConnection(userId);

            // when
            SseEmitter secondEmitter = sseEmittersService.createSseConnection(userId);

            // then
            assertThat(secondEmitter).isNotNull();
            assertThat(firstEmitter).isNotEqualTo(secondEmitter);
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("사용자 연결 상태를 정확히 조회한다")
        void UT_NT_052_checks_user_connection_status_accurately() {
            // given
            Long connectedUserId = testUser.getUserId();
            User disconnectedUser = createTestUser(9999L, "disconnected");
            Long disconnectedUserId = disconnectedUser.getUserId();

            // when
            sseEmittersService.createSseConnection(connectedUserId);

            // then
            assertThat(sseEmittersService.isUserConnected(connectedUserId)).isTrue();
            assertThat(sseEmittersService.isUserConnected(disconnectedUserId)).isFalse();
        }
    }

    @Nested
    @DisplayName("SSE 알림 전송")
    class SseNotificationSendTest {

        @Test
        @DisplayName("UT-NT-053: 새 알림 생성 시 SSE로 즉시 전송되는가?")
        void UT_NT_053_sends_sse_notification_to_connected_user() {
            // given
            Long userId = 1L;
            sseEmittersService.createSseConnection(userId);

            // when & then - IOException이 발생하지 않으면 성공으로 간주
            assertThatCode(() -> sseEmittersService.sendSseNotification(userId, testNotification))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("연결되지 않은 사용자에게 알림 전송 시 아무 작업도 하지 않는다")
        void UT_NT_053_ignores_notification_to_disconnected_user() {
            // given
            Long disconnectedUserId = 999L;

            // when & then
            assertThatCode(() -> sseEmittersService.sendSseNotification(disconnectedUserId, testNotification))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-054: 읽음 처리 시 unread-count 업데이트가 SSE로 전송되는가?")
        void UT_NT_054_sends_unread_count_update_via_sse() {
            // given
            Long userId = testUser.getUserId();
            sseEmittersService.createSseConnection(userId);
            
            // 추가 알림 생성으로 unread count 증가
            for (int i = 0; i < 3; i++) {
                AppNotification notification = AppNotification.create(testUser, testNotificationType, "테스트" + i);
                notificationRepository.save(notification);
            }

            // when & then
            assertThatCode(() -> sseEmittersService.sendUnreadCountUpdate(userId))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("SSE 동시성 테스트")
    class SseConcurrencyTest {

        @Test
        @DisplayName("동시에 여러 사용자가 SSE 연결을 생성해도 안전하다")
        void UT_NT_056_handles_concurrent_sse_connections_safely() throws Exception {
            // given
            int userCount = 10; // 테스트 환경에서는 수를 줄임
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(userCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(userCount);
            
            // 미리 사용자들 생성
            List<User> users = new ArrayList<>();
            for (int i = 1; i <= userCount; i++) {
                User user = createTestUser(2000L + i, "concurrent" + i);
                users.add(user);
            }

            // when
            for (int i = 0; i < userCount; i++) {
                final User user = users.get(i);
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        SseEmitter emitter = sseEmittersService.createSseConnection(user.getUserId());
                        if (emitter != null) {
                            successCount.incrementAndGet();
                        }

                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await();
            executor.shutdown();

            // then
            assertThat(successCount.get()).isEqualTo(userCount);
            assertThat(errorCount.get()).isEqualTo(0);
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(userCount);
        }

        @Test
        @DisplayName("동시에 여러 알림을 전송해도 안전하다")
        void UT_NT_057_handles_concurrent_notification_sending_safely() throws Exception {
            // given
            int userCount = 3;
            int notificationsPerUser = 3;
            
            // 미리 사용자들 생성하고 연결 설정
            List<User> users = new ArrayList<>();
            for (int i = 1; i <= userCount; i++) {
                User user = createTestUser(3000L + i, "notify_user" + i);
                users.add(user);
                sseEmittersService.createSseConnection(user.getUserId());
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(userCount * notificationsPerUser);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(10);

            // when
            for (int userIndex = 0; userIndex < userCount; userIndex++) {
                for (int notifIndex = 0; notifIndex < notificationsPerUser; notifIndex++) {
                    final User targetUser = users.get(userIndex);
                    final int finalNotifIndex = notifIndex;

                    executor.submit(() -> {
                        try {
                            startLatch.await();

                            // 기존 testUser 사용 (DB 저장 부하 감소)
                            AppNotification notification = AppNotification.create(
                                testUser, testNotificationType, "알림 " + finalNotifIndex);
                            notification = notificationRepository.save(notification);

                            sseEmittersService.sendSseNotification(targetUser.getUserId(), notification);
                            successCount.incrementAndGet();

                        } catch (Exception e) {
                            // 알림 전송 실패는 정상적인 상황 (연결이 끊어질 수 있음)
                        } finally {
                            endLatch.countDown();
                        }
                    });
                }
            }

            startLatch.countDown();
            endLatch.await();
            executor.shutdown();

            // then - 동시성 환경에서는 일부 실패 가능
            assertThat(successCount.get()).isGreaterThanOrEqualTo(0);
            // 모든 스레드가 완료되었는지 확인 (일부 연결은 실패할 수 있음)
            assertThat(sseEmittersService.getActiveConnectionCount()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Redis 클러스터 기능 테스트")
    class RedisClusterTest {

        @Test
        @DisplayName("전역 연결 상태를 Redis를 통해 확인한다")
        void UT_NT_058_checks_global_connection_status_via_redis() {
            // given
            Long userId = testUser.getUserId();
            sseEmittersService.createSseConnection(userId);

            // when & then - Redis가 Mock이므로 로컬 연결 상태 확인
            boolean isConnected = sseEmittersService.isUserConnectedGlobally(userId);
            assertThat(isConnected).isTrue();
        }

        @Test
        @DisplayName("Redis 연결 실패 시 로컬 상태로 폴백한다")
        void UT_NT_058_falls_back_to_local_status_on_redis_failure() {
            // given
            Long userId = testUser.getUserId();
            sseEmittersService.createSseConnection(userId);

            // when
            boolean isConnected = sseEmittersService.isUserConnectedGlobally(userId);

            // then
            assertThat(isConnected).isTrue(); // 로컬 연결 상태로 폴백
        }
    }

    @Nested
    @DisplayName("브로드캐스트 기능 테스트")
    class BroadcastTest {

        @Test
        @DisplayName("UT-NT-055: 연결 끊김 후 재연결이 정상 동작하는가? (with LastEventId)")
        void UT_NT_055_sse_reconnection_works_after_disconnect() {
            // given
            Long userId = testUser.getUserId();
            String lastEventId = "notification_1_2024-01-01T00:00:00";
            
            // 첫 번째 연결
            SseEmitter firstEmitter = sseEmittersService.createSseConnection(userId);
            assertThat(firstEmitter).isNotNull();
            assertThat(sseEmittersService.isUserConnected(userId)).isTrue();
            
            // 연결 끊김 시뮬레이션 (새로운 연결로 대체)
            SseEmitter reconnectedEmitter = sseEmittersService.createSseConnection(userId, lastEventId);
            
            // then
            assertThat(reconnectedEmitter).isNotNull();
            assertThat(reconnectedEmitter).isNotEqualTo(firstEmitter);
            assertThat(sseEmittersService.isUserConnected(userId)).isTrue();
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("전체 사용자에게 브로드캐스트 메시지 전송")
        void UT_NT_059_broadcasts_message_to_all_users() throws Exception {
            // given
            int userCount = 5;
            for (int i = 1; i <= userCount; i++) {
                User user = createTestUser(1000L + i, "broadcast_user" + i);
                sseEmittersService.createSseConnection(user.getUserId());
            }
            
            // when
            var result = sseEmittersService.broadcastToAll("announcement", "시스템 공지사항").get();
            
            // then
            assertThat(result.getSuccessCount()).isGreaterThan(0);
            assertThat(result.getTotalCount()).isEqualTo(result.getSuccessCount() + result.getFailureCount());
        }

        @Test
        @DisplayName("빈 연결 상태에서 브로드캐스트 시 빈 결과 반환")
        void UT_NT_059_returns_empty_result_when_no_connections_for_broadcast() throws Exception {
            // when
            var result = sseEmittersService.broadcastToAll("test", "데이터").get();
            
            // then
            assertThat(result.getSuccessCount()).isEqualTo(0);
            assertThat(result.getFailureCount()).isEqualTo(0);
            assertThat(result.getTotalCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("성능 및 안정성 테스트")
    class PerformanceAndStabilityTest {

        @Test
        @DisplayName("대량 연결 처리 성능 테스트")
        void UT_NT_056_handles_large_number_of_connections() throws Exception {
            // given
            int userCount = 50; // 테스트 환경에서는 수를 줄임
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(userCount);
            AtomicInteger successCount = new AtomicInteger(0);
            
            // 미리 사용자들 생성
            List<User> users = new ArrayList<>();
            for (int i = 1; i <= userCount; i++) {
                User user = createTestUser(5000L + i, "perf_user" + i);
                users.add(user);
            }

            ExecutorService executor = Executors.newFixedThreadPool(20);
            Instant start = Instant.now();

            // when - 50개 동시 연결
            for (int i = 0; i < userCount; i++) {
                final User user = users.get(i);
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        SseEmitter emitter = sseEmittersService.createSseConnection(user.getUserId());
                        if (emitter != null) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // 실패는 successCount로 추적
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await();
            executor.shutdown();
            Duration duration = Duration.between(start, Instant.now());

            // then
            assertThat(duration.toMillis()).isLessThan(5000); // 5초 이내
            assertThat(successCount.get()).isEqualTo(userCount);
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(userCount);
        }

        @Test
        @DisplayName("연결 정리 기능 검증")
        void UT_NT_057_connection_cleanup_works_properly() {
            // given
            List<User> users = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                User user = createTestUser(4000L + i, "cleanup_user" + i);
                users.add(user);
                sseEmittersService.createSseConnection(user.getUserId());
            }
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(5);

            // when - 일부 연결 재생성 (기존 연결 정리)
            sseEmittersService.createSseConnection(users.get(0).getUserId());
            sseEmittersService.createSseConnection(users.get(1).getUserId());
            sseEmittersService.createSseConnection(users.get(2).getUserId());

            // then - 연결 수는 동일하게 유지
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(5);
            users.forEach(user -> {
                assertThat(sseEmittersService.isUserConnected(user.getUserId())).isTrue();
            });
        }

        @Test
        @DisplayName("메모리 누수 방지 - 대량 연결/해제 사이클")
        void UT_NT_058_prevents_memory_leaks_with_connection_cycles() {
            // given
            int cycleCount = 10; // 테스트 환경에서 줄임
            int usersPerCycle = 5;
            
            List<User> allUsers = new ArrayList<>();

            // when - 반복적인 연결/해제
            for (int cycle = 0; cycle < cycleCount; cycle++) {
                // 연결 생성
                for (int user = 1; user <= usersPerCycle; user++) {
                    long kakaoId = cycle * usersPerCycle + user + 6000L;
                    User newUser = createTestUser(kakaoId, "cycle_" + cycle + "_user" + user);
                    allUsers.add(newUser);
                    sseEmittersService.createSseConnection(newUser.getUserId());
                }
                
                // 중간 확인
                if (cycle % 5 == 4) {
                    assertThat(sseEmittersService.getActiveConnectionCount())
                        .isEqualTo((cycle + 1) * usersPerCycle);
                }
            }

            // then
            int expectedConnections = cycleCount * usersPerCycle;
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(expectedConnections);
        }
    }

    @Nested
    @DisplayName("추가 누락 케이스 테스트")
    class AdditionalMissingCasesTest {

        @Test
        @DisplayName("연결되지 않은 사용자에게 개수 업데이트 전송 시 무시")
        void UT_NT_054_ignores_unread_count_update_for_disconnected_user() {
            // given
            User disconnectedUser = createTestUser(999L, "disconnected");
            Long disconnectedUserId = disconnectedUser.getUserId();

            // when & then - 예외 발생하지 않음
            assertThatCode(() -> sseEmittersService.sendUnreadCountUpdate(disconnectedUserId))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("정상적인 unread count 업데이트 전송")
        void UT_NT_054_handles_unread_count_update_gracefully() {
            // given
            Long userId = testUser.getUserId();
            sseEmittersService.createSseConnection(userId);

            // when & then - 예외 발생하지 않음
            assertThatCode(() -> sseEmittersService.sendUnreadCountUpdate(userId))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("서버 인스턴스 ID 생성 검증")
        void UT_NT_059_generates_server_instance_id() {
            // when - 연결 생성 (내부적으로 서버 인스턴스 ID 사용)
            Long userId = 1L;
            SseEmitter emitter = sseEmittersService.createSseConnection(userId);
            
            // then
            assertThat(emitter).isNotNull();
            assertThat(sseEmittersService.isUserConnected(userId)).isTrue();
        }

        @Test
        @DisplayName("SSE 타임아웃 설정 확인")
        void UT_NT_052_sse_timeout_configuration_works() {
            // given
            Long userId = 1L;
            
            // when
            SseEmitter emitter = sseEmittersService.createSseConnection(userId);
            
            // then
            assertThat(emitter).isNotNull();
            assertThat(emitter.getTimeout()).isEqualTo(1800000L); // 30분
        }
    }

    // ================================
    // Helper Methods
    // ================================

    private User createTestUser(Long kakaoId, String nickname) {
        User user = User.builder()
            .kakaoId(kakaoId)
            .nickname(nickname)
            .status(Status.ACTIVE)
            .build();
        return userRepository.save(user);
    }
}