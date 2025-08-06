package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.requestDto.NotificationListItem;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.google.firebase.messaging.FirebaseMessagingException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  @Mock UserRepository userRepository;
  @Mock NotificationTypeRepository notificationTypeRepository;
  @Mock NotificationRepository notificationRepository;
  @Mock SseEmittersService sseEmittersService;
  @Mock FcmService fcmService;
  @InjectMocks NotificationService service;

  @Nested
  class CreateNotificationTests {

    @Test
    void success_triggersSseAndFcm() throws FirebaseMessagingException {
      // given
      Long userId = 1L;
      User user = mock(User.class);
      given(user.getUserId()).willReturn(userId);
      given(userRepository.findById(userId))
          .willReturn(Optional.of(user));

      // notificationRepository.save(any()) → argument 그대로 리턴
      given(notificationRepository.save(any(Notification.class)))
          .willAnswer(invocation -> invocation.getArgument(0));

      NotificationType nt = mock(NotificationType.class);
      given(notificationTypeRepository.findByType(Type.COMMENT))
          .willReturn(Optional.of(nt));

      // static factory Notification.create()
      try (var mc = mockStatic(Notification.class)) {
        Notification created = mock(Notification.class);

        // 1) Notification.create(...) → 우리가 만든 mock 반환
        given(Notification.create(eq(user), eq(nt), any(String[].class)))
            .willReturn(created);

        // 2) created.getUser() → user 반환
        given(created.getUser()).willReturn(user);

        // 3) created.getNotificationId() → 42L 반환
        given(created.getNotificationId()).willReturn(42L);

        // save(created) → 다시 created 를 반환
        given(notificationRepository.save(created))
            .willReturn(created);

        // when
        NotificationCreateRequestDto dto = NotificationCreateRequestDto.builder()
            .userId(userId)
            .type(Type.COMMENT)
            .args(new String[]{"A"})
            .build();

        NotificationCreateResponseDto resp = service.createNotification(dto);

        // then
        assertThat(resp.getNotificationId()).isEqualTo(42L);
        then(sseEmittersService).should()
            .sendSseNotification(userId, created);
        then(fcmService).should()
            .sendFcmNotification(created);
      }
    }

    @Test
    void userNotFound_throwsUserNotFound() {
      given(userRepository.findById(99L)).willReturn(Optional.empty());

      NotificationCreateRequestDto dto = NotificationCreateRequestDto.builder()
          .userId(99L).type(Type.COMMENT).args(new String[]{}).build();

      assertThatThrownBy(() -> service.createNotification(dto))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);

      then(notificationTypeRepository).shouldHaveNoInteractions();
      then(notificationRepository).shouldHaveNoInteractions();
      then(sseEmittersService).shouldHaveNoInteractions();
      then(fcmService).shouldHaveNoInteractions();
    }

    @Test
    void typeNotFound_throwsTypeNotFound() {
      Long userId = 1L;
      User user = mock(User.class);
      given(user.getUserId()).willReturn(userId);
      given(userRepository.findById(userId)).willReturn(Optional.of(user));
      given(notificationTypeRepository.findByType(Type.COMMENT))
          .willReturn(Optional.empty());

      NotificationCreateRequestDto dto = NotificationCreateRequestDto.builder()
          .userId(userId).type(Type.COMMENT).args(new String[]{}).build();

      assertThatThrownBy(() -> service.createNotification(dto))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);

      then(notificationRepository).shouldHaveNoInteractions();
      then(sseEmittersService).shouldHaveNoInteractions();
      then(fcmService).shouldHaveNoInteractions();
    }
  }

  @Nested
  class GetNotificationsTests {

    @Test
    void firstPage_success() {
      Long userId = 1L;
      NotificationListItem item = new NotificationListItem(
          10L, "hi", Type.LIKE, false, LocalDateTime.now());
      given(notificationRepository.findByUserIdOrderByNotificationIdDesc(
          eq(userId), any(Pageable.class)))
          .willReturn(List.of(item));
      given(notificationRepository.findAfterCursor(
          eq(userId), anyLong(), any(Pageable.class)))
          .willReturn(Collections.emptyList());
      given(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
          .willReturn(5L);

      NotificationListResponseDto resp = service.getNotifications(userId, null, 20);

      assertThat(resp.getNotifications()).hasSize(1);
      assertThat(resp.getUnreadCount()).isEqualTo(5L);
      assertThat(resp.isHasMore()).isFalse();
      assertThat(resp.getCursor()).isEqualTo(10L);
    }

    @Test
    void nextPage_hasMoreTrue() {
      Long userId = 1L;
      NotificationListItem i1 = new NotificationListItem(20L,"A",Type.CHAT,false,LocalDateTime.now());
      NotificationListItem i2 = new NotificationListItem(10L,"B",Type.COMMENT,false,LocalDateTime.now());
      given(notificationRepository.findByUserIdOrderByNotificationIdDesc(userId, PageRequest.of(0,2)))
          .willReturn(List.of(i1,i2));
      // afterCursor returns one more to indicate hasMore
      given(notificationRepository.findAfterCursor(userId, 10L, PageRequest.of(0,1)))
          .willReturn(List.of(i1));
      given(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
          .willReturn(3L);

      NotificationListResponseDto resp = service.getNotifications(userId, null, 2);

      assertThat(resp.getNotifications()).extracting("notificationId")
          .containsExactly(20L,10L);
      assertThat(resp.isHasMore()).isTrue();
      assertThat(resp.getCursor()).isEqualTo(10L);
      assertThat(resp.getUnreadCount()).isEqualTo(3L);
    }
  }

  @Nested
  class MarkAllAsReadTests {

    @Test
    void noUnread_noInteraction() {
      Long userId = 1L;
      given(notificationRepository.findByUser_UserIdAndIsReadFalse(userId))
          .willReturn(Collections.emptyList());

      service.markAllAsRead(userId);

      then(sseEmittersService).shouldHaveNoInteractions();
    }

    @Test
    void someUnread_triggersUpdate() {
      Long userId = 1L;
      Notification n = mock(Notification.class);
      given(notificationRepository.findByUser_UserIdAndIsReadFalse(userId))
          .willReturn(List.of(n));

      service.markAllAsRead(userId);

      then(n).should().markAsRead();
      then(sseEmittersService).should().sendUnreadCountUpdate(userId);
    }
  }

  @Nested
  class DeleteNotificationTests {

    @Test
    void successUnread_triggersUpdate() {
      Long userId = 1L, nid = 100L;
      User user = mock(User.class);
      given(user.getUserId()).willReturn(userId);
      Notification notif = mock(Notification.class);
      given(notif.getUser()).willReturn(user);
      given(notif.getIsRead()).willReturn(false);
      given(notificationRepository.findById(nid))
          .willReturn(Optional.of(notif));

      service.deleteNotification(userId, nid);

      then(notificationRepository).should().delete(notif);
      then(sseEmittersService).should().sendUnreadCountUpdate(userId);
    }

    @Test
    void successRead_noUpdate() {
      Long userId = 1L, nid = 200L;
      User user = mock(User.class);
      given(user.getUserId()).willReturn(userId);
      Notification notif = mock(Notification.class);
      given(notif.getUser()).willReturn(user);
      given(notif.getIsRead()).willReturn(true);
      given(notificationRepository.findById(nid))
          .willReturn(Optional.of(notif));

      service.deleteNotification(userId, nid);

      then(notificationRepository).should().delete(notif);
      then(sseEmittersService).should(never()).sendUnreadCountUpdate(any());
    }

    @Test
    void notFound_throws() {
      given(notificationRepository.findById(500L))
          .willReturn(Optional.empty());

      assertThatThrownBy(() -> service.deleteNotification(1L, 500L))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    void wrongOwner_throws() {
      Long userId = 1L, nid = 300L;
      User other = mock(User.class);
      given(other.getUserId()).willReturn(2L);
      Notification notif = mock(Notification.class);
      given(notif.getUser()).willReturn(other);
      given(notificationRepository.findById(nid))
          .willReturn(Optional.of(notif));

      assertThatThrownBy(() -> service.deleteNotification(userId,nid))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
    }
  }

  @Nested
  class SendEventTests {

    @Test
    void sendCreated_usesSseService() {
      Long userId = 1L;
      User user = mock(User.class);
      given(user.getUserId()).willReturn(userId);
      Notification notif = mock(Notification.class);
      given(notif.getUser()).willReturn(user);

      service.sendCreated(notif);

      then(sseEmittersService).should().sendSseNotification(userId, notif);
    }

    @Test
    void sendRead_usesSseService() {
      Long userId = 1L;
      User user = mock(User.class);
      given(user.getUserId()).willReturn(userId);
      Notification notif = mock(Notification.class);
      given(notif.getUser()).willReturn(user);

      service.sendRead(notif);

      then(sseEmittersService).should().sendUnreadCountUpdate(userId);
    }
  }
}