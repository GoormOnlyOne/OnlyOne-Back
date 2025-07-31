package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.*;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
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
import org.springframework.data.domain.Pageable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  @Mock UserRepository userRepository;
  @Mock NotificationTypeRepository notificationTypeRepository;
  @Mock NotificationRepository notificationRepository;
  @Mock ApplicationEventPublisher applicationEventPublisher;
  @InjectMocks NotificationService service;

  @Nested @DisplayName("createSseConnection")
  class CreateSseConnectionTests {

    @Test
    @DisplayName("SSE 연결 생성 성공")
    void createSseConnection_success() {
      // given
      Long userId = 1L;

      // when
      SseEmitter emitter = service.createSseConnection(userId);

      // then
      assertThat(emitter).isNotNull();
      assertThat(service.getActiveConnectionCount()).isEqualTo(1);
    }
  }

  @Nested @DisplayName("createNotification")
  class CreateNotificationTests {

    @Test @DisplayName("알림 생성 성공")
    void createNotification_success() {
      // given
      Long userId = 1L;
      User mockUser = mock(User.class);
      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));

      NotificationType mockType = mock(NotificationType.class);
      given(notificationTypeRepository.findByType(Type.COMMENT))
          .willReturn(Optional.of(mockType));

      Notification mockNotification = mock(Notification.class);
      // **여기 추가**
      given(mockNotification.getUser()).willReturn(mockUser);

      given(mockNotification.getNotificationId()).willReturn(10001L);
      given(mockNotification.getContent()).willReturn("홍길동님이 댓글을 남겼습니다");
      given(mockNotification.getFcmSent()).willReturn(false);

      given(notificationRepository.save(any(Notification.class)))
          .willReturn(mockNotification);

      NotificationCreateRequestDto requestDto = NotificationCreateRequestDto.builder()
          .userId(userId)
          .type(Type.COMMENT)
          .args(new String[]{ "홍길동" })
          .build();

      // when
      NotificationCreateResponseDto result = service.createNotification(requestDto);

      // then
      assertThat(result.getNotificationId()).isEqualTo(10001L);
      assertThat(result.getContent()).isEqualTo("홍길동님이 댓글을 남겼습니다");
      assertThat(result.getFcmSent()).isFalse();

      then(userRepository).should().findById(userId);
      then(notificationTypeRepository).should().findByType(Type.COMMENT);
      then(notificationRepository).should().save(any(Notification.class));
    }

    @Test
    @DisplayName("사용자 없음 - USER_NOT_FOUND 예외")
    void createNotification_userNotFound() {
      // given
      Long userId = 999L;
      given(userRepository.findById(userId)).willReturn(Optional.empty());

      NotificationCreateRequestDto requestDto = NotificationCreateRequestDto.builder()
          .userId(userId)
          .type(Type.COMMENT)
          .args(new String[]{ "메세지" })
          .build();

      // when & then
      assertThatThrownBy(() -> service.createNotification(requestDto))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);

      then(notificationTypeRepository).shouldHaveNoInteractions();
      then(notificationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("알림 타입 없음 - NOTIFICATION_TYPE_NOT_FOUND 예외")
    void createNotification_typeNotFound() {
      // given
      Long userId = 1L;
      User mockUser = mock(User.class);
      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));
      given(notificationTypeRepository.findByType(Type.COMMENT))
          .willReturn(Optional.empty());

      NotificationCreateRequestDto requestDto = NotificationCreateRequestDto.builder()
          .userId(userId)
          .type(Type.COMMENT)
          .args(new String[]{ "메세지" })
          .build();

      // when & then
      assertThatThrownBy(() -> service.createNotification(requestDto))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);

      then(notificationRepository).shouldHaveNoInteractions();
    }
  }

  @Nested @DisplayName("getNotifications")
  class GetNotificationsTests {

    @Test
    @DisplayName("첫 페이지 조회 성공")
    void getNotifications_firstPage() {
      // given
      Long userId = 1L;
      int size = 20;

      NotificationListItem item1 = NotificationListItem.builder()
          .notificationId(10001L)
          .content("알림 1")
          .type(Type.COMMENT)
          .isRead(false)
          .build();

      NotificationListItem item2 = NotificationListItem.builder()
          .notificationId(10000L)
          .content("알림 2")
          .type(Type.LIKE)
          .isRead(true)
          .build();

      given(notificationRepository.findTopByUser(eq(userId), any(Pageable.class)))
          .willReturn(Arrays.asList(item1, item2));

      given(notificationRepository.findAfterCursor(eq(userId), eq(10000L), any(Pageable.class)))
          .willReturn(Arrays.asList(/* 더 있다고 가정 */ mock(NotificationListItem.class)));

      given(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
          .willReturn(5L);

      // when
      NotificationListResponseDto result = service.getNotifications(userId, null, size);

      // then
      assertThat(result.getNotifications()).hasSize(2);
      assertThat(result.getCursor()).isEqualTo(10000L);
      assertThat(result.isHasMore()).isTrue();
      assertThat(result.getUnreadCount()).isEqualTo(5L);

      then(notificationRepository).should()
          .findTopByUser(eq(userId), any(Pageable.class));
      then(notificationRepository).should()
          .countByUser_UserIdAndIsReadFalse(userId);
    }

    @Test
    @DisplayName("커서 페이징 조회 성공")
    void getNotifications_withCursor() {
      // given
      Long userId = 1L;
      Long cursor = 10000L;
      int size = 20;

      NotificationListItem item = NotificationListItem.builder()
          .notificationId(9999L)
          .content("이전 알림")
          .type(Type.CHAT)
          .isRead(false)
          .build();

      given(notificationRepository.findAfterCursor(eq(userId), eq(cursor), any(Pageable.class)))
          .willReturn(Arrays.asList(item));

      given(notificationRepository.findAfterCursor(eq(userId), eq(9999L), any(Pageable.class)))
          .willReturn(Collections.emptyList()); // 더 없음

      given(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
          .willReturn(3L);

      // when
      NotificationListResponseDto result = service.getNotifications(userId, cursor, size);

      // then
      assertThat(result.getNotifications()).hasSize(1);
      assertThat(result.getCursor()).isEqualTo(9999L);
      assertThat(result.isHasMore()).isFalse();
      assertThat(result.getUnreadCount()).isEqualTo(3L);
    }
  }

  @Nested @DisplayName("markAsRead")
  class MarkAsReadTests {

    @Test @DisplayName("개별 알림 읽음 처리 성공")
    void markAsRead_success() {
      // given
      Long userId = 1L;
      List<Long> notificationIds = Arrays.asList(10001L, 10002L);

      // 첫 번째 알림: 아직 읽지 않음 → markAsRead() 호출 기대
      Notification n1 = mock(Notification.class);
      given(n1.getIsRead()).willReturn(false);
      given(n1.getNotificationId()).willReturn(10001L);

      // 두 번째 알림: 이미 읽음 → markAsRead() 호출 안 함
      Notification n2 = mock(Notification.class);
      given(n2.getIsRead()).willReturn(true);
      // (두 번째 getNotificationId() 는 테스트 흐름에 쓰이지 않으므로 stub 할 필요 없습니다)

      // 서비스가 실제로 호출하는 메서드만 stub
      given(notificationRepository.findByUserIdAndIds(userId, notificationIds))
          .willReturn(Arrays.asList(n1, n2));

      NotificationReadRequestDto requestDto = NotificationReadRequestDto.builder()
          .notificationIds(notificationIds)
          .build();

      // when
      NotificationReadResponseDto result = service.markAsRead(userId, requestDto);

      // then
      assertThat(result.getUpdatedCount()).isEqualTo(1);
      then(n1).should().markAsRead();
      then(n2).should(never()).markAsRead();
    }
  }

  @Nested @DisplayName("deleteNotification")
  class DeleteNotificationTests {

    @Test
    @DisplayName("알림 삭제 성공")
    void deleteNotification_success() {
      // given
      Long userId = 1L;
      Long notificationId = 10001L;

      User mockUser = mock(User.class);
      given(mockUser.getUserId()).willReturn(userId);

      Notification mockNotification = mock(Notification.class);
      given(mockNotification.getUser()).willReturn(mockUser);

      given(notificationRepository.findById(notificationId))
          .willReturn(Optional.of(mockNotification));

      // when
      service.deleteNotification(userId, notificationId);

      // then
      then(notificationRepository).should().delete(mockNotification);
    }

    @Test
    @DisplayName("알림 없음 - NOTIFICATION_NOT_FOUND 예외")
    void deleteNotification_notFound() {
      // given
      Long userId = 1L;
      Long notificationId = 99999L;

      given(notificationRepository.findById(notificationId))
          .willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> service.deleteNotification(userId, notificationId))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);

      then(notificationRepository).should(never()).delete(any());
    }

    @Test
    @DisplayName("다른 사용자의 알림 삭제 시도 - NOTIFICATION_NOT_FOUND 예외")
    void deleteNotification_notOwner() {
      // given
      Long userId = 1L;
      Long notificationId = 10001L;

      User otherUser = mock(User.class);
      given(otherUser.getUserId()).willReturn(2L); // 다른 사용자

      Notification mockNotification = mock(Notification.class);
      given(mockNotification.getUser()).willReturn(otherUser);

      given(notificationRepository.findById(notificationId))
          .willReturn(Optional.of(mockNotification));

      // when & then
      assertThatThrownBy(() -> service.deleteNotification(userId, notificationId))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);

      then(notificationRepository).should(never()).delete(any());
    }
  }
}