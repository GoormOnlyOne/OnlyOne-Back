package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.request.NotificationQueryDto;
import com.example.onlyone.domain.notification.dto.response.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.port.NotificationStoragePort;
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
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationQueryService 단위 테스트")
class NotificationQueryServiceTest {

    @InjectMocks private NotificationQueryService queryService;
    @Mock private NotificationStoragePort storagePort;
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
                    queryService.getNotifications(new NotificationQueryDto(null, 10));

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

            queryService.getNotifications(new NotificationQueryDto(null, 50));

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
                    queryService.getNotifications(new NotificationQueryDto(null, 2));

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

            Long count = queryService.getUnreadCount();

            assertThat(count).isEqualTo(5L);
        }
    }
}
