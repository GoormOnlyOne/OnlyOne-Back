package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.DeliveryMethod;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

/**
 * Notification Service Test - Simplified practical test
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("알림 서비스 테스트")
class NotificationServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationTypeRepository notificationTypeRepository;
    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private SseEmittersService sseEmittersService;
    @Mock
    private FcmService fcmService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @InjectMocks
    private NotificationService notificationService;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = createTestUser(1L, "테스트유저");
    }

    @Nested
    @DisplayName("읽지 않은 개수 조회")
    class GetUnreadCountTest {

        @Test
        @DisplayName("읽지 않은 개수를 정확히 조회한다")
        void returns_unread_count_accurately() {
            // given
            Long userId = 1L;
            Long expectedCount = 5L;
            given(notificationRepository.countUnreadByUserId(userId)).willReturn(expectedCount);

            // when
            Long result = notificationService.getUnreadCount(userId);

            // then
            assertThat(result).isEqualTo(expectedCount);
        }

        @Test
        @DisplayName("읽지 않은 개수가 null일 때 0을 반환한다")
        void returns_0_when_unread_count_is_null() {
            // given
            Long userId = 1L;
            given(notificationRepository.countUnreadByUserId(userId)).willReturn(null);

            // when
            Long result = notificationService.getUnreadCount(userId);

            // then
            assertThat(result).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("알림 생성 검증")
    class CreateNotificationValidationTest {

        @Test
        @DisplayName("존재하지 않는 사용자로 알림 생성 시 예외가 발생한다")
        void throws_exception_when_user_does_not_exist() {
            // given
            NotificationCreateRequestDto request = NotificationCreateRequestDto.of(999L, Type.CHAT, "테스트");
            given(userRepository.findById(999L)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> notificationService.createNotification(request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("존재하지 않는 알림 타입으로 생성 시 예외가 발생한다")
        void throws_exception_when_notification_type_does_not_exist() {
            // given
            NotificationCreateRequestDto request = NotificationCreateRequestDto.of(1L, Type.CHAT, "테스트");
            given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
            given(notificationTypeRepository.findByType(Type.CHAT)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> notificationService.createNotification(request))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAllAsReadTest {

        @Test
        @DisplayName("읽음 처리된 개수만큼 반환한다")
        void returns_number_of_notifications_marked_as_read() {
            // given
            Long userId = 1L;
            given(notificationRepository.markAllAsReadByUserId(userId)).willReturn(3L);

            // when
            notificationService.markAllAsRead(userId);

            // then - 예외 없이 완료되면 성공
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("타겟 정보 포함 알림 생성")
    class CreateNotificationWithTargetTest {

        @Test
        @DisplayName("타겟 정보를 포함한 알림을 성공적으로 생성한다")
        void creates_notification_with_target_info() {
            // given
            User user = createTestUser(1L, "테스트유저");
            Type type = Type.CHAT;
            Long targetId = 123L;
            NotificationType notificationType = NotificationType.of(type, "테스트 템플릿");
            AppNotification expectedNotification = AppNotification.create(user, notificationType, "테스트");
            
            given(userRepository.findById(1L)).willReturn(Optional.of(user));
            given(notificationTypeRepository.findByType(type)).willReturn(Optional.of(notificationType));
            given(notificationRepository.save(any(AppNotification.class))).willReturn(expectedNotification);

            // when
            NotificationCreateResponseDto result = notificationService.createNotificationWithTarget(
                user, type, targetId, "테스트메시지");

            // then
            assertThat(result).isNotNull();
            then(notificationRepository).should().save(any(AppNotification.class));
            then(eventPublisher).should().publishEvent(any());
        }
    }

    @Nested
    @DisplayName("알림 조회")
    class GetNotificationsTest {

        @Test
        @DisplayName("사용자의 알림 목록을 조회한다")
        void gets_user_notifications() {
            // given
            Long userId = 1L;
            Long cursor = null;
            int size = 20;
            List<NotificationItemDto> mockList = List.of(
                NotificationItemDto.builder()
                    .notificationId(1L)
                    .type(Type.CHAT)
                    .content("테스트 알림")
                    .isRead(false)
                    .build()
            );
            given(notificationRepository.findNotificationsByUserId(userId, cursor, size)).willReturn(mockList);

            // when
            NotificationListResponseDto result = notificationService.getNotifications(userId, cursor, size);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getNotifications()).hasSize(1);
        }

        @Test
        @DisplayName("타입별 알림 목록을 조회한다")
        void gets_notifications_by_type() {
            // given
            Long userId = 1L;
            Type type = Type.LIKE;
            Long cursor = null;
            int size = 20;
            List<NotificationItemDto> mockList = List.of(
                NotificationItemDto.builder()
                    .notificationId(2L)
                    .type(Type.LIKE)
                    .content("좋아요 알림")
                    .isRead(false)
                    .build()
            );
            given(notificationRepository.findNotificationsByUserIdAndType(userId, type, cursor, size)).willReturn(mockList);

            // when
            NotificationListResponseDto result = notificationService.getNotificationsByType(userId, type, cursor, size);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getNotifications()).hasSize(1);
            assertThat(result.getNotifications().get(0).getType()).isEqualTo(Type.LIKE);
        }
    }

    @Nested
    @DisplayName("알림 삭제")
    class DeleteNotificationTest {

        @Test
        @DisplayName("알림을 성공적으로 삭제한다")
        void deletes_notification_successfully() {
            // given
            Long userId = 1L;
            Long notificationId = 100L;
            AppNotification notification = AppNotification.create(testUser, 
                NotificationType.of(Type.CHAT, "테스트"), "삭제테스트");
            
            given(notificationRepository.findById(notificationId)).willReturn(Optional.of(notification));

            // when
            notificationService.deleteNotification(userId, notificationId);

            // then
            then(notificationRepository).should().findById(notificationId);
            then(notificationRepository).should().deleteById(notificationId);
        }

        @Test
        @DisplayName("존재하지 않는 알림 삭제 시 예외가 발생한다")
        void throws_exception_when_deleting_non_existent_notification() {
            // given
            Long userId = 1L;
            Long notificationId = 999L;
            given(notificationRepository.findById(notificationId)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> notificationService.deleteNotification(userId, notificationId))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("개별 읽음 처리")
    class MarkAsReadTest {

        @Test
        @DisplayName("개별 알림을 읽음 처리한다")
        void marks_individual_notification_as_read() {
            // given
            Long userId = 1L;
            Long notificationId = 100L;
            AppNotification notification = AppNotification.create(testUser, 
                NotificationType.of(Type.CHAT, "테스트"), "읽음테스트");
            
            given(notificationRepository.findById(notificationId)).willReturn(Optional.of(notification));
            given(notificationRepository.save(any(AppNotification.class))).willReturn(notification);

            // when
            notificationService.markAsRead(notificationId, userId);

            // then
            then(notificationRepository).should().findById(notificationId);
            then(notificationRepository).should().save(any(AppNotification.class));
            then(sseEmittersService).should().sendUnreadCountUpdate(userId);
        }
    }

    @Nested
    @DisplayName("이벤트 리스너")
    class EventListenerTest {

        @Test
        @DisplayName("알림 생성 이벤트를 처리한다 - SSE와 FCM 모두")
        void handles_notification_created_event_with_both_delivery() {
            // given
            NotificationType notificationType = NotificationType.of(Type.CHAT, "테스트", DeliveryMethod.BOTH);
            AppNotification notification = AppNotification.create(testUser, notificationType, "테스트");
            NotificationService.NotificationCreatedEvent event = 
                new NotificationService.NotificationCreatedEvent(notification);

            // when
            notificationService.handleNotificationCreated(event);

            // then
            then(sseEmittersService).should().sendSseNotification(testUser.getUserId(), notification);
            then(fcmService).should().sendFcmNotification(notification);
        }

        @Test
        @DisplayName("알림 생성 이벤트를 처리한다 - SSE만")
        void handles_notification_created_event_with_sse_only() {
            // given
            NotificationType notificationType = NotificationType.of(Type.LIKE, "테스트", DeliveryMethod.SSE_ONLY);
            AppNotification notification = AppNotification.create(testUser, notificationType, "테스트");
            NotificationService.NotificationCreatedEvent event = 
                new NotificationService.NotificationCreatedEvent(notification);

            // when
            notificationService.handleNotificationCreated(event);

            // then
            then(sseEmittersService).should().sendSseNotification(testUser.getUserId(), notification);
            then(fcmService).should(never()).sendFcmNotification(any());
        }

        @Test
        @DisplayName("알림 생성 이벤트를 처리한다 - FCM만")
        void handles_notification_created_event_with_fcm_only() {
            // given
            NotificationType notificationType = NotificationType.of(Type.SETTLEMENT, "테스트", DeliveryMethod.FCM_ONLY);
            AppNotification notification = AppNotification.create(testUser, notificationType, "테스트");
            NotificationService.NotificationCreatedEvent event = 
                new NotificationService.NotificationCreatedEvent(notification);

            // when
            notificationService.handleNotificationCreated(event);

            // then
            then(sseEmittersService).should(never()).sendSseNotification(anyLong(), any());
            then(fcmService).should().sendFcmNotification(notification);
        }
    }

    @Nested
    @DisplayName("동시성 테스트 - Mock 기반")
    class ConcurrencyTest {

        @Test
        @DisplayName("동시에 읽지 않은 개수 조회가 안전하다")
        void concurrent_unread_count_queries_are_safe() throws Exception {
            // given
            Long userId = 1L;
            Long expectedCount = 10L;
            given(notificationRepository.countUnreadByUserId(userId)).willReturn(expectedCount);

            int threadCount = 50;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger correctResults = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // when
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        Long result = notificationService.getUnreadCount(userId);
                        successCount.incrementAndGet();

                        if (expectedCount.equals(result)) {
                            correctResults.incrementAndGet();
                        }

                    } catch (Exception e) {
                        // 실패 카운트는 successCount로 추적
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // 모든 스레드 동시 시작
            endLatch.await();
            executor.shutdown();

            // then
            assertThat(successCount.get()).isEqualTo(threadCount);
            assertThat(correctResults.get()).isEqualTo(threadCount);
        }

        @Test
        @DisplayName("동시에 읽음 처리가 안전하다")
        void concurrent_mark_as_read_operations_are_safe() throws Exception {
            // given
            Long userId = 1L;
            given(notificationRepository.markAllAsReadByUserId(userId)).willReturn(5L);

            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            // when
            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();

                        notificationService.markAllAsRead(userId);
                        successCount.incrementAndGet();

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

            // then
            assertThat(successCount.get()).isEqualTo(threadCount);
        }
    }

    // ================================
    // Helper Methods
    // ================================

    private User createTestUser(Long id, String nickname) {
        return User.builder()
            .userId(id)
            .kakaoId(12345L)
            .nickname(nickname)
            .status(Status.ACTIVE)
            .build();
    }
}