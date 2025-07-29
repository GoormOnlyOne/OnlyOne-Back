package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationTypeRepository typeRepo;
    @Mock NotificationRepository notificationRepo;
    @Mock ApplicationEventPublisher eventPublisher;

    @InjectMocks NotificationService service;

    @Test
    @DisplayName("타입에 맞게 알림이 생성되고 저장된다")
    void sendNotification_success() {
        // given
        User to = mock(User.class);
        when(to.getNickname()).thenReturn("Bob");

        NotificationType likeNt = new NotificationType(
                Type.LIKE,
                "회원 %s님이 회원 %s님의 글을 좋아합니다."
        );
        given(typeRepo.findByType(Type.LIKE))
                .willReturn(Optional.of(likeNt));
        given(notificationRepo.save(any(Notification.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // when
        Notification result = service.sendNotification(
                to,
                Type.LIKE,
                "Alice",
                to.getNickname()
        );

        // then
        then(typeRepo).should().findByType(Type.LIKE);
        then(notificationRepo).should().save(any(Notification.class));
        then(eventPublisher).should()
                .publishEvent(any(NotificationCreatedEvent.class));

        assertThat(result.getUser()).isSameAs(to);
        assertThat(result.getNotificationType()).isSameAs(likeNt);
        assertThat(result.getContent())
                .isEqualTo("회원 Alice님이 회원 Bob님의 글을 좋아합니다.");
        assertThat(result.getIsRead()).isFalse();
    }

    @Test
    @DisplayName("알림 타입이 없으면 NOTIFY_404_1 CustomException 발생")
    void sendNotification_noType() {
        // given
        given(typeRepo.findByType(Type.COMMENT))
                .willReturn(Optional.empty());
        User to = mock(User.class);

        // when & then
        CustomException ex = assertThrows(
                CustomException.class,
                () -> service.sendNotification(to, Type.COMMENT, "X")
        );
        assertThat(ex.getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);
        assertThat(ex.getErrorCode().getCode())
                .isEqualTo("NOTIFY_404_1");

        then(notificationRepo).should(never()).save(any());
        then(eventPublisher).should(never())
                .publishEvent(any());
    }

    @Test
    @DisplayName("알림을 읽으면 읽음 처리로 변경된다")
    void markAsRead_success() {
        // given
        Notification mockNotification = mock(Notification.class);
        given(notificationRepo.findById(100L))
                .willReturn(Optional.of(mockNotification));

        // when
        service.markAsRead(100L);

        // then
        then(notificationRepo).should().findById(100L);
        then(mockNotification).should().markAsRead();
    }

    @Test
    @DisplayName("알림이 없으면 NOTIFY_404_2 CustomException 발생")
    void markAsRead_notFound() {
        // given
        given(notificationRepo.findById(200L))
                .willReturn(Optional.empty());

        // when & then
        CustomException ex = assertThrows(
                CustomException.class,
                () -> service.markAsRead(200L)
        );
        assertThat(ex.getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);

        then(notificationRepo).should().findById(200L);
        then(notificationRepo).should(never()).save(any());
    }
}