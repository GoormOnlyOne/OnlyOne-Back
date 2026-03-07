package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.dto.request.NotificationCreateDto;
import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.example.onlyone.domain.notification.fixture.NotificationFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 파사드 테스트")
class NotificationServiceTest {

    @InjectMocks private NotificationService notificationService;
    @Mock private NotificationCommandService commandService;
    @Mock private NotificationQueryService queryService;

    @Nested
    @DisplayName("조회 위임")
    class QueryDelegation {

        @Test
        @DisplayName("getNotifications → queryService 위임")
        void delegatesToQueryService() {
            NotificationQueryDto dto = new NotificationQueryDto(null, 10);
            List<NotificationItemDto> items = List.of(notificationItem(1L));
            NotificationListResponseDto expected = new NotificationListResponseDto(items, 1L, false);
            given(queryService.getNotifications(dto)).willReturn(expected);

            NotificationListResponseDto result = notificationService.getNotifications(dto);

            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("getUnreadCount → queryService 위임")
        void delegatesUnreadCount() {
            given(queryService.getUnreadCount()).willReturn(5L);

            assertThat(notificationService.getUnreadCount()).isEqualTo(5L);
        }
    }

    @Nested
    @DisplayName("명령 위임")
    class CommandDelegation {

        @Test
        @DisplayName("markAsRead → commandService 위임")
        void delegatesMarkAsRead() {
            notificationService.markAsRead(1L);
            then(commandService).should().markAsRead(1L);
        }

        @Test
        @DisplayName("deleteNotification → commandService 위임")
        void delegatesDelete() {
            notificationService.deleteNotification(1L);
            then(commandService).should().deleteNotification(1L);
        }

        @Test
        @DisplayName("markAllAsRead → commandService 위임")
        void delegatesMarkAllAsRead() {
            notificationService.markAllAsRead();
            then(commandService).should().markAllAsRead();
        }

        @Test
        @DisplayName("createNotification → commandService 위임")
        void delegatesCreate() {
            User user = user();
            NotificationCreateDto dto =
                    new NotificationCreateDto(user, NotificationType.LIKE, "홍길동");

            notificationService.createNotification(dto);

            then(commandService).should().createNotification(dto);
        }
    }
}
