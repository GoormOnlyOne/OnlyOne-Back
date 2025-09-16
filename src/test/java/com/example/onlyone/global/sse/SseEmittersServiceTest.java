package com.example.onlyone.global.sse;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.sse.service.SseEmittersService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * SSE 서비스 통합 테스트
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
    private SseEmittersService sseEmittersService;

    private User testUser;
    private NotificationType testNotificationType;

    @BeforeEach
    void setUp() {
        // 테스트 데이터 정리
        sseEmittersService.clearAllConnections();
        notificationRepository.deleteAll();
        notificationTypeRepository.deleteAll();
        userRepository.deleteAll();

        // 테스트 사용자 생성
        testUser = User.builder()
                .kakaoId(12345L)
                .nickname("테스트사용자")
                .profileImage("test-profile.jpg")
                .status(Status.ACTIVE)
                .build();
        testUser = userRepository.save(testUser);

        // 테스트 알림 타입 생성
        testNotificationType = NotificationType.of(Type.CHAT, "새로운 메시지가 도착했습니다: {0}");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
    }

    @Nested
    @DisplayName("SSE 연결 생성")
    class CreateSseConnection {
        
        @Test
        @DisplayName("성공")
        void success() {
            // when
            SseEmitter emitter = sseEmittersService.createSseConnection(testUser.getKakaoId(), null);

            // then
            assertThat(emitter).isNotNull();
            assertThat(sseEmittersService.isUserConnected(testUser.getKakaoId())).isTrue();
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(1);
        }
        
        @Test
        @DisplayName("Last-Event-ID와 함께 연결 생성 성공")
        void withLastEventId_success() {
            // given
            String lastEventId = "evt_1234567890_abcd1234";

            // when
            SseEmitter emitter = sseEmittersService.createSseConnection(testUser.getKakaoId(), lastEventId);

            // then
            assertThat(emitter).isNotNull();
            assertThat(sseEmittersService.isUserConnected(testUser.getKakaoId())).isTrue();
        }
        
        @Test
        @DisplayName("기존 연결이 있을 때 새 연결 생성 시 기존 연결 정리")
        void existingConnection_cleansUpOld() {
            // given
            sseEmittersService.createSseConnection(testUser.getKakaoId(), null);
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(1);

            // when
            sseEmittersService.createSseConnection(testUser.getKakaoId(), null);

            // then
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(1);
            assertThat(sseEmittersService.isUserConnected(testUser.getKakaoId())).isTrue();
        }
    }

    @Nested
    @DisplayName("SSE 이벤트 전송")
    class SendEvent {
        
        @Test
        @DisplayName("성공")
        void success() {
            // given
            sseEmittersService.createSseConnection(testUser.getKakaoId(), null);
            Notification notification = createTestNotification();

            // when & then
            assertThatCode(() -> sseEmittersService.sendEvent(testUser.getKakaoId(), "notification", notification))
                    .doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("연결되지 않은 사용자에게 전송 시 예외 발생 안함")
        void noConnection_doesNotThrow() {
            // given
            Notification notification = createTestNotification();

            // when & then
            assertThatCode(() -> sseEmittersService.sendEvent(999L, "notification", notification))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("연결 관리")
    class ConnectionManagement {
        
        @Test
        @DisplayName("연결 상태 확인 성공")
        void checkConnectionStatus_success() {
            // given
            assertThat(sseEmittersService.isUserConnected(testUser.getKakaoId())).isFalse();

            // when
            sseEmittersService.createSseConnection(testUser.getKakaoId(), null);

            // then
            assertThat(sseEmittersService.isUserConnected(testUser.getKakaoId())).isTrue();
        }
        
        @Test
        @DisplayName("모든 연결 정리 성공")
        void clearAllConnections_success() {
            // given
            sseEmittersService.createSseConnection(testUser.getKakaoId(), null);
            User anotherUser = createAnotherUser();
            sseEmittersService.createSseConnection(anotherUser.getKakaoId(), null);
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(2);

            // when
            sseEmittersService.clearAllConnections();

            // then
            assertThat(sseEmittersService.getActiveConnectionCount()).isZero();
            assertThat(sseEmittersService.isUserConnected(testUser.getKakaoId())).isFalse();
            assertThat(sseEmittersService.isUserConnected(anotherUser.getKakaoId())).isFalse();
        }
        
        @Test
        @DisplayName("동시 연결 성공")
        void concurrentConnections_success() throws InterruptedException {
            // given
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            // when
            for (int i = 0; i < threadCount; i++) {
                final long userId = i + 1000L;
                User user = User.builder()
                        .kakaoId(userId)
                        .nickname("사용자" + i)
                        .profileImage("profile" + i + ".jpg")
                        .status(Status.ACTIVE)
                        .build();
                userRepository.save(user);

                executor.submit(() -> {
                    try {
                        SseEmitter emitter = sseEmittersService.createSseConnection(user.getKakaoId(), null);
                        if (emitter != null) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // 예외 발생 시 무시
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // then
            latch.await(10, TimeUnit.SECONDS);
            assertThat(successCount.get()).isEqualTo(threadCount);
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(threadCount);

            executor.shutdown();
        }
    }

    private Notification createTestNotification() {
        Notification notification = Notification.create(testUser, testNotificationType, "테스트 알림");
        return notificationRepository.save(notification);
    }

    private User createAnotherUser() {
        User anotherUser = User.builder()
                .kakaoId(67890L)
                .nickname("다른사용자")
                .profileImage("another-profile.jpg")
                .status(Status.ACTIVE)
                .build();
        return userRepository.save(anotherUser);
    }
}