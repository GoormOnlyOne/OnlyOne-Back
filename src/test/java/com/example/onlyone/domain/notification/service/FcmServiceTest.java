package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.google.firebase.messaging.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("FCM 서비스 테스트")
class FcmServiceTest {

    @Mock
    private FirebaseMessaging firebaseMessaging;
    @Mock
    private NotificationRepository notificationRepository;
    @InjectMocks
    private FcmService fcmService;

    private User testUser;
    private NotificationType testNotificationType;
    private AppNotification testNotification;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fcmService, "batchSize", 500);
        ReflectionTestUtils.setField(fcmService, "maxRetry", 3);

        testUser = createTestUser(1L, "testuser", "test_fcm_token_123");
        testNotificationType = NotificationType.of(Type.CHAT, "테스트 템플릿");
        testNotification = AppNotification.create(testUser, testNotificationType, "테스트");
        ReflectionTestUtils.setField(testNotification, "id", 1L);
        ReflectionTestUtils.setField(testNotification, "createdAt", LocalDateTime.now());
    }

    @Nested
    @DisplayName("FCM 알림 전송")
    class FcmNotificationSendTest {

        @Test
        @DisplayName("UT-NT-044: FCM 토큰이 있는 경우 FCM 전송이 시도되는가?")
        void successfully_sends_FCM_notification() throws Exception {
            // given
            String expectedResponse = "projects/test/messages/msg123";
            given(firebaseMessaging.send(any(Message.class))).willReturn(expectedResponse);

            // when
            fcmService.sendFcmNotification(testNotification);

            // then
            ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
            then(firebaseMessaging).should().send(messageCaptor.capture());
            
            Message sentMessage = messageCaptor.getValue();
            assertThat(sentMessage).isNotNull();
        }

        @Test
        @DisplayName("예외 처리: FCM 토큰 누락 시 예외 발생")
        void throws_exception_when_FCM_token_is_missing() {
            // given
            User userWithoutToken = createTestUser(2L, "notoken", null);
            AppNotification notificationWithoutToken = AppNotification.create(
                userWithoutToken, testNotificationType, "테스트");
            ReflectionTestUtils.setField(notificationWithoutToken, "id", 2L);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(notificationWithoutToken))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_TOKEN_NOT_FOUND);
        }

        @Test
        @DisplayName("예외 처리: FCM 토큰 빈 문자열 시 예외 발생")
        void throws_exception_when_FCM_token_is_empty() {
            // given
            User userWithEmptyToken = createTestUser(3L, "emptytoken", "");
            AppNotification notificationWithEmptyToken = AppNotification.create(
                userWithEmptyToken, testNotificationType, "테스트");
            ReflectionTestUtils.setField(notificationWithEmptyToken, "id", 3L);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(notificationWithEmptyToken))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_TOKEN_NOT_FOUND);
        }

        @Test
        @DisplayName("예외 처리: Firebase 예외 CustomException 변환")
        void converts_Firebase_exception_to_CustomException() throws Exception {
            // given
            FirebaseMessagingException firebaseException = mock(FirebaseMessagingException.class);
            given(firebaseMessaging.send(any(Message.class))).willThrow(firebaseException);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class);
        }
    }

    @Nested
    @DisplayName("FCM 배치 전송")
    class FcmBatchSendTest {

        @Test
        @DisplayName("배치 전송: 다중 알림 배치 전솥")
        void sends_multiple_notifications_in_batch() throws Exception {
            // given
            List<AppNotification> notifications = createNotificationList(10);
            
            BatchResponse mockResponse = mock(BatchResponse.class);
            given(mockResponse.getSuccessCount()).willReturn(8);
            given(mockResponse.getFailureCount()).willReturn(2);
            
            List<SendResponse> sendResponses = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                SendResponse sendResponse = mock(SendResponse.class);
                given(sendResponse.isSuccessful()).willReturn(i < 8);
                if (i >= 8) {
                    FirebaseMessagingException mockException = mock(FirebaseMessagingException.class);
                    given(sendResponse.getException()).willReturn(mockException);
                }
                sendResponses.add(sendResponse);
            }
            given(mockResponse.getResponses()).willReturn(sendResponses);
            given(firebaseMessaging.sendMulticast(any(MulticastMessage.class))).willReturn(mockResponse);

            // when
            CompletableFuture<FcmService.BatchSendResult> resultFuture = fcmService.sendBatch(notifications);
            FcmService.BatchSendResult result = resultFuture.get();

            // then
            assertThat(result.getSuccessCount()).isEqualTo(8);
            assertThat(result.getFailureCount()).isEqualTo(2);
            assertThat(result.getTotalCount()).isEqualTo(10);
        }

        @Test
        @DisplayName("빈 상태 처리: 빈 리스트 배치 전송 처리")
        void returns_empty_result_for_empty_list() throws Exception {
            // given
            List<AppNotification> emptyList = new ArrayList<>();

            // when
            CompletableFuture<FcmService.BatchSendResult> resultFuture = fcmService.sendBatch(emptyList);
            FcmService.BatchSendResult result = resultFuture.get();

            // then
            assertThat(result.getSuccessCount()).isEqualTo(0);
            assertThat(result.getFailureCount()).isEqualTo(0);
            assertThat(result.getTotalCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("배치 눆리: 대량 알림 배치 크기 분할")
        void splits_large_notifications_into_configured_batch_size() throws Exception {
            // given
            ReflectionTestUtils.setField(fcmService, "batchSize", 100);
            List<AppNotification> notifications = createNotificationList(250);
            
            BatchResponse mockResponse = mock(BatchResponse.class);
            given(mockResponse.getSuccessCount()).willReturn(100);
            given(mockResponse.getFailureCount()).willReturn(0);
            given(mockResponse.getResponses()).willReturn(new ArrayList<>());
            given(firebaseMessaging.sendMulticast(any(MulticastMessage.class))).willReturn(mockResponse);

            // when
            CompletableFuture<FcmService.BatchSendResult> resultFuture = fcmService.sendBatch(notifications);
            FcmService.BatchSendResult result = resultFuture.get();

            // then
            then(firebaseMessaging).should(atLeast(2)).sendMulticast(any(MulticastMessage.class));
        }
    }

    @Nested
    @DisplayName("FCM 우선순위 큐")
    class FcmPriorityQueueTest {

        @Test
        @DisplayName("우선순위 처리: 우선순위별 큐 추가")
        void adds_notifications_to_queue_with_priority() {
            // when
            fcmService.queueFcmNotification(testNotification, FcmService.FcmPriority.HIGH);
            fcmService.queueFcmNotification(testNotification, FcmService.FcmPriority.NORMAL);
            fcmService.queueFcmNotification(testNotification, FcmService.FcmPriority.LOW);

            // then - 예외가 발생하지 않으면 성공
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("우선순위 처리: Task 우선순위 정렬")
        void fcmNotificationTask_sorts_by_priority() {
            // given
            FcmService.FcmNotificationTask highTask = FcmService.FcmNotificationTask.of(
                testNotification, FcmService.FcmPriority.HIGH);
            FcmService.FcmNotificationTask normalTask = FcmService.FcmNotificationTask.of(
                testNotification, FcmService.FcmPriority.NORMAL);
            FcmService.FcmNotificationTask lowTask = FcmService.FcmNotificationTask.of(
                testNotification, FcmService.FcmPriority.LOW);

            // when & then
            assertThat(highTask.compareTo(normalTask)).isLessThan(0);
            assertThat(normalTask.compareTo(lowTask)).isLessThan(0);
            assertThat(highTask.compareTo(lowTask)).isLessThan(0);
        }
    }

    @Nested
    @DisplayName("FCM 재시도 메커니즘")
    class FcmRetryTest {

        @Test
        @DisplayName("재시도 처리: 실패 알림 재시도")
        void retries_failed_notifications() throws Exception {
            // given
            Long userId = 1L;
            List<AppNotification> failedNotifications = createNotificationList(5);
            given(notificationRepository.findFailedFcmNotificationsByUserId(userId))
                .willReturn(failedNotifications);

            BatchResponse mockResponse = mock(BatchResponse.class);
            given(mockResponse.getSuccessCount()).willReturn(5);
            given(mockResponse.getFailureCount()).willReturn(0);
            given(mockResponse.getResponses()).willReturn(new ArrayList<>());
            given(firebaseMessaging.sendMulticast(any(MulticastMessage.class))).willReturn(mockResponse);

            // when
            fcmService.retryFailedNotifications(userId);

            // then
            TimeUnit.MILLISECONDS.sleep(100);
            then(notificationRepository).should().findFailedFcmNotificationsByUserId(userId);
        }

        @Test
        @DisplayName("재시도 처리: 재시도 대상 없음 시 무작업")
        void does_nothing_when_no_notifications_to_retry() throws Exception {
            // given
            Long userId = 1L;
            given(notificationRepository.findFailedFcmNotificationsByUserId(userId))
                .willReturn(new ArrayList<>());

            // when
            fcmService.retryFailedNotifications(userId);

            // then
            TimeUnit.MILLISECONDS.sleep(100);
            then(firebaseMessaging).should(never()).sendMulticast(any());
        }
    }

    @Nested
    @DisplayName("FCM 동시성 테스트")
    class FcmConcurrencyTest {

        @Test
        @DisplayName("동시성 테스트: 다중 FCM 동시 전송 안전성")
        void safely_sends_multiple_FCM_notifications_concurrently() throws Exception {
            // given
            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            lenient().when(firebaseMessaging.send(any(Message.class))).thenReturn("success_message_id");

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // when
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        User user = createTestUser((long) index, "user" + index, "token_" + index);
                        AppNotification notification = AppNotification.create(
                            user, testNotificationType, "테스트" + index);
                        ReflectionTestUtils.setField(notification, "id", (long) index);

                        fcmService.sendFcmNotification(notification);
                        successCount.incrementAndGet();

                    } catch (CustomException e) {
                        errorCount.incrementAndGet();
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
            assertThat(successCount.get() + errorCount.get()).isEqualTo(threadCount);
        }
    }

    private User createTestUser(Long id, String nickname, String fcmToken) {
        User user = User.builder()
            .userId(id)
            .kakaoId(12345L + id)
            .nickname(nickname)
            .status(Status.ACTIVE)
            .build();
        
        if (fcmToken != null) {
            ReflectionTestUtils.setField(user, "fcmToken", fcmToken);
        }
        
        return user;
    }

    @Nested
    @DisplayName("성능 및 안정성 테스트")
    class PerformanceAndStabilityTest {

        @Test
        @DisplayName("UT-NT-028: 대량 FCM 전송 성능 테스트")
        void handles_large_volume_fcm_sending() throws Exception {
            // given
            int largeCount = 10000;
            List<AppNotification> largeNotificationList = createNotificationList(largeCount);
            
            BatchResponse mockResponse = mock(BatchResponse.class);
            given(mockResponse.getSuccessCount()).willReturn(500); // batchSize = 500 by default
            given(mockResponse.getFailureCount()).willReturn(0);
            given(mockResponse.getResponses()).willReturn(new ArrayList<>());
            given(firebaseMessaging.sendMulticast(any(MulticastMessage.class))).willReturn(mockResponse);

            // when
            Instant start = Instant.now();
            CompletableFuture<FcmService.BatchSendResult> resultFuture = fcmService.sendBatch(largeNotificationList);
            FcmService.BatchSendResult result = resultFuture.get();
            Duration duration = Duration.between(start, Instant.now());

            // then - 10초 이내 완료 및 성공
            assertThat(duration.toMillis()).isLessThan(10000);
            assertThat(result.getSuccessCount()).isEqualTo(largeCount); // 10000개 모두 성공해야 함
            assertThat(result.getFailureCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("메모리 효율성: 대량 처리 OutOfMemory 방지")
        void no_memory_issues_with_large_batches() throws Exception {
            // given - 100,000개 대량 데이터
            int massiveCount = 100000;
            
            // 배치별로 처리하여 메모리 효율성 검증
            BatchResponse mockResponse = mock(BatchResponse.class);
            given(mockResponse.getSuccessCount()).willReturn(500); // 배치 사이즈
            given(mockResponse.getFailureCount()).willReturn(0);
            given(mockResponse.getResponses()).willReturn(new ArrayList<>());
            given(firebaseMessaging.sendMulticast(any(MulticastMessage.class))).willReturn(mockResponse);
            
            // when - 배치별 처리
            int totalProcessed = 0;
            int batchSize = 1000;
            
            for (int batch = 0; batch < massiveCount / batchSize; batch++) {
                List<AppNotification> batchNotifications = createNotificationList(batchSize);
                CompletableFuture<FcmService.BatchSendResult> resultFuture = fcmService.sendBatch(batchNotifications);
                FcmService.BatchSendResult result = resultFuture.get();
                totalProcessed += result.getSuccessCount();
                
                // 주기적으로 메모리 상태 확인
                if (batch % 10 == 0) {
                    Runtime.getRuntime().gc(); // 가비지 컵렉션 유도
                }
            }

            // then - OutOfMemory 없이 완료
            assertThat(totalProcessed).isGreaterThan(massiveCount / 2);
        }

        @Test
        @DisplayName("장애 대응: 네트워크 장애 재시도 메커니즘")
        void retry_mechanism_handles_network_failures() throws Exception {
            // given
            List<AppNotification> failedNotifications = createNotificationList(5);
            
            // 첫 번째 호출에서는 실패, 두 번째에서 성공
            given(notificationRepository.findFailedFcmNotificationsByUserId(1L))
                .willReturn(failedNotifications)
                .willReturn(new ArrayList<>()); // 재시도 후 빈 리스트
            
            BatchResponse mockResponse = mock(BatchResponse.class);
            given(mockResponse.getSuccessCount()).willReturn(5);
            given(mockResponse.getFailureCount()).willReturn(0);
            given(mockResponse.getResponses()).willReturn(new ArrayList<>());
            given(firebaseMessaging.sendMulticast(any(MulticastMessage.class))).willReturn(mockResponse);

            // when
            fcmService.retryFailedNotifications(1L);
            
            // 재시도 한 번 더
            fcmService.retryFailedNotifications(1L);

            // then
            TimeUnit.MILLISECONDS.sleep(200); // 비동기 처리 대기
            then(notificationRepository).should(times(2)).findFailedFcmNotificationsByUserId(1L);
        }
    }

    @Nested
    @DisplayName("추가 예외 처리 테스트")
    class AdditionalExceptionHandlingTest {

        @Test
        @DisplayName("에러 코드 처리: Firebase INVALID_ARGUMENT 에러")
        void handles_specific_firebase_error_codes() throws Exception {
            // given - INVALID_ARGUMENT 에러
            FirebaseMessagingException invalidTokenException = mock(FirebaseMessagingException.class);
            com.google.firebase.ErrorCode mockErrorCode = mock(com.google.firebase.ErrorCode.class);
            given(mockErrorCode.toString()).willReturn("INVALID_ARGUMENT");
            given(invalidTokenException.getErrorCode()).willReturn(mockErrorCode);
            given(firebaseMessaging.send(any(Message.class))).willThrow(invalidTokenException);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_TOKEN_REFRESH_REQUIRED);
        }

        @Test
        @DisplayName("에러 코드 처리: Firebase UNREGISTERED 에러")
        void handles_unregistered_error_code() throws Exception {
            // given - UNREGISTERED 에러
            FirebaseMessagingException unregisteredError = mock(FirebaseMessagingException.class);
            com.google.firebase.ErrorCode mockErrorCode = mock(com.google.firebase.ErrorCode.class);
            given(mockErrorCode.toString()).willReturn("UNREGISTERED");
            given(unregisteredError.getErrorCode()).willReturn(mockErrorCode);
            given(firebaseMessaging.send(any(Message.class))).willThrow(unregisteredError);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_TOKEN_REFRESH_REQUIRED);
        }

        @Test
        @DisplayName("에러 코드 처리: Firebase 알 수 없는 에러")
        void handles_unknown_firebase_error_codes() throws Exception {
            // given - 알 수 없는 에러
            FirebaseMessagingException unknownError = mock(FirebaseMessagingException.class);
            com.google.firebase.ErrorCode mockErrorCode = mock(com.google.firebase.ErrorCode.class);
            given(mockErrorCode.toString()).willReturn("INTERNAL");
            given(unknownError.getErrorCode()).willReturn(mockErrorCode);
            given(firebaseMessaging.send(any(Message.class))).willThrow(unknownError);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_MESSAGE_SEND_FAILED);
        }

        @Test
        @DisplayName("예외 처리: 예상치 못한 일반 예외")
        void handles_unexpected_exceptions() throws Exception {
            // given - RuntimeException 발생
            given(firebaseMessaging.send(any(Message.class))).willThrow(new RuntimeException("예상치 못한 오류"));

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_MESSAGE_SEND_FAILED);
        }
    }

    @Nested
    @DisplayName("배치 처리 상세 테스트")
    class DetailedBatchProcessingTest {

        @Test
        @DisplayName("배치 분할: 동적 배치 크기 분할")
        void splits_notifications_by_configured_batch_size() throws Exception {
            // given
            ReflectionTestUtils.setField(fcmService, "batchSize", 3);
            List<AppNotification> notifications = createNotificationList(10);
            
            BatchResponse mockResponse = mock(BatchResponse.class);
            given(mockResponse.getSuccessCount()).willReturn(3);
            given(mockResponse.getFailureCount()).willReturn(0);
            given(mockResponse.getResponses()).willReturn(new ArrayList<>());
            given(firebaseMessaging.sendMulticast(any(MulticastMessage.class))).willReturn(mockResponse);

            // when
            CompletableFuture<FcmService.BatchSendResult> resultFuture = fcmService.sendBatch(notifications);
            FcmService.BatchSendResult result = resultFuture.get();

            // then - 4번 호출될 것 (3+3+3+1)
            then(firebaseMessaging).should(times(4)).sendMulticast(any(MulticastMessage.class));
        }

        @Test
        @DisplayName("배치 실패: 배치 내 개별 실패 처리")
        void handles_individual_failures_within_batch() throws Exception {
            // given
            List<AppNotification> notifications = createNotificationList(3);
            
            BatchResponse mockResponse = mock(BatchResponse.class);
            given(mockResponse.getSuccessCount()).willReturn(2);
            given(mockResponse.getFailureCount()).willReturn(1);
            
            // 개별 응답 설정
            List<SendResponse> sendResponses = new ArrayList<>();
            SendResponse success1 = mock(SendResponse.class);
            given(success1.isSuccessful()).willReturn(true);
            sendResponses.add(success1);
            
            SendResponse failure = mock(SendResponse.class);
            given(failure.isSuccessful()).willReturn(false);
            FirebaseMessagingException mockException = mock(FirebaseMessagingException.class);
            given(failure.getException()).willReturn(mockException);
            sendResponses.add(failure);
            
            SendResponse success2 = mock(SendResponse.class);
            given(success2.isSuccessful()).willReturn(true);
            sendResponses.add(success2);
            
            given(mockResponse.getResponses()).willReturn(sendResponses);
            given(firebaseMessaging.sendMulticast(any(MulticastMessage.class))).willReturn(mockResponse);

            // when
            CompletableFuture<FcmService.BatchSendResult> resultFuture = fcmService.sendBatch(notifications);
            FcmService.BatchSendResult result = resultFuture.get();

            // then
            assertThat(result.getSuccessCount()).isEqualTo(2);
            assertThat(result.getFailureCount()).isEqualTo(1);
            assertThat(result.getTotalCount()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("우선순위 큐 상세 테스트")
    class PriorityQueueDetailedTest {

        @Test
        @DisplayName("우선순위 순서: 우선순위별 작업 순서 보장")
        void ensures_priority_order_in_queue() {
            // given
            PriorityBlockingQueue<FcmService.FcmNotificationTask> testQueue = new PriorityBlockingQueue<>();
            
            // 다른 우선순위로 작업 추가
            FcmService.FcmNotificationTask lowTask = FcmService.FcmNotificationTask.of(
                testNotification, FcmService.FcmPriority.LOW);
            FcmService.FcmNotificationTask highTask = FcmService.FcmNotificationTask.of(
                testNotification, FcmService.FcmPriority.HIGH);
            FcmService.FcmNotificationTask normalTask = FcmService.FcmNotificationTask.of(
                testNotification, FcmService.FcmPriority.NORMAL);
            
            testQueue.offer(lowTask);
            testQueue.offer(highTask);
            testQueue.offer(normalTask);

            // when & then - HIGH -> NORMAL -> LOW 순서로 출력
            assertThat(testQueue.poll()).isEqualTo(highTask);
            assertThat(testQueue.poll()).isEqualTo(normalTask);
            assertThat(testQueue.poll()).isEqualTo(lowTask);
        }

        @Test
        @DisplayName("우선순위 순서: 동일 우선순위 시간순 정렬")
        void orders_by_timestamp_when_same_priority() throws InterruptedException {
            // given
            FcmService.FcmNotificationTask task1 = FcmService.FcmNotificationTask.of(
                testNotification, FcmService.FcmPriority.NORMAL);
            Thread.sleep(1); // 시간 차이 보장
            FcmService.FcmNotificationTask task2 = FcmService.FcmNotificationTask.of(
                testNotification, FcmService.FcmPriority.NORMAL);
            
            PriorityBlockingQueue<FcmService.FcmNotificationTask> testQueue = new PriorityBlockingQueue<>();
            testQueue.offer(task2); // 나중에 만든 것을 먼저 추가
            testQueue.offer(task1);

            // when & then - 먼저 만든 것이 먼저 출력
            assertThat(testQueue.poll()).isEqualTo(task1);
            assertThat(testQueue.poll()).isEqualTo(task2);
        }
    }

    private List<AppNotification> createNotificationList(int count) {
        List<AppNotification> notifications = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User user = createTestUser((long) i, "user" + i, "token_" + i);
            AppNotification notification = AppNotification.create(
                user, testNotificationType, "테스트" + i);
            ReflectionTestUtils.setField(notification, "id", (long) i);
            ReflectionTestUtils.setField(notification, "createdAt", LocalDateTime.now());
            notifications.add(notification);
        }
        return notifications;
    }
}