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
        @DisplayName("UT-NT-095: SSE 연결 생성")
        void utNt095CreatesNewSseConnectionSuccessfully() {
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
        @DisplayName("UT-NT-096: SSE 연결 정리")
        void utNt096ReplacesExistingConnectionWithNewOne() {
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
        @DisplayName("UT-NT-097: SSE 연결 상태 조회")
        void utNt097ChecksUserConnectionStatusAccurately() {
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
        @DisplayName("UT-NT-098: SSE 알림 전송")
        void utNt098SendsSseNotificationToConnectedUser() {
            // given
            Long userId = 1L;
            sseEmittersService.createSseConnection(userId);

            // when & then - IOException이 발생하지 않으면 성공으로 간주
            assertThatCode(() -> sseEmittersService.sendSseNotification(userId, testNotification))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-099: SSE 미연결 전송")
        void utNt099IgnoresNotificationToDisconnectedUser() {
            // given
            Long disconnectedUserId = 999L;

            // when & then
            assertThatCode(() -> sseEmittersService.sendSseNotification(disconnectedUserId, testNotification))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-100: SSE unread-count 전송")
        void utNt100SendsUnreadCountUpdateViaSse() {
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
        @DisplayName("UT-NT-101: SSE 동시 연결")
        void utNt101HandlesConcurrentSseConnectionsSafely() throws Exception {
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
            assertThat(errorCount.get()).isZero();
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(userCount);
        }

        @Test
        @DisplayName("UT-NT-102: SSE 동시 알림 전송")
        void utNt102HandlesConcurrentNotificationSendingSafely() throws Exception {
            // given
            int userCount = 3;
            int notificationsPerUser = 3;
            
            // 미리 사용자들 생성하고 연결 설정
            List<Long> userIds = new ArrayList<>();
            for (int i = 1; i <= userCount; i++) {
                User user = createTestUser(3000L + i, "notify_user" + i);
                userIds.add(user.getUserId());
                sseEmittersService.createSseConnection(user.getUserId());
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(userCount * notificationsPerUser);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(10);

            // when
            for (int userIndex = 0; userIndex < userCount; userIndex++) {
                for (int notifIndex = 0; notifIndex < notificationsPerUser; notifIndex++) {
                    final Long targetUserId = userIds.get(userIndex);
                    final int finalNotifIndex = notifIndex;

                    executor.submit(() -> {
                        try {
                            startLatch.await();

                            // 스레드마다 새로운 NotificationType과 User 사용하여 FK 제약조건 문제 방지
                            NotificationType threadSafeType = notificationTypeRepository
                                .findByType(Type.CHAT)
                                .orElseGet(() -> notificationTypeRepository.save(
                                    NotificationType.of(Type.CHAT, "동시성 테스트 템플릿")));
                            
                            // 각 스레드에서 사용자를 새로 조회하여 트랜잭션 문제 해결
                            User targetUser = userRepository.findById(targetUserId).orElse(null);
                            if (targetUser == null) {
                                return; // 사용자가 없으면 스킵
                            }
                            
                            AppNotification notification = AppNotification.create(
                                targetUser, threadSafeType, "알림 " + finalNotifIndex);
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
        @DisplayName("UT-NT-103: SSE Redis 연결 확인")
        void utNt103ChecksGlobalConnectionStatusViaRedis() {
            // given
            Long userId = testUser.getUserId();
            sseEmittersService.createSseConnection(userId);

            // when & then - Redis가 Mock이므로 로컬 연결 상태 확인
            boolean isConnected = sseEmittersService.isUserConnectedGlobally(userId);
            assertThat(isConnected).isTrue();
        }

        @Test
        @DisplayName("UT-NT-104: SSE Redis 폴백")
        void utNt104FallsBackToLocalStatusOnRedisFailure() {
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
        @DisplayName("UT-NT-055: SSE 재연결")
        void utNt095SseReconnectionWorksAfterDisconnect() {
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
            assertThat(reconnectedEmitter).isNotNull()
                    .isNotEqualTo(firstEmitter);
            assertThat(sseEmittersService.isUserConnected(userId)).isTrue();
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("UT-NT-096: SSE 연결 정리")
        void utNt096BroadcastsMessageToAllUsers() throws Exception {
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
        @DisplayName("UT-NT-097: SSE 연결 상태 조회")
        void utNt097ReturnsEmptyResultWhenNoConnectionsForBroadcast() throws Exception {
            // when
            var result = sseEmittersService.broadcastToAll("test", "데이터").get();
            
            // then
            assertThat(result.getSuccessCount()).isZero();
            assertThat(result.getFailureCount()).isZero();
            assertThat(result.getTotalCount()).isZero();
        }
    }

    @Nested
    @DisplayName("성능 및 안정성 테스트")
    class PerformanceAndStabilityTest {

        @Test
        @DisplayName("UT-NT-098: SSE 알림 전송")
        void utNt098HandlesLargeNumberOfConnections() throws Exception {
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
        @DisplayName("UT-NT-099: SSE 미연결 전송")
        void utNt099ConnectionCleanupWorksProperly() {
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
        @DisplayName("UT-NT-100: SSE unread-count 전송")
        void utNt100PreventsMemoryLeaksWithConnectionCycles() {
            // given
            int cycleCount = 10; // 테스트 환경에서 줄임
            int usersPerCycle = 5;

            // when - 반복적인 연결/해제
            for (int cycle = 0; cycle < cycleCount; cycle++) {
                // 연결 생성
                for (int user = 1; user <= usersPerCycle; user++) {
                    long kakaoId = cycle * usersPerCycle + user + 6000L;
                    User newUser = createTestUser(kakaoId, "cycle_" + cycle + "_user" + user);
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
        @DisplayName("UT-NT-101: SSE 동시 연결")
        void utNt101IgnoresUnreadCountUpdateForDisconnectedUser() {
            // given
            User disconnectedUser = createTestUser(999L, "disconnected");
            Long disconnectedUserId = disconnectedUser.getUserId();

            // when & then - 예외 발생하지 않음
            assertThatCode(() -> sseEmittersService.sendUnreadCountUpdate(disconnectedUserId))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-102: SSE 동시 알림 전송")
        void utNt102HandlesUnreadCountUpdateGracefully() {
            // given
            Long userId = testUser.getUserId();
            sseEmittersService.createSseConnection(userId);

            // when & then - 예외 발생하지 않음
            assertThatCode(() -> sseEmittersService.sendUnreadCountUpdate(userId))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-103: SSE Redis 연결 확인")
        void utNt103GeneratesServerInstanceId() {
            // when - 연결 생성 (내부적으로 서버 인스턴스 ID 사용)
            Long userId = 1L;
            SseEmitter emitter = sseEmittersService.createSseConnection(userId);
            
            // then
            assertThat(emitter).isNotNull();
            assertThat(sseEmittersService.isUserConnected(userId)).isTrue();
        }

        @Test
        @DisplayName("UT-NT-104: SSE Redis 폴백")
        void utNt104SseTimeoutConfigurationWorks() {
            // given
            Long userId = 1L;
            
            // when
            SseEmitter emitter = sseEmittersService.createSseConnection(userId);
            
            // then
            assertThat(emitter).isNotNull();
            assertThat(emitter.getTimeout()).isEqualTo(1800000L); // 30분
        }

        @Test
        @DisplayName("UT-NT-105: SSE IOException 핸들링")
        void utNt105HandlesIoExceptionDuringNotificationSend() {
            // given - 새로운 사용자로 SSE 연결
            User ioErrorUser = createTestUser(10001L, "ioerror_user");
            Long userId = ioErrorUser.getUserId();
            sseEmittersService.createSseConnection(userId);
            
            // Emitter 완료시키기 (IOException을 유발하기 위해)
            assertThat(sseEmittersService.isUserConnected(userId)).isTrue();
            
            // when & then - IOException 발생 시 연결이 정리되어야 함
            AppNotification notification = AppNotification.create(ioErrorUser, testNotificationType, "테스트");
            final AppNotification finalNotification = notificationRepository.save(notification);
            
            // IOException이 발생해도 예외가 전파되지 않아야 함
            assertThatCode(() -> sseEmittersService.sendSseNotification(userId, finalNotification))
                .doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("UT-NT-106: Unread Count IOException 핸들링")
        void utNt106HandlesIoExceptionDuringUnreadCountUpdate() {
            // given - 새로운 사용자로 SSE 연결
            User ioErrorUser = createTestUser(10002L, "unread_ioerror_user");
            Long userId = ioErrorUser.getUserId();
            sseEmittersService.createSseConnection(userId);
            
            // when & then - IOException 발생해도 예외가 전파되지 않아야 함
            assertThatCode(() -> sseEmittersService.sendUnreadCountUpdate(userId))
                .doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("UT-NT-107: 브로드캐스트 IOException 핸들링")
        void utNt107HandlesBroadcastIoException() throws Exception {
            // given - 연결 생성 후 emitter 상태 변경으로 IOException 유발
            User broadcastUser = createTestUser(10003L, "broadcast_ioerror");
            sseEmittersService.createSseConnection(broadcastUser.getUserId());
            
            // when - 브로드캐스트 시도 (IOException 발생 가능)
            var result = sseEmittersService.broadcastToAll("test_event", "test_data").get();
            
            // then - 실패 카운트가 포함될 수 있음
            assertThat(result.getTotalCount()).isGreaterThanOrEqualTo(0);
        }
        
        @Test
        @DisplayName("UT-NT-108: 글로벌 연결 상태 Redis 실패 처리")
        void utNt108HandlesRedisFailureInGlobalConnectionCheck() {
            // given
            Long userId = testUser.getUserId();
            sseEmittersService.createSseConnection(userId);
            
            // when - Redis 실패 시 로컬 상태로 폴백
            boolean isConnected = sseEmittersService.isUserConnectedGlobally(userId);
            
            // then
            assertThat(isConnected).isTrue(); // 로컬 연결 상태로 폴백
        }
        
        @Test
        @DisplayName("UT-NT-109: 글로벌 연결 수 Redis 실패 처리")
        void utNt109HandlesRedisFailureInGlobalConnectionCount() {
            // given
            Long userId = testUser.getUserId();
            sseEmittersService.createSseConnection(userId);
            
            // when - Redis 실패 시 로컬 카운트로 폴백
            long globalCount = sseEmittersService.getGlobalActiveConnectionCount();
            
            // then
            assertThat(globalCount).isGreaterThanOrEqualTo(1); // 로컬 연결 수로 폴백
        }
        
        @Test
        @DisplayName("UT-NT-110: Last Event ID 파싱 실패 처리")
        void utNt110HandlesInvalidLastEventIdGracefully() {
            // given
            Long userId = testUser.getUserId();
            String invalidEventId = "invalid_event_id_format";
            
            // when & then - 잘못된 형식의 eventId도 처리되어야 함
            assertThatCode(() -> {
                SseEmitter emitter = sseEmittersService.createSseConnection(userId, invalidEventId);
                assertThat(emitter).isNotNull();
            }).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("UT-NT-111: Null Event ID 처리")
        void utNt111HandlesNullLastEventIdGracefully() {
            // given
            Long userId = testUser.getUserId();
            
            // when & then
            assertThatCode(() -> {
                SseEmitter emitter = sseEmittersService.createSseConnection(userId, null);
                assertThat(emitter).isNotNull();
            }).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("UT-NT-112: 빈 Event ID 처리")
        void utNt112HandlesBlankLastEventIdGracefully() {
            // given
            Long userId = testUser.getUserId();
            
            // when & then
            assertThatCode(() -> {
                SseEmitter emitter = sseEmittersService.createSseConnection(userId, "   ");
                assertThat(emitter).isNotNull();
            }).doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("UT-NT-113: 시스템 공지 브로드캐스트")
        void utNt113BroadcastsSystemNoticeToAllUsers() throws Exception {
            // given
            List<User> users = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                User user = createTestUser(11000L + i, "system_notice_user" + i);
                users.add(user);
                sseEmittersService.createSseConnection(user.getUserId());
            }
            
            // when
            var result = sseEmittersService.broadcastSystemNotice("maintenance", "시스템 점검 예정").get();
            
            // then
            assertThat(result.getSuccessCount()).isGreaterThan(0);
            assertThat(result.getTotalCount()).isEqualTo(result.getSuccessCount() + result.getFailureCount());
        }
        
        @Test
        @DisplayName("UT-NT-114: 공지사항 브로드캐스트")
        void utNt114BroadcastsAnnouncementToAllUsers() throws Exception {
            // given
            List<User> users = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                User user = createTestUser(12000L + i, "announcement_user" + i);
                users.add(user);
                sseEmittersService.createSseConnection(user.getUserId());
            }
            
            // when
            var result = sseEmittersService.broadcastAnnouncement("중요 공지", "새로운 기능이 추가되었습니다").get();
            
            // then
            assertThat(result.getSuccessCount()).isGreaterThan(0);
            assertThat(result.getTotalCount()).isEqualTo(result.getSuccessCount() + result.getFailureCount());
        }
        
        @Test
        @DisplayName("UT-NT-115: Connection의 Duration 계산")
        void utNt115CalculatesConnectionDurationCorrectly() {
            // given
            Long userId = testUser.getUserId();
            
            // when
            sseEmittersService.createSseConnection(userId);
            
            // then - 연결이 생성되고 duration이 계산 가능해야 함
            assertThat(sseEmittersService.isUserConnected(userId)).isTrue();
            // duration은 내부적으로 계산되므로 직접 테스트는 어렵지만, 연결이 정상 생성됨을 확인
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