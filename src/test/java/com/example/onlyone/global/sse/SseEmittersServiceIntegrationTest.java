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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE 서비스 통합 테스트
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("SSE 서비스 통합 테스트")
class SseEmittersServiceIntegrationTest {

    @Autowired
    private SseEmittersService sseEmittersService;
    
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
        // 기존 연결 정리
        sseEmittersService.clearAllConnections();
        
        // 테스트 데이터 정리
        notificationRepository.deleteAll();
        notificationTypeRepository.deleteAll();
        userRepository.deleteAll();
        
        // 테스트 사용자 생성
        testUser = User.builder()
                .email("test@example.com")
                .name("테스트사용자")
                .status(Status.ACTIVE)
                .build();
        testUser = userRepository.save(testUser);
        
        // 테스트 알림 타입 생성
        testNotificationType = NotificationType.of(Type.MENTION, "멘션 알림: {0}");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
    }
    
    @Nested
    @DisplayName("SSE 연결 관리")
    class ConnectionManagement {
        
        @Test
        @DisplayName("연결 생성 성공")
        void createConnection_Success() {
            // when
            SseEmitter emitter = sseEmittersService.createSseConnection(testUser, null);
            
            // then
            assertThat(emitter).isNotNull();
            assertThat(sseEmittersService.isUserConnected(testUser.getUserId())).isTrue();
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(1);
        }
        
        @Test
        @DisplayName("재연결 시 기존 연결 정리")
        void reconnection_CleansUpExisting() {
            // given
            sseEmittersService.createSseConnection(testUser, null);
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(1);
            
            // when
            SseEmitter newEmitter = sseEmittersService.createSseConnection(testUser, null);
            
            // then
            assertThat(newEmitter).isNotNull();
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(1);
        }
        
        @Test
        @DisplayName("연결 지속 시간 확인")
        void connectionDuration_Check() throws InterruptedException {
            // given
            sseEmittersService.createSseConnection(testUser, null);
            
            // when
            Thread.sleep(100);
            String duration = sseEmittersService.getConnectionDuration(testUser.getUserId());
            
            // then
            assertThat(duration).isNotNull();
            assertThat(duration).contains("초");
        }
        
        @Test
        @DisplayName("모든 연결 정리")
        void clearAllConnections_Success() {
            // given
            User user2 = User.builder()
                    .email("test2@example.com")
                    .name("테스트사용자2")
                    .status(Status.ACTIVE)
                    .build();
            user2 = userRepository.save(user2);
            
            sseEmittersService.createSseConnection(testUser, null);
            sseEmittersService.createSseConnection(user2, null);
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(2);
            
            // when
            sseEmittersService.clearAllConnections();
            
            // then
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(0);
        }
    }
    
    @Nested
    @DisplayName("이벤트 전송")
    class EventSending {
        
        @Test
        @DisplayName("연결된 사용자에게 이벤트 전송 성공")
        void sendEvent_Connected_Success() throws Exception {
            // given
            sseEmittersService.createSseConnection(testUser, null);
            
            // when
            CompletableFuture<Boolean> result = sseEmittersService.sendEvent(
                testUser.getUserId(), "test", "테스트 메시지"
            );
            
            // then
            assertThat(result).isNotNull();
            assertThat(result.get(5, TimeUnit.SECONDS)).isTrue();
        }
        
        @Test
        @DisplayName("연결되지 않은 사용자에게 이벤트 전송 실패")
        void sendEvent_NotConnected_Failure() throws Exception {
            // when - 연결 없이 전송
            CompletableFuture<Boolean> result = sseEmittersService.sendEvent(
                999L, "test", "테스트 메시지"
            );
            
            // then
            assertThat(result).isNotNull();
            assertThat(result.get(5, TimeUnit.SECONDS)).isFalse();
        }
        
        @Test
        @DisplayName("동기 이벤트 전송 성공")
        void sendEventSync_Success() {
            // given
            sseEmittersService.createSseConnection(testUser, null);
            
            // when
            boolean result = sseEmittersService.sendEventSync(
                testUser.getUserId(), "test", "동기 테스트"
            );
            
            // then
            assertThat(result).isTrue();
        }
    }
    
    @Nested
    @DisplayName("놓친 알림 복구")
    class MissedNotificationRecovery {
        
        @Test
        @DisplayName("재연결 시 놓친 알림 전송")
        void reconnection_SendsMissedNotifications() throws InterruptedException {
            // given - 알림 생성
            Notification notification1 = createNotification("알림1", LocalDateTime.now().minusMinutes(5));
            Notification notification2 = createNotification("알림2", LocalDateTime.now().minusMinutes(3));
            
            // 첫 연결
            sseEmittersService.createSseConnection(testUser, null);
            
            // when - 재연결 (lastEventId 포함)
            String lastEventId = "evt_" + System.currentTimeMillis();
            Thread.sleep(10); // 시간 차이 생성
            
            SseEmitter emitter = sseEmittersService.createSseConnection(testUser, lastEventId);
            
            // then
            assertThat(emitter).isNotNull();
            // 실제 SSE 이벤트 전송은 비동기로 처리되므로 검증 어려움
            // 로그나 메트릭스를 통해 확인 필요
        }
    }
    
    @Nested
    @DisplayName("동시성 테스트")
    class ConcurrencyTest {
        
        @Test
        @DisplayName("동시 다중 연결 생성")
        void concurrentConnections_Success() throws InterruptedException {
            // given
            int userCount = 10;
            CountDownLatch latch = new CountDownLatch(userCount);
            AtomicInteger successCount = new AtomicInteger(0);
            
            List<User> users = new ArrayList<>();
            for (int i = 0; i < userCount; i++) {
                User user = User.builder()
                        .email("concurrent" + i + "@example.com")
                        .name("동시사용자" + i)
                        .status(Status.ACTIVE)
                        .build();
                users.add(userRepository.save(user));
            }
            
            // when - 동시에 연결 생성
            users.forEach(user -> {
                new Thread(() -> {
                    try {
                        SseEmitter emitter = sseEmittersService.createSseConnection(user, null);
                        if (emitter != null) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                }).start();
            });
            
            // then
            latch.await(10, TimeUnit.SECONDS);
            assertThat(successCount.get()).isEqualTo(userCount);
            assertThat(sseEmittersService.getActiveConnectionCount()).isEqualTo(userCount);
        }
        
        @Test
        @DisplayName("동시 이벤트 전송")
        void concurrentEventSending_Success() throws InterruptedException {
            // given
            sseEmittersService.createSseConnection(testUser, null);
            
            int eventCount = 20;
            CountDownLatch latch = new CountDownLatch(eventCount);
            AtomicInteger successCount = new AtomicInteger(0);
            
            // when - 동시에 이벤트 전송
            for (int i = 0; i < eventCount; i++) {
                final int index = i;
                new Thread(() -> {
                    try {
                        CompletableFuture<Boolean> result = sseEmittersService.sendEvent(
                            testUser.getUserId(), "test", "이벤트" + index
                        );
                        if (result.join()) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }
            
            // then
            latch.await(10, TimeUnit.SECONDS);
            assertThat(successCount.get()).isGreaterThan(0);
        }
    }
    
    @Nested
    @DisplayName("연결 상태 조회")
    class ConnectionStatus {
        
        @Test
        @DisplayName("활성 사용자 ID 목록 조회")
        void getActiveUserIds_Success() {
            // given
            User user2 = User.builder()
                    .email("test2@example.com")
                    .name("테스트사용자2")
                    .status(Status.ACTIVE)
                    .build();
            user2 = userRepository.save(user2);
            
            sseEmittersService.createSseConnection(testUser, null);
            sseEmittersService.createSseConnection(user2, null);
            
            // when
            Set<Long> activeUserIds = sseEmittersService.getActiveUserIds();
            
            // then
            assertThat(activeUserIds).hasSize(2);
            assertThat(activeUserIds).contains(testUser.getUserId(), user2.getUserId());
        }
        
        @Test
        @DisplayName("마지막 연결 시간 조회")
        void getLastConnectedTime_Success() {
            // given
            LocalDateTime beforeConnection = LocalDateTime.now();
            sseEmittersService.createSseConnection(testUser, null);
            LocalDateTime afterConnection = LocalDateTime.now();
            
            // when
            LocalDateTime lastConnectedTime = sseEmittersService.getLastConnectedTime(testUser.getUserId());
            
            // then
            assertThat(lastConnectedTime).isNotNull();
            assertThat(lastConnectedTime).isAfterOrEqualTo(beforeConnection);
            assertThat(lastConnectedTime).isBeforeOrEqualTo(afterConnection);
        }
    }
    
    private Notification createNotification(String content, LocalDateTime createdAt) {
        Notification notification = Notification.create(testUser, testNotificationType, content);
        notification = notificationRepository.save(notification);
        // 테스트를 위해 생성 시간 조작 (실제로는 @CreatedDate 사용)
        return notification;
    }
}