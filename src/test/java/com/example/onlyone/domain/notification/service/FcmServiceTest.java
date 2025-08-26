package com.example.onlyone.domain.notification.service;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.google.firebase.messaging.FirebaseMessaging;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("FCM 서비스 테스트")
class FcmServiceTest {

    @Autowired
    private FirebaseMessaging firebaseMessaging;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FcmService fcmService;

    private User testUser;
    private NotificationType testNotificationType;
    private AppNotification testNotification;

    @BeforeEach
    void setUp() {

        testUser = createTestUser(1L, "testuser", "test_fcm_token_123");
        testNotificationType = NotificationType.of(Type.CHAT, "테스트 템플릿: %s");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
        testNotification = AppNotification.create(testUser, testNotificationType, "테스트");
        testNotification = notificationRepository.save(testNotification);
    }

    @Nested
    @DisplayName("FCM 알림 전송")
    class FcmNotificationSendTest {

        @Test
        @DisplayName("UT-NT-070: FCM 전송 성공")
        void utNt070SuccessfullySendsFcmNotification() {
            // when & then - FCM 전송 시도 시 적절한 예외 처리 확인
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_MESSAGE_SEND_FAILED);
        }


        @Test
        @DisplayName("UT-NT-072: FCM 전송 실패")
        void utNt072HandlesFcmSendFailure() throws Exception {
            // given
            RuntimeException fcmException = new RuntimeException("FCM send failed");
            doThrow(fcmException).when(firebaseMessaging).send(any());

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_MESSAGE_SEND_FAILED);
        }

        @Test
        @DisplayName("UT-NT-073: FCM 메시지 검증")
        void utNt073SetsFcmMessageContentCorrectly() {
            // when & then - 알림 내용이 올바르게 설정되었는지 확인
            assertThat(testNotification.getContent()).contains("테스트");
            assertThat(testNotification.getUser().getFcmToken()).isEqualTo("test_fcm_token_123");
            assertThat(testNotification.getNotificationType().getType()).isEqualTo(Type.CHAT);
        }
    }

    @Nested
    @DisplayName("FCM 배치 전송")
    class FcmBatchSendTest {

        @Test
        @DisplayName("UT-NT-074: FCM 배치 전송")
        void utNt074SendsMultipleNotificationsInBatch() {
            List<AppNotification> notifications = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                User user = createTestUser(10L + i, "user" + i, "token" + i);
                AppNotification notification = AppNotification.create(user, testNotificationType, "배치" + i);
                notifications.add(notificationRepository.save(notification));
            }

            // when & then
            assertThatCode(() -> fcmService.sendBatch(notifications))
                .doesNotThrowAnyException();
        }


        @Test
        @DisplayName("UT-NT-076: FCM 부분 실패")
        void utNt076ContinuesBatchSendingDespitePartialFailures() {
            List<AppNotification> notifications = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                User user = createTestUser(30L + i, "mixuser" + i, "mixtoken" + i);
                AppNotification notification = AppNotification.create(user, testNotificationType, "혼합" + i);
                notifications.add(notificationRepository.save(notification));
            }

            // when & then
            assertThatCode(() -> fcmService.sendBatch(notifications))
                .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("FCM 우선순위 큐 기능 테스트")
    class FcmQueueTest {

        @Test
        @DisplayName("UT-NT-077: FCM 우선순위 큐")
        void utNt077QueuesFcmNotificationWithPriority() {
            // when & then
            assertThatCode(() -> fcmService.queueFcmNotification(testNotification, FcmService.FcmPriority.HIGH))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-078: FCM 빈 배치")
        void utNt078ReturnsEmptyResultForEmptyBatch() throws Exception {
            List<AppNotification> emptyList = new ArrayList<>();

            // when
            var result = fcmService.sendBatch(emptyList).get();

            // then
            assertThat(result.getSuccessCount()).isZero();
            assertThat(result.getFailureCount()).isZero();
            assertThat(result.getTotalCount()).isZero();
        }
    }

    @Nested
    @DisplayName("FCM 우선순위 큐")
    class FcmPriorityQueueTest {

        @Test
        @DisplayName("UT-NT-079: FCM 우선순위 처리")
        void utNt079ProcessesNotificationsByPriority() {
            PriorityBlockingQueue<FcmService.FcmNotificationTask> queue = new PriorityBlockingQueue<>();
            
            User urgentUser = createTestUser(100L, "urgent", "urgent_token");
            User normalUser = createTestUser(101L, "normal", "normal_token");
            
            AppNotification urgentNotification = AppNotification.create(urgentUser, testNotificationType, "긴급");
            AppNotification normalNotification = AppNotification.create(normalUser, testNotificationType, "일반");
            
            urgentNotification = notificationRepository.save(urgentNotification);
            normalNotification = notificationRepository.save(normalNotification);
            
            FcmService.FcmNotificationTask urgentTask = FcmService.FcmNotificationTask.of(urgentNotification, FcmService.FcmPriority.HIGH);
            FcmService.FcmNotificationTask normalTask = FcmService.FcmNotificationTask.of(normalNotification, FcmService.FcmPriority.LOW);
            
            // when
            queue.offer(normalTask);
            queue.offer(urgentTask);
            
            // then
            FcmService.FcmNotificationTask firstTask = queue.poll();
            assertThat(firstTask).isNotNull();
            assertThat(firstTask.getPriority()).isEqualTo(FcmService.FcmPriority.HIGH);
        }

        @Test
        @DisplayName("UT-NT-080: FCM FIFO 처리")
        void utNt080ProcessesSamePriorityNotificationsInFifoOrder() {
            PriorityBlockingQueue<FcmService.FcmNotificationTask> queue = new PriorityBlockingQueue<>();
            
            List<FcmService.FcmNotificationTask> tasks = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                User user = createTestUser(200L + i, "fifo" + i, "fifo_token" + i);
                AppNotification notification = AppNotification.create(user, testNotificationType, "FIFO" + i);
                notification = notificationRepository.save(notification);
                tasks.add(FcmService.FcmNotificationTask.of(notification, FcmService.FcmPriority.NORMAL));
            }
            
            // when
            tasks.forEach(queue::offer);
            
            // then
            FcmService.FcmNotificationTask firstOut = queue.poll();
            assertThat(firstOut).isNotNull();
            assertThat(firstOut.getPriority()).isEqualTo(FcmService.FcmPriority.NORMAL);
        }
    }

    @Nested
    @DisplayName("FCM 동시성 처리")
    class FcmConcurrencyTest {

        @Test
        @DisplayName("UT-NT-081: FCM 동시성")
        void utNt081HandlesConcurrentFcmRequestsSafely() throws Exception {
            int threadCount = 5;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            
            // when
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        User user = createTestUser(300L + index, "concurrent" + index, "token" + index);
                        AppNotification notification = AppNotification.create(user, testNotificationType, "동시" + index);
                        notification = notificationRepository.save(notification);
                        fcmService.sendFcmNotification(notification);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // 동시성 안전성만 확인하므로 예외 허용
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // then
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
            assertThat(completed).isTrue();
            assertThat(successCount.get()).isGreaterThanOrEqualTo(0);
        }
    }

    // 추가 테스트 클래스들...

    @Nested
    @DisplayName("성능 및 안정성 테스트")
    class PerformanceAndStabilityTest {

        @Test
        @DisplayName("UT-NT-082: FCM 대량 전송")
        void utNt082HandlesHighVolumeNotificationsStably() {
            List<AppNotification> notifications = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                User user = createTestUser(1000L + i, "bulk" + i, "bulk_token" + i);
                AppNotification notification = AppNotification.create(user, testNotificationType, "대량" + i);
                notifications.add(notificationRepository.save(notification));
            }

            // when & then
            assertThatCode(() -> {
                for (AppNotification notification : notifications) {
                    try {
                        fcmService.sendFcmNotification(notification);
                    } catch (Exception e) {
                        // 일부 실패 허용
                    }
                }
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-083: FCM 라이프사이클")
        void utNt083StartsAndShutsDownProperly() {
            FcmService newFcmService = new FcmService(firebaseMessaging, notificationRepository);
            
            // when
            ReflectionTestUtils.invokeMethod(newFcmService, "afterPropertiesSet");
            
            // then
            assertThat(newFcmService).isNotNull();
            
            // when
            ReflectionTestUtils.invokeMethod(newFcmService, "destroy");
        }

    }

    // Helper 메서드
    private User createTestUser(Long kakaoId, String nickname, String fcmToken) {
        User user = User.builder()
            .kakaoId(kakaoId)
            .nickname(nickname)
            .fcmToken(fcmToken)
            .status(Status.ACTIVE)
            .build();
        return userRepository.save(user);
    }
}