package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * SSE 서비스 테스트 - 실시간 알림 전송 및 동시성 검증
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SSE 서비스 테스트")
class SseEmittersServiceTest {

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private SetOperations<String, Object> setOperations;
    @InjectMocks
    private SseEmittersService sseEmittersService;

    private User testUser;
    private NotificationType testNotificationType;
    private AppNotification testNotification;

    @BeforeEach
    void setUp() {
        // SSE 설정값 주입
        ReflectionTestUtils.setField(sseEmittersService, "sseTimeoutMillis", 1800000L);

        // Redis Mock 설정
        given(redisTemplate.opsForSet()).willReturn(setOperations);

        // 테스트 데이터 설정
        testUser = createTestUser(1L, "testuser");
        testNotificationType = NotificationType.of(Type.CHAT, "%s님이 메시지를 보냈습니다.");
        testNotification = AppNotification.create(testUser, testNotificationType, "홍길동");
        ReflectionTestUtils.setField(testNotification, "id", 1L);
        ReflectionTestUtils.setField(testNotification, "createdAt", LocalDateTime.now());
    }

    @Nested
    @DisplayName("SSE 연결 관리")
    class SseConnectionManagementTest {

        @Test
        @DisplayName("새로운 SSE 연결을 성공적으로 생성한다")
        void 새로운_SSE_연결을_성공적으로_생성한다() {
            // given
            Long userId = 1L;

            // when
            SseEmitter emitter = sseEmittersService.createSseConnection(userId);

            // then
            assertThat(emitter).isNotNull();
            assertThat(sseEmittersService.isUserConnected(userId)).isTrue();
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("기존 연결이 있는 상태에서 새 연결 생성 시 기존 연결을 정리한다")
        void 기존_연결이_있는_상태에서_새_연결_생성_시_기존_연결을_정리한다() {
            // given
            Long userId = 1L;
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
        void 사용자_연결_상태를_정확히_조회한다() {
            // given
            Long connectedUserId = 1L;
            Long disconnectedUserId = 2L;

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
        @DisplayName("연결된 사용자에게 SSE 알림을 전송한다")
        void 연결된_사용자에게_SSE_알림을_전송한다() {
            // given
            Long userId = 1L;
            sseEmittersService.createSseConnection(userId);

            // when & then - IOException이 발생하지 않으면 성공으로 간주
            assertThatCode(() -> sseEmittersService.sendSseNotification(userId, testNotification))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("연결되지 않은 사용자에게 알림 전송 시 아무 작업도 하지 않는다")
        void 연결되지_않은_사용자에게_알림_전송_시_아무_작업도_하지_않는다() {
            // given
            Long disconnectedUserId = 999L;

            // when & then
            assertThatCode(() -> sseEmittersService.sendSseNotification(disconnectedUserId, testNotification))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("읽지 않은 개수 업데이트를 전송한다")
        void 읽지_않은_개수_업데이트를_전송한다() {
            // given
            Long userId = 1L;
            Long unreadCount = 5L;
            sseEmittersService.createSseConnection(userId);
            given(notificationRepository.countUnreadByUserId(userId)).willReturn(unreadCount);

            // when & then
            assertThatCode(() -> sseEmittersService.sendUnreadCountUpdate(userId))
                .doesNotThrowAnyException();

            then(notificationRepository).should().countUnreadByUserId(userId);
        }
    }

    @Nested
    @DisplayName("SSE 동시성 테스트")
    class SseConcurrencyTest {

        @Test
        @DisplayName("동시에 여러 사용자가 SSE 연결을 생성해도 안전하다")
        void 동시에_여러_사용자가_SSE_연결을_생성해도_안전하다() throws Exception {
            // given
            int userCount = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(userCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(userCount);

            // when
            for (int i = 1; i <= userCount; i++) {
                final long userId = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        SseEmitter emitter = sseEmittersService.createSseConnection(userId);
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
        void 동시에_여러_알림을_전송해도_안전하다() throws Exception {
            // given
            int userCount = 10;
            int notificationsPerUser = 5;

            // 사용자 연결 설정
            for (int i = 1; i <= userCount; i++) {
                sseEmittersService.createSseConnection((long) i);
            }

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(userCount * notificationsPerUser);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(20);

            // when
            for (int userId = 1; userId <= userCount; userId++) {
                for (int notifIndex = 0; notifIndex < notificationsPerUser; notifIndex++) {
                    final long finalUserId = userId;
                    final int finalNotifIndex = notifIndex;

                    executor.submit(() -> {
                        try {
                            startLatch.await();

                            User user = createTestUser(finalUserId, "user" + finalUserId);
                            AppNotification notification = AppNotification.create(
                                user, testNotificationType, "알림 " + finalNotifIndex);
                            ReflectionTestUtils.setField(notification, "id",
                                finalUserId * 1000 + finalNotifIndex);
                            ReflectionTestUtils.setField(notification, "createdAt", LocalDateTime.now());

                            sseEmittersService.sendSseNotification(finalUserId, notification);
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

            // then
            assertThat(successCount.get()).isGreaterThan(0);
            // 모든 사용자가 여전히 연결되어 있어야 함
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(userCount);
        }
    }

    @Nested
    @DisplayName("Redis 클러스터 기능 테스트")
    class RedisClusterTest {

        @Test
        @DisplayName("전역 연결 상태를 Redis를 통해 확인한다")
        void 전역_연결_상태를_Redis를_통해_확인한다() {
            // given
            Long userId = 1L;
            given(setOperations.isMember(anyString(), eq(userId.toString()))).willReturn(true);

            // when
            boolean isConnected = sseEmittersService.isUserConnectedGlobally(userId);

            // then
            assertThat(isConnected).isTrue();
            then(setOperations).should().isMember(anyString(), eq(userId.toString()));
        }

        @Test
        @DisplayName("Redis 연결 실패 시 로컬 상태로 폴백한다")
        void Redis_연결_실패_시_로컬_상태로_폴백한다() {
            // given
            Long userId = 1L;
            sseEmittersService.createSseConnection(userId);
            given(setOperations.isMember(anyString(), anyString())).willThrow(new RuntimeException("Redis connection failed"));

            // when
            boolean isConnected = sseEmittersService.isUserConnectedGlobally(userId);

            // then
            assertThat(isConnected).isTrue(); // 로컬 연결 상태로 폴백
        }
    }

    // ================================
    // Helper Methods
    // ================================

    private User createTestUser(Long id, String nickname) {
        return User.builder()
            .userId(id)
            .kakaoId(12345L + id)
            .nickname(nickname)
            .status(Status.ACTIVE)
            .build();
    }
}