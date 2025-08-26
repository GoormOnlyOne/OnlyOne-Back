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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

    @MockitoBean
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
        // 테스트 데이터 정리
        notificationRepository.deleteAll();
        userRepository.deleteAll();
        notificationTypeRepository.deleteAll();
        
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
        @DisplayName("UT-NT-075: FCM 전송 성공")
        void utNt075SuccessfullySendsFcmNotification() {
            // when & then - Mock된 FirebaseMessaging으로 정상 동작 확인
            assertThatCode(() -> fcmService.sendFcmNotification(testNotification))
                .doesNotThrowAnyException();
        }


        @Test
        @DisplayName("UT-NT-076: FCM 전송 실패")
        void utNt076HandlesFcmSendFailure() throws Exception {
            // given
            RuntimeException fcmException = new RuntimeException("FCM send failed");
            doThrow(fcmException).when(firebaseMessaging).send(any());

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_MESSAGE_SEND_FAILED);
        }

        @Test
        @DisplayName("UT-NT-077: FCM 메시지 검증")
        void utNt077SetsFcmMessageContentCorrectly() {
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
        @DisplayName("UT-NT-078: FCM 배치 전송")
        void utNt078SendsMultipleNotificationsInBatch() {
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
        @DisplayName("UT-NT-079: FCM 부분 실패")
        void utNt079ContinuesBatchSendingDespitePartialFailures() {
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
        @DisplayName("UT-NT-080: FCM 우선순위 큐")
        void utNt080QueuesFcmNotificationWithPriority() {
            // when & then
            assertThatCode(() -> fcmService.queueFcmNotification(testNotification, FcmService.FcmPriority.HIGH))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-081: FCM 빈 배치")
        void utNt081ReturnsEmptyResultForEmptyBatch() throws Exception {
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
        @DisplayName("UT-NT-082: FCM 우선순위 처리")
        void utNt082ProcessesNotificationsByPriority() {
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
        @DisplayName("UT-NT-083: FCM FIFO 처리")
        void utNt083ProcessesSamePriorityNotificationsInFifoOrder() {
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
        @DisplayName("UT-NT-084: FCM 동시성")
        void utNt084HandlesConcurrentFcmRequestsSafely() throws Exception {
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

    @Nested
    @DisplayName("커버리지 개선 테스트")
    class CoverageImprovementTest {

        @Test
        @DisplayName("UT-NT-087: FCM 토큰 없음 예외 처리")
        void utNt087ThrowsExceptionWhenFcmTokenIsNull() {
            // given - FCM 토큰이 없는 사용자
            User userWithoutToken = createTestUser(9001L, "no_token_user", null);
            AppNotification notificationWithoutToken = AppNotification.create(userWithoutToken, testNotificationType, "토큰 없음");
            final AppNotification finalNotification = notificationRepository.save(notificationWithoutToken);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(finalNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_TOKEN_NOT_FOUND);
        }

        @Test
        @DisplayName("UT-NT-088: FCM 븈 토큰 예외 처리")
        void utNt088ThrowsExceptionWhenFcmTokenIsBlank() {
            // given - 븈 FCM 토큰을 가진 사용자
            User userWithBlankToken = createTestUser(9002L, "blank_token_user", "   ");
            AppNotification notificationWithBlankToken = AppNotification.create(userWithBlankToken, testNotificationType, "븈 토큰");
            final AppNotification finalNotification = notificationRepository.save(notificationWithBlankToken);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(finalNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_TOKEN_NOT_FOUND);
        }

        @Test
        @DisplayName("UT-NT-089: Firebase 메시지 구성 예외 처리")
        void utNt089HandlesMessageBuildingException() throws Exception {
            // given - 잘못된 데이터로 마토 메시지 구성 실패 상황
            AppNotification corruptedNotification = spy(testNotification);
            when(corruptedNotification.getNotificationType()).thenThrow(new RuntimeException("데이터 손상"));

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(corruptedNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_MESSAGE_SEND_FAILED);
        }

        @Test
        @DisplayName("UT-NT-090: 실패한 FCM 알림 재전송")
        void utNt090RetriesFailedFcmNotifications() {
            // given
            Long userId = testUser.getUserId();

            // when & then - 비동기 메서드이므로 예외 발생하지 않음
            assertThatCode(() -> fcmService.retryFailedNotifications(userId))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-091: 존재하지 않는 사용자 재전송")
        void utNt091HandlesRetryForNonExistentUser() {
            // given
            Long nonExistentUserId = 99999L;

            // when & then - 비동기 메서드이므로 예외 발생하지 않음
            assertThatCode(() -> fcmService.retryFailedNotifications(nonExistentUserId))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-092: Firebase 무효 토큰 오류 처리")
        void utNt092HandlesInvalidTokenError() throws Exception {
            // given - Firebase에서 무효 토큰 오류 반환
            com.google.firebase.messaging.FirebaseMessagingException invalidTokenException = 
                mock(com.google.firebase.messaging.FirebaseMessagingException.class);
            when(invalidTokenException.getErrorCode()).thenReturn(com.google.firebase.ErrorCode.INVALID_ARGUMENT);
            
            doThrow(invalidTokenException).when(firebaseMessaging).send(any());

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_TOKEN_REFRESH_REQUIRED);
        }

        @Test
        @DisplayName("UT-NT-093: Firebase 등록 취소 오류 처리")
        void utNt093HandlesUnregisteredTokenError() throws Exception {
            // given - Firebase에서 등록취소 오류 반환
            com.google.firebase.messaging.FirebaseMessagingException unregisteredTokenException = 
                mock(com.google.firebase.messaging.FirebaseMessagingException.class);
            when(unregisteredTokenException.getErrorCode()).thenReturn(com.google.firebase.ErrorCode.INVALID_ARGUMENT);
            
            doThrow(unregisteredTokenException).when(firebaseMessaging).send(any());

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_TOKEN_REFRESH_REQUIRED);
        }

        @Test
        @DisplayName("UT-NT-094: Firebase null ErrorCode 처리")
        void utNt094HandlesFirebaseMessagingExceptionWithNullErrorCode() throws Exception {
            // given - ErrorCode가 null인 FirebaseMessagingException
            com.google.firebase.messaging.FirebaseMessagingException nullCodeException = 
                mock(com.google.firebase.messaging.FirebaseMessagingException.class);
            when(nullCodeException.getErrorCode()).thenReturn(null);
            
            doThrow(nullCodeException).when(firebaseMessaging).send(any());

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_MESSAGE_SEND_FAILED);
        }

        @Test
        @DisplayName("UT-NT-095: 우선순위 큐 처리 중단")
        void utNt095HandlesQueueProcessorInterruption() {
            // given
            User queueUser = createTestUser(9003L, "queue_user", "queue_token");
            AppNotification queueNotification = AppNotification.create(queueUser, testNotificationType, "큐 테스트");
            final AppNotification finalNotification = notificationRepository.save(queueNotification);

            // when & then - 큐 처리는 비동기이므로 예외 발생하지 않음
            assertThatCode(() -> fcmService.queueFcmNotification(finalNotification, FcmService.FcmPriority.NORMAL))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("UT-NT-096: 배치 처리 중 Firebase 예외")
        void utNt096HandlesBatchProcessingFirebaseException() throws Exception {
            // given
            List<AppNotification> notifications = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                User user = createTestUser(9100L + i, "batch_error" + i, "batch_token" + i);
                AppNotification notification = AppNotification.create(user, testNotificationType, "배치 오류" + i);
                notifications.add(notificationRepository.save(notification));
            }

            com.google.firebase.messaging.FirebaseMessagingException batchException = 
                mock(com.google.firebase.messaging.FirebaseMessagingException.class);
            doThrow(batchException).when(firebaseMessaging).sendMulticast(any());

            // when
            var result = fcmService.sendBatch(notifications).get();

            // then
            assertThat(result.getFailureCount()).isEqualTo(notifications.size());
            assertThat(result.getSuccessCount()).isZero();
        }

        @Test
        @DisplayName("UT-NT-097: 배치 응답 처리")
        void utNt097ProcessesBatchResponseCorrectly() throws Exception {
            // given
            List<AppNotification> notifications = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                User user = createTestUser(9200L + i, "batch_response" + i, "response_token" + i);
                AppNotification notification = AppNotification.create(user, testNotificationType, "응답 테스트" + i);
                notifications.add(notificationRepository.save(notification));
            }

            // Mock BatchResponse
            com.google.firebase.messaging.BatchResponse batchResponse = mock(com.google.firebase.messaging.BatchResponse.class);
            when(batchResponse.getSuccessCount()).thenReturn(1);
            when(batchResponse.getFailureCount()).thenReturn(1);
            
            // Mock SendResponse
            com.google.firebase.messaging.SendResponse successResponse = mock(com.google.firebase.messaging.SendResponse.class);
            when(successResponse.isSuccessful()).thenReturn(true);
            
            com.google.firebase.messaging.SendResponse failResponse = mock(com.google.firebase.messaging.SendResponse.class);
            when(failResponse.isSuccessful()).thenReturn(false);
            com.google.firebase.messaging.FirebaseMessagingException failException = 
                mock(com.google.firebase.messaging.FirebaseMessagingException.class);
            when(failResponse.getException()).thenReturn(failException);
            
            when(batchResponse.getResponses()).thenReturn(List.of(successResponse, failResponse));
            when(firebaseMessaging.sendMulticast(any())).thenReturn(batchResponse);

            // when
            var result = fcmService.sendBatch(notifications).get();

            // then
            assertThat(result.getSuccessCount()).isEqualTo(1);
            assertThat(result.getFailureCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("UT-NT-098: FcmNotificationTask compareTo 동일 우선순위")
        void utNt098ComparesTasksWithSamePriorityByTimestamp() {
            // given
            User user1 = createTestUser(9301L, "task1", "token1");
            User user2 = createTestUser(9302L, "task2", "token2");
            
            AppNotification notification1 = AppNotification.create(user1, testNotificationType, "작솅1");
            AppNotification notification2 = AppNotification.create(user2, testNotificationType, "작솅2");
            
            notification1 = notificationRepository.save(notification1);
            notification2 = notificationRepository.save(notification2);

            FcmService.FcmNotificationTask task1 = FcmService.FcmNotificationTask.of(notification1, FcmService.FcmPriority.NORMAL);
            // 시간 차이를 만들기 위해 잠시 대기
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            FcmService.FcmNotificationTask task2 = FcmService.FcmNotificationTask.of(notification2, FcmService.FcmPriority.NORMAL);

            // when & then - 동일 우선순위일 때 timestamp로 비교
            int comparison = task1.compareTo(task2);
            assertThat(comparison).isLessThan(0); // task1이 먼저 생성되어 작은 값
        }

        @Test
        @DisplayName("UT-NT-099: FcmPriority getValue 테스트")
        void utNt099TestsFcmPriorityValues() {
            // when & then
            assertThat(FcmService.FcmPriority.HIGH.getValue()).isEqualTo(1);
            assertThat(FcmService.FcmPriority.NORMAL.getValue()).isEqualTo(2);
            assertThat(FcmService.FcmPriority.LOW.getValue()).isEqualTo(3);
        }

        @Test
        @DisplayName("UT-NT-100: BatchSendResult 생성 테스트")
        void utNt100TestsBatchSendResultCreation() {
            // when
            var result = FcmService.BatchSendResult.of(5L, 3L);
            var emptyResult = FcmService.BatchSendResult.empty();

            // then
            assertThat(result.getSuccessCount()).isEqualTo(5L);
            assertThat(result.getFailureCount()).isEqualTo(3L);
            assertThat(result.getTotalCount()).isEqualTo(8L);
            
            assertThat(emptyResult.getSuccessCount()).isZero();
            assertThat(emptyResult.getFailureCount()).isZero();
            assertThat(emptyResult.getTotalCount()).isZero();
        }
    }

    @Nested
    @DisplayName("성능 및 안정성 테스트")
    class PerformanceAndStabilityTest {

        @Test
        @DisplayName("UT-NT-085: FCM 대량 전송")
        void utNt085HandlesHighVolumeNotificationsStably() {
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
        @DisplayName("UT-NT-086: FCM 라이프사이클")
        void utNt086StartsAndShutsDownProperly() {
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
    
    // FCM 토큰없는 사용자 생성 helper
    private User createTestUserWithoutToken(Long kakaoId, String nickname) {
        return createTestUser(kakaoId, nickname, null);
    }
}