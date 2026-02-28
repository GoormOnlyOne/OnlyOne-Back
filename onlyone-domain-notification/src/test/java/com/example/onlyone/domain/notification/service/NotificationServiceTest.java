package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.port.NotificationEventPublisher;
import com.example.onlyone.domain.notification.port.NotificationStoragePort;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static com.example.onlyone.domain.notification.fixture.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 단위 테스트")
class NotificationServiceTest {

    @InjectMocks private NotificationService notificationService;
    @Mock private NotificationStoragePort storagePort;
    @Mock private NotificationEventPublisher eventPublisher;
    @Mock private AuthService authService;
    @Mock private NotificationUnreadCounter unreadCounter;

    @Nested
    @DisplayName("알림 목록 조회")
    class GetNotifications {

        @Test
        @DisplayName("성공: 알림 목록이 반환된다")
        void success_returnsNotificationList() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            List<NotificationItemDto> items = List.of(
                    notificationItem(3L), notificationItem(2L), notificationItem(1L));
            given(storagePort.findByUserId(DEFAULT_USER_ID, null, 11))
                    .willReturn(items);

            NotificationListResponseDto result =
                    notificationService.getNotifications(new NotificationQueryDto(null, 10));

            assertThat(result.notifications()).hasSize(3);
            assertThat(result.hasMore()).isFalse();
            assertThat(result.cursor()).isEqualTo(1L);
        }

        @Test
        @DisplayName("성공: size가 30을 초과하면 30으로 제한된다")
        void success_sizeIsCappedAt30() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(storagePort.findByUserId(DEFAULT_USER_ID, null, 31))
                    .willReturn(new ArrayList<>());

            notificationService.getNotifications(new NotificationQueryDto(null, 50));

            then(storagePort).should().findByUserId(DEFAULT_USER_ID, null, 31);
        }

        @Test
        @DisplayName("성공: hasMore가 올바르게 설정된다")
        void success_hasMoreIsSetCorrectly() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            List<NotificationItemDto> items = List.of(
                    notificationItem(3L), notificationItem(2L), notificationItem(1L));
            given(storagePort.findByUserId(DEFAULT_USER_ID, null, 3))
                    .willReturn(items);

            NotificationListResponseDto result =
                    notificationService.getNotifications(new NotificationQueryDto(null, 2));

            assertThat(result.hasMore()).isTrue();
            assertThat(result.notifications()).hasSize(2);
            assertThat(result.cursor()).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("읽지 않은 알림 개수")
    class GetUnreadCount {

        @Test
        @DisplayName("성공: 카운터에서 개수를 조회한다")
        void success_returnsUnreadCount() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(unreadCounter.getCount(DEFAULT_USER_ID)).willReturn(5L);

            Long count = notificationService.getUnreadCount();

            assertThat(count).isEqualTo(5L);
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAsRead {

        @Test
        @DisplayName("성공: 알림이 읽음으로 변경되고 카운터 감소")
        void success_notificationIsMarkedAsRead() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(storagePort.markAsReadByIdAndUserId(1L, DEFAULT_USER_ID)).willReturn(1);

            notificationService.markAsRead(1L);

            then(storagePort).should().markAsReadByIdAndUserId(1L, DEFAULT_USER_ID);
            then(unreadCounter).should().decrement(DEFAULT_USER_ID);
        }

        @Test
        @DisplayName("성공: 이미 읽은 알림이면 카운터 변경 없음")
        void success_alreadyReadDoesNotDecrementCounter() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(storagePort.markAsReadByIdAndUserId(1L, DEFAULT_USER_ID)).willReturn(0);

            notificationService.markAsRead(1L);

            then(unreadCounter).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("모든 알림 읽음")
    class MarkAllAsRead {

        @Test
        @DisplayName("성공: 모든 알림이 읽음으로 변경되고 카운터 리셋")
        void success_allNotificationsMarkedAsRead() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(storagePort.markAllAsReadByUserId(DEFAULT_USER_ID)).willReturn(3L);

            notificationService.markAllAsRead();

            then(storagePort).should().markAllAsReadByUserId(DEFAULT_USER_ID);
            then(unreadCounter).should().reset(DEFAULT_USER_ID);
        }
    }

    @Nested
    @DisplayName("알림 삭제")
    class DeleteNotification {

        @Test
        @DisplayName("성공: 읽지 않은 알림 삭제 시 카운터 감소")
        void success_unreadNotificationDeletedDecrementsCounter() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(storagePort.deleteByIdAndUserId(1L, DEFAULT_USER_ID)).willReturn(true);

            notificationService.deleteNotification(1L);

            then(storagePort).should().deleteByIdAndUserId(1L, DEFAULT_USER_ID);
            then(unreadCounter).should().decrement(DEFAULT_USER_ID);
        }

        @Test
        @DisplayName("성공: 이미 읽은 알림 삭제 시 카운터 변경 없음")
        void success_readNotificationDeletedNoCounterChange() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(storagePort.deleteByIdAndUserId(1L, DEFAULT_USER_ID)).willReturn(false);

            notificationService.deleteNotification(1L);

            then(unreadCounter).shouldHaveNoInteractions();
        }
    }

    @Nested
    @DisplayName("알림 생성")
    class CreateNotification {

        @Test
        @DisplayName("성공: 알림이 저장되고 이벤트가 발행된다")
        void success_savedAndEventPublished() {
            User user = user();
            NotificationCreateDto createDto =
                    new NotificationCreateDto(user, NotificationType.LIKE, new String[]{"홍길동"});
            given(storagePort.save(eq(DEFAULT_USER_ID), eq(NotificationType.LIKE), any(String.class)))
                    .willReturn(1L);

            notificationService.createNotification(createDto);

            then(storagePort).should().save(eq(DEFAULT_USER_ID), eq(NotificationType.LIKE), any(String.class));
            then(eventPublisher).should().publish(any(NotificationCreatedEvent.class));
            then(unreadCounter).should().increment(DEFAULT_USER_ID);
        }
    }
}
