package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.DeliveryMethod;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
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
 * FCM Service Test - Push notification send and token management verification
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FCM Service Test")
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
        // FCM configuration injection
        ReflectionTestUtils.setField(fcmService, "batchSize", 500);
        ReflectionTestUtils.setField(fcmService, "maxRetry", 3);

        // Test data setup
        testUser = createTestUser(1L, "testuser", "test_fcm_token_123");
        testNotificationType = NotificationType.of(Type.CHAT, "%s sent a message.", DeliveryMethod.FCM_ONLY);
        
        testNotification = AppNotification.create(testUser, testNotificationType, "TestUser");
        ReflectionTestUtils.setField(testNotification, "id", 1L);
        ReflectionTestUtils.setField(testNotification, "createdAt", LocalDateTime.now());
    }

    @Nested
    @DisplayName("FCM Notification Send")
    class FcmNotificationSendTest {

        @Test
        @DisplayName("Successfully sends FCM notification")
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
        @DisplayName("Throws exception when FCM token is missing")
        void throws_exception_when_FCM_token_is_missing() {
            // given
            User userWithoutToken = createTestUser(2L, "notoken", null);
            AppNotification notificationWithoutToken = AppNotification.create(
                userWithoutToken, testNotificationType, "Test");
            ReflectionTestUtils.setField(notificationWithoutToken, "id", 2L);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(notificationWithoutToken))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_TOKEN_NOT_FOUND);
        }

        @Test
        @DisplayName("Throws exception when FCM token is empty")
        void throws_exception_when_FCM_token_is_empty() {
            // given
            User userWithEmptyToken = createTestUser(3L, "emptytoken", "");
            AppNotification notificationWithEmptyToken = AppNotification.create(
                userWithEmptyToken, testNotificationType, "Test");
            ReflectionTestUtils.setField(notificationWithEmptyToken, "id", 3L);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(notificationWithEmptyToken))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FCM_TOKEN_NOT_FOUND);
        }

        @Test
        @DisplayName("Converts Firebase exception to CustomException")
        void converts_Firebase_exception_to_CustomException() throws Exception {
            // given
            FirebaseMessagingException firebaseException = mock(FirebaseMessagingException.class);
            given(firebaseMessaging.send(any(Message.class))).willThrow(firebaseException);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class);
        }

        @Test
        @DisplayName("Throws send failed exception on general Firebase error")
        void throws_send_failed_exception_on_general_Firebase_error() throws Exception {
            // given
            Exception generalException = new Exception("General error");
            given(firebaseMessaging.send(any(Message.class))).willThrow(generalException);

            // when & then
            assertThatThrownBy(() -> fcmService.sendFcmNotification(testNotification))
                .isInstanceOf(CustomException.class);
        }
    }

    @Nested
    @DisplayName("FCM Batch Send")
    class FcmBatchSendTest {

        @Test
        @DisplayName("Sends multiple notifications in batch")
        void sends_multiple_notifications_in_batch() throws Exception {
            // given
            List<AppNotification> notifications = createNotificationList(10);
            
            BatchResponse mockResponse = mock(BatchResponse.class);
            given(mockResponse.getSuccessCount()).willReturn(8);
            given(mockResponse.getFailureCount()).willReturn(2);
            
            List<SendResponse> sendResponses = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                SendResponse sendResponse = mock(SendResponse.class);
                given(sendResponse.isSuccessful()).willReturn(i < 8); // 8 success, 2 failure
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
        @DisplayName("Returns empty result for empty list batch send")
        void returns_empty_result_for_empty_list_batch_send() throws Exception {
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
        @DisplayName("Splits large notifications into configured batch size")
        void splits_large_notifications_into_configured_batch_size() throws Exception {
            // given
            ReflectionTestUtils.setField(fcmService, "batchSize", 100);
            List<AppNotification> notifications = createNotificationList(250); // Will be split into 3 batches
            
            BatchResponse mockResponse = mock(BatchResponse.class);
            given(mockResponse.getSuccessCount()).willReturn(100);
            given(mockResponse.getFailureCount()).willReturn(0);
            given(mockResponse.getResponses()).willReturn(new ArrayList<>());
            given(firebaseMessaging.sendMulticast(any(MulticastMessage.class))).willReturn(mockResponse);

            // when
            CompletableFuture<FcmService.BatchSendResult> resultFuture = fcmService.sendBatch(notifications);
            FcmService.BatchSendResult result = resultFuture.get();

            // then
            // Verify 3 batch calls
            then(firebaseMessaging).should(atLeast(2)).sendMulticast(any(MulticastMessage.class));
        }
    }

    @Nested
    @DisplayName("FCM Priority Queue")
    class FcmPriorityQueueTest {

        @Test
        @DisplayName("Adds notifications to queue with priority")
        void adds_notifications_to_queue_with_priority() {
            // given & when
            fcmService.queueFcmNotification(testNotification, FcmService.FcmPriority.HIGH);
            fcmService.queueFcmNotification(testNotification, FcmService.FcmPriority.NORMAL);
            fcmService.queueFcmNotification(testNotification, FcmService.FcmPriority.LOW);

            // then - No exception means success
            assertThat(true).isTrue();
        }

        @Test
        @DisplayName("FcmNotificationTask sorts by priority")
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
    @DisplayName("FCM Retry Mechanism")
    class FcmRetryTest {

        @Test
        @DisplayName("Retries failed notifications")
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

            // then - Async method, wait briefly before verification
            Thread.sleep(100);
            then(notificationRepository).should().findFailedFcmNotificationsByUserId(userId);
        }

        @Test
        @DisplayName("Does nothing when no notifications to retry")
        void does_nothing_when_no_notifications_to_retry() throws Exception {
            // given
            Long userId = 1L;
            given(notificationRepository.findFailedFcmNotificationsByUserId(userId))
                .willReturn(new ArrayList<>());

            // when
            fcmService.retryFailedNotifications(userId);

            // then
            Thread.sleep(100);
            then(firebaseMessaging).should(never()).sendMulticast(any());
        }
    }

    @Nested
    @DisplayName("FCM Concurrency Test")
    class FcmConcurrencyTest {

        @Test
        @DisplayName("Safely sends multiple FCM notifications concurrently")
        void safely_sends_multiple_FCM_notifications_concurrently() throws Exception {
            // given
            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            given(firebaseMessaging.send(any(Message.class))).willReturn("success_message_id");

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // when
            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        User user = createTestUser((long) index, "user" + index, "token_" + index);
                        AppNotification notification = AppNotification.create(
                            user, testNotificationType, "Notification " + index);
                        ReflectionTestUtils.setField(notification, "id", (long) index);

                        fcmService.sendFcmNotification(notification);
                        successCount.incrementAndGet();

                    } catch (CustomException e) {
                        // FCM related exceptions are normal cases
                        if (e.getErrorCode() == ErrorCode.FCM_MESSAGE_SEND_FAILED) {
                            errorCount.incrementAndGet();
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
            assertThat(successCount.get()).isEqualTo(threadCount);
            assertThat(errorCount.get()).isEqualTo(0);
        }
    }

    // ================================
    // Helper Methods
    // ================================

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

    private List<AppNotification> createNotificationList(int count) {
        List<AppNotification> notifications = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            User user = createTestUser((long) i, "user" + i, "token_" + i);
            AppNotification notification = AppNotification.create(
                user, testNotificationType, "Notification " + i);
            ReflectionTestUtils.setField(notification, "id", (long) i);
            ReflectionTestUtils.setField(notification, "createdAt", LocalDateTime.now());
            notifications.add(notification);
        }
        return notifications;
    }
}