package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.NotificationRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationCreatedEvent;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
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

    @Mock UserRepository userRepo;
    @Mock NotificationTypeRepository typeRepo;
    @Mock NotificationRepository notificationRepo;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks NotificationService service;

    @Test
    @DisplayName("템플릿 인자에 맞춰 content 가 렌더링되고 DTO 가 반환된다")
    void sendNotification_success() {
        // given
        Long userId = 1L;
        User to = mock(User.class);
        given(userRepo.findById(userId)).willReturn(Optional.of(to));

        NotificationType likeNt = new NotificationType(
                Type.LIKE,
                "회원 %s님이 회원 %s님의 글을 좋아합니다."
        );
        given(typeRepo.findByType(Type.LIKE))
                .willReturn(Optional.of(likeNt));

        // save 시 입력된 Notification 을 그대로 리턴
        given(notificationRepo.save(any(Notification.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // 플레이스홀더 인자를 배열로 넘긴다
        NotificationRequestDto dto = NotificationRequestDto.builder()
                .userId(userId)
                .typeCode("LIKE")
                .args(new String[]{"Alice", "Bob"})
                .build();

        // when
        NotificationResponseDto resp = service.sendNotification(dto);

        // then
        then(userRepo).should().findById(userId);
        then(typeRepo).should().findByType(Type.LIKE);
        then(notificationRepo).should().save(any());
        then(eventPublisher).should()
                .publishEvent(any(NotificationCreatedEvent.class));

        assertThat(resp.getContent())
                .isEqualTo("회원 Alice님이 회원 Bob님의 글을 좋아합니다.");
        assertThat(resp.isFcmSent()).isFalse();
    }

    @Test
    @DisplayName("알림 타입이 없으면 NOTIFICATION_TYPE_NOT_FOUND 예외 발생")
    void sendNotification_noType() {
        // given
        Long userId = 2L;
        User to = mock(User.class);
        given(userRepo.findById(userId)).willReturn(Optional.of(to));
        given(typeRepo.findByType(Type.COMMENT))
                .willReturn(Optional.empty());

        NotificationRequestDto dto = NotificationRequestDto.builder()
                .userId(userId)
                .typeCode("COMMENT")
                .args(new String[]{"X"})
                .build();

        // when & then
        CustomException ex = assertThrows(CustomException.class,
                () -> service.sendNotification(dto)
        );
        assertThat(ex.getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);

        then(notificationRepo).should(never()).save(any());
        then(eventPublisher).should(never()).publishEvent(any());
    }

    @Test
    @DisplayName("markAsRead: 존재하는 알림은 읽음 처리된다")
    void markAsRead_success() {
        Notification mockN = mock(Notification.class);
        given(notificationRepo.findById(100L))
                .willReturn(Optional.of(mockN));

        service.markAsRead(100L);

        then(notificationRepo).should().findById(100L);
        then(mockN).should().markAsRead();
    }

    @Test
    @DisplayName("markAsRead: 알림이 없으면 NOTIFICATION_NOT_FOUND 예외 발생")
    void markAsRead_notFound() {
        given(notificationRepo.findById(200L))
                .willReturn(Optional.empty());

        CustomException ex = assertThrows(CustomException.class,
                () -> service.markAsRead(200L)
        );
        assertThat(ex.getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);

        then(notificationRepo).should().findById(200L);
        then(notificationRepo).should(never()).save(any());
        then(eventPublisher).should(never()).publishEvent(any());
    }
}