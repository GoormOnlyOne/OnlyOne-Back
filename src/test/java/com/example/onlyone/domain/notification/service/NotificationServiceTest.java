package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.NotificationListRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.event.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock UserRepository userRepo;
    @Mock NotificationTypeRepository typeRepo;
    @Mock NotificationRepository notificationRepo;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks NotificationService service;

    @Nested @DisplayName("sendNotification")
    class SendNotificationTests {

        @Test
        @DisplayName("성공: content/isRead/fcmSent 만 검증")
        void success_onlyCoreFields() {
            // given
            Long userId = 1L;
            User mockUser = mock(User.class);
            given(userRepo.findById(userId)).willReturn(Optional.of(mockUser));

            NotificationType nt = new NotificationType(
                    Type.LIKE,
                    "회원 %s님이 회원 %s님의 글을 좋아합니다."
            );
            given(typeRepo.findByType(Type.LIKE))
                    .willReturn(Optional.of(nt));

            // save() 시점에 id/createdAt 은 셋팅하지 않고 그냥 리턴
            given(notificationRepo.save(any(Notification.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            NotificationRequestDto dto = NotificationRequestDto.builder()
                    .userId(userId)
                    .type(Type.LIKE)
                    .args(new String[]{"Alice", "Bob"})
                    .build();

            // when
            NotificationResponseDto resp = service.sendNotification(dto);

            // then
            then(userRepo).should().findById(userId);
            then(typeRepo).should().findByType(Type.LIKE);
            then(notificationRepo).should().save(any(Notification.class));
            then(eventPublisher).should()
                    .publishEvent(any(NotificationCreatedEvent.class));

            // 핵심 필드들만 검증
            assertThat(resp.getContent())
                    .isEqualTo("회원 Alice님이 회원 Bob님의 글을 좋아합니다.");
            assertThat(resp.isRead()).isFalse();
            assertThat(resp.isFcmSent()).isFalse();

            // id/createdAt 는 save stub 때문에 null 이더라도 OK
        }
    }

    @Test
    @DisplayName("미읽음 알림이 있으면 모두 markAsRead() 호출")
    void whenUnreadExists_markAll() {
        // given
        Notification n1 = mock(Notification.class);
        Notification n2 = mock(Notification.class);
        given(notificationRepo.findByUser_UserIdAndIsReadFalse(1L))
                .willReturn(List.of(n1, n2));

        NotificationListRequestDto dto =
                NotificationListRequestDto.builder().userId(1L).build();

        // when
        service.markAllAsRead(dto);

        // then
        then(notificationRepo).should().findByUser_UserIdAndIsReadFalse(1L);
        then(n1).should().markAsRead();
        then(n2).should().markAsRead();
    }

    @Test
    @DisplayName("미읽음 알림이 없으면 예외 없이 no-op")
    void whenNoUnread_doNothing() {
        // given
        given(notificationRepo.findByUser_UserIdAndIsReadFalse(2L))
                .willReturn(Collections.emptyList());

        NotificationListRequestDto dto =
                NotificationListRequestDto.builder().userId(2L).build();

        // when/then (예외 없이 그냥 끝)
        assertDoesNotThrow(() -> service.markAllAsRead(dto));

        // 그리고 markAsRead() 자체가 불리지 않음
        then(notificationRepo).should().findByUser_UserIdAndIsReadFalse(2L);
    }
}