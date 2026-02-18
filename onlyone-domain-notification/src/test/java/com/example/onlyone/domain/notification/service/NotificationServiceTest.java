package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.AuthService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;

import static com.example.onlyone.domain.notification.fixture.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 단위 테스트")
class NotificationServiceTest {

    @InjectMocks private NotificationService notificationService;
    @Mock private NotificationRepository notificationRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuthService authService;

    @Nested
    @DisplayName("알림 목록 조회")
    class GetNotifications {

        @Test
        @DisplayName("성공: 알림 목록이 반환된다")
        void success_returnsNotificationList() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            List<NotificationItemDto> items = List.of(
                    notificationItem(3L), notificationItem(2L), notificationItem(1L));
            given(notificationRepository.findNotificationsByUserId(DEFAULT_USER_ID, null, 11))
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
            given(notificationRepository.findNotificationsByUserId(DEFAULT_USER_ID, null, 31))
                    .willReturn(new ArrayList<>());

            notificationService.getNotifications(new NotificationQueryDto(null, 50));

            then(notificationRepository).should().findNotificationsByUserId(DEFAULT_USER_ID, null, 31);
        }

        @Test
        @DisplayName("성공: hasMore가 올바르게 설정된다")
        void success_hasMoreIsSetCorrectly() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            List<NotificationItemDto> items = List.of(
                    notificationItem(3L), notificationItem(2L), notificationItem(1L));
            given(notificationRepository.findNotificationsByUserId(DEFAULT_USER_ID, null, 3))
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
        @DisplayName("성공: 개수가 반환된다")
        void success_returnsUnreadCount() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(notificationRepository.countUnreadByUserId(DEFAULT_USER_ID)).willReturn(5L);

            Long count = notificationService.getUnreadCount();

            assertThat(count).isEqualTo(5L);
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAsRead {

        @Test
        @DisplayName("성공: 알림이 읽음으로 변경된다")
        void success_notificationIsMarkedAsRead() {
            User user = user();
            Notification notification = likeNotification(user);
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(notificationRepository.findByIdWithFetchJoin(1L)).willReturn(notification);

            notificationService.markAsRead(1L);

            assertThat(notification.isRead()).isTrue();
        }

        @Test
        @DisplayName("실패: 알림이 존재하지 않으면 NOTIFICATION_NOT_FOUND")
        void fail_notificationNotFound() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(notificationRepository.findByIdWithFetchJoin(999L)).willReturn(null);

            assertThatThrownBy(() -> notificationService.markAsRead(999L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 다른 사용자의 알림이면 NOTIFICATION_NOT_FOUND")
        void fail_otherUserNotification() {
            User other = otherUser();
            Notification notification = likeNotification(other);
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(notificationRepository.findByIdWithFetchJoin(1L)).willReturn(notification);

            assertThatThrownBy(() -> notificationService.markAsRead(1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("모든 알림 읽음")
    class MarkAllAsRead {

        @Test
        @DisplayName("성공: 모든 알림이 읽음으로 변경된다")
        void success_allNotificationsMarkedAsRead() {
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(notificationRepository.markAllAsReadByUserId(DEFAULT_USER_ID)).willReturn(3L);

            notificationService.markAllAsRead();

            then(notificationRepository).should().markAllAsReadByUserId(DEFAULT_USER_ID);
        }
    }

    @Nested
    @DisplayName("알림 삭제")
    class DeleteNotification {

        @Test
        @DisplayName("성공: 알림이 삭제된다")
        void success_notificationIsDeleted() {
            User user = user();
            Notification notification = likeNotification(user);
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(notificationRepository.findByIdWithFetchJoin(1L)).willReturn(notification);

            notificationService.deleteNotification(1L);

            then(notificationRepository).should().delete(notification);
        }

        @Test
        @DisplayName("실패: 다른 사용자의 알림이면 NOTIFICATION_NOT_FOUND")
        void fail_otherUserNotification() {
            User other = otherUser();
            Notification notification = likeNotification(other);
            given(authService.getCurrentUserId()).willReturn(DEFAULT_USER_ID);
            given(notificationRepository.findByIdWithFetchJoin(1L)).willReturn(notification);

            assertThatThrownBy(() -> notificationService.deleteNotification(1L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
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
            given(notificationRepository.save(any(Notification.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            notificationService.createNotification(createDto);

            then(notificationRepository).should().save(any(Notification.class));
            then(eventPublisher).should().publishEvent(any(NotificationCreatedEvent.class));
        }
    }
}
