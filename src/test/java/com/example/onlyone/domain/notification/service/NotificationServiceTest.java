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
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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

    @Nested @DisplayName("markAllAsRead")
    class MarkAllAsReadTests {
        @Test @DisplayName("성공: updatedCount > 0")
        void success() {
            NotificationListRequestDto dto = NotificationListRequestDto.builder()
                    .userId(10L).build();
            given(notificationRepo.markAllAsReadByUserId(10L)).willReturn(5);

            service.markAllAsRead(dto);

            then(notificationRepo).should().markAllAsReadByUserId(10L);
        }

        @Test @DisplayName("실패: updatedCount == 0 → NOTIFICATION_NOT_FOUND")
        void notFound() {
            NotificationListRequestDto dto = NotificationListRequestDto.builder()
                    .userId(20L).build();
            given(notificationRepo.markAllAsReadByUserId(20L)).willReturn(0);

            CustomException ex = assertThrows(CustomException.class,
                    () -> service.markAllAsRead(dto)
            );
            assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);

            then(notificationRepo).should().markAllAsReadByUserId(20L);
        }
    }
}