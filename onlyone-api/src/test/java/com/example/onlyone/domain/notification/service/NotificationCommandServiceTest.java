package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
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

import static com.example.onlyone.domain.notification.fixture.NotificationFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationCommandService 단위 테스트")
class NotificationCommandServiceTest {

    @InjectMocks private NotificationCommandService commandService;
    @Mock private NotificationStoragePort storagePort;
    @Mock private NotificationEventPublisher eventPublisher;
    @Mock private AuthService authService;
    @Mock private NotificationUnreadCounter unreadCounter;

    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAsRead {

        @Test
        @DisplayName("성공: 알림이 읽음으로 변경되고 카운터 감소")
        void success_notificationIsMarkedAsRead() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(storagePort.markAsReadByIdAndUserId(1L, DEFAULT_USER_ID)).willReturn(1);

            commandService.markAsRead(1L);

            then(storagePort).should().markAsReadByIdAndUserId(1L, DEFAULT_USER_ID);
            then(unreadCounter).should().decrement(DEFAULT_USER_ID);
        }

        @Test
        @DisplayName("성공: 이미 읽은 알림이면 카운터 변경 없음")
        void success_alreadyReadDoesNotDecrementCounter() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(storagePort.markAsReadByIdAndUserId(1L, DEFAULT_USER_ID)).willReturn(0);

            commandService.markAsRead(1L);

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

            commandService.markAllAsRead();

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

            commandService.deleteNotification(1L);

            then(storagePort).should().deleteByIdAndUserId(1L, DEFAULT_USER_ID);
            then(unreadCounter).should().decrement(DEFAULT_USER_ID);
        }

        @Test
        @DisplayName("성공: 이미 읽은 알림 삭제 시 카운터 변경 없음")
        void success_readNotificationDeletedNoCounterChange() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(storagePort.deleteByIdAndUserId(1L, DEFAULT_USER_ID)).willReturn(false);

            commandService.deleteNotification(1L);

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
                    new NotificationCreateDto(user, NotificationType.LIKE, "홍길동");
            given(storagePort.save(eq(DEFAULT_USER_ID), eq(NotificationType.LIKE), any(String.class)))
                    .willReturn(1L);

            commandService.createNotification(createDto);

            then(storagePort).should().save(eq(DEFAULT_USER_ID), eq(NotificationType.LIKE), any(String.class));
            then(eventPublisher).should().publish(any(NotificationCreatedEvent.class));
            then(unreadCounter).should().increment(DEFAULT_USER_ID);
        }
    }
}
