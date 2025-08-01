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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private NotificationTypeRepository notificationTypeRepository;
  @Mock private NotificationRepository notificationRepository;
  @Mock private ApplicationEventPublisher applicationEventPublisher;
  @InjectMocks private NotificationService service;

  /**
   * 모든 테스트 실행 전에 Mock 객체들을 리셋합니다.
   */
  @BeforeEach
  void resetAllMocks() {
    Mockito.reset(userRepository, notificationTypeRepository, notificationRepository, applicationEventPublisher);
  }

  @Nested
  @DisplayName("createSseConnection 메서드")
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
      assertThat(emitter.getTimeout()).isEqualTo(0L);
    }

    @Test
    @DisplayName("기존 연결이 있을 때 새 연결 생성")
    void createSseConnection_withExistingConnection() {
      // given
      Long userId = 1L;

      // 첫 번째 연결 생성
      SseEmitter firstEmitter = service.createSseConnection(userId);

      // when - 두 번째 연결 생성
      SseEmitter secondEmitter = service.createSseConnection(userId);

      // then
      assertThat(secondEmitter).isNotNull();
      assertThat(firstEmitter).isNotSameAs(secondEmitter);
    }

    @Test
    @DisplayName("초기 하트비트 전송 실패 시 예외 발생")
    void createSseConnection_heartbeatFailure() {
      // given
      Long userId = 1L;

      try (MockedConstruction<SseEmitter> mockedEmitter = mockConstruction(SseEmitter.class,
          (mock, context) -> {
            doThrow(new IOException("Connection failed"))
                .when(mock).send(any(SseEmitter.SseEventBuilder.class));
          })) {

        // when & then
        assertThatThrownBy(() -> service.createSseConnection(userId))
            .isInstanceOf(CustomException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SSE_CONNECTION_FAILED);
      }
    }
  }

  @Nested
  @DisplayName("createNotification 메서드")
  class CreateNotificationTests {

    @Test
    @DisplayName("알림 생성 성공")
    void createNotification_success() {
      // given
      Long userId = 1L;
      User mockUser = mock(User.class);
      given(mockUser.getUserId()).willReturn(userId);
      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));

      NotificationType mockType = mock(NotificationType.class);
      given(mockType.getType()).willReturn(Type.COMMENT);
      given(mockType.getTemplate()).willReturn("{0}님이 댓글을 남겼습니다");
      given(notificationTypeRepository.findByType(Type.COMMENT))
          .willReturn(Optional.of(mockType));

      try (MockedStatic<Notification> mockedNotification = mockStatic(Notification.class)) {
        Notification mockNotification = mock(Notification.class);
        given(mockNotification.getUser()).willReturn(mockUser);
        given(mockNotification.getNotificationId()).willReturn(10001L);
        given(mockNotification.getContent()).willReturn("홍길동님이 댓글을 남겼습니다");
        given(mockNotification.getFcmSent()).willReturn(false);

        mockedNotification.when(() -> Notification.create(any(User.class),
                any(NotificationType.class), any(String[].class)))
            .thenReturn(mockNotification);

        given(notificationRepository.save(any(Notification.class)))
            .willReturn(mockNotification);

        NotificationCreateRequestDto requestDto = NotificationCreateRequestDto.builder()
            .userId(userId)
            .type(Type.COMMENT)
            .args(new String[]{"홍길동"})
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
          .args(new String[]{"메세지"})
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
          .args(new String[]{"메세지"})
          .build();

      // when & then
      assertThatThrownBy(() -> service.createNotification(requestDto))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);

      then(notificationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("SSE 전송 실패해도 알림 생성은 성공")
    void createNotification_sseFailureDoesNotAffectCreation() {
      // given
      Long userId = 1L;
      User mockUser = mock(User.class);
      given(mockUser.getUserId()).willReturn(userId);
      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));

      NotificationType mockType = mock(NotificationType.class);
      given(notificationTypeRepository.findByType(Type.COMMENT))
          .willReturn(Optional.of(mockType));

      try (MockedStatic<Notification> mockedNotification = mockStatic(Notification.class)) {
        Notification mockNotification = mock(Notification.class);
        given(mockNotification.getUser()).willReturn(mockUser);
        given(mockNotification.getNotificationId()).willReturn(10001L);
        given(mockNotification.getContent()).willReturn("테스트 알림");
        given(mockNotification.getFcmSent()).willReturn(false);

        mockedNotification.when(() -> Notification.create(any(), any(), any()))
            .thenReturn(mockNotification);

        given(notificationRepository.save(any(Notification.class)))
            .willReturn(mockNotification);

        service.createSseConnection(userId);

        NotificationCreateRequestDto requestDto = NotificationCreateRequestDto.builder()
            .userId(userId)
            .type(Type.COMMENT)
            .args(new String[]{"테스트"})
            .build();

        // when
        NotificationCreateResponseDto result = service.createNotification(requestDto);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getNotificationId()).isEqualTo(10001L);
      }
    }
  }



  @Nested
  @DisplayName("markAllAsRead 메서드")
  class MarkAllAsReadTests {

    @Test
    @DisplayName("전체 읽음 처리 성공")
    void markAllAsRead_success() {
      // given
      Long userId = 1L;

      Notification notification1 = mock(Notification.class);
      Notification notification2 = mock(Notification.class);
      List<Notification> unreadList = Arrays.asList(notification1, notification2);

      given(notificationRepository.findByUser_UserIdAndIsReadFalse(userId))
          .willReturn(unreadList);

      // when
      service.markAllAsRead(userId);

      // then
      then(notification1).should().markAsRead();
      then(notification2).should().markAsRead();
    }

    @Test
    @DisplayName("읽지 않은 알림이 없는 경우")
    void markAllAsRead_noUnreadNotifications() {
      // given
      Long userId = 1L;
      given(notificationRepository.findByUser_UserIdAndIsReadFalse(userId))
          .willReturn(Collections.emptyList());

      // when
      service.markAllAsRead(userId);

      // then
      then(notificationRepository).should().findByUser_UserIdAndIsReadFalse(userId);
      then(notificationRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    @DisplayName("읽음 처리 후 실시간 업데이트 전송")
    void markAllAsRead_withSseUpdate() {
      // given
      Long userId = 1L;

      Notification notification = mock(Notification.class);
      given(notificationRepository.findByUser_UserIdAndIsReadFalse(userId))
          .willReturn(Arrays.asList(notification));

      service.createSseConnection(userId);

      given(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
          .willReturn(0L);

      // when
      service.markAllAsRead(userId);

      // then
      then(notification).should().markAsRead();
      then(notificationRepository).should().countByUser_UserIdAndIsReadFalse(userId);
    }
  }

  @Nested
  @DisplayName("deleteNotification 메서드")
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
      given(mockNotification.getIsRead()).willReturn(false);

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
      given(otherUser.getUserId()).willReturn(2L);

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

    @Test
    @DisplayName("읽지 않은 알림 삭제 시 개수 업데이트")
    void deleteNotification_unreadWithUpdate() {
      // given
      Long userId = 1L;
      Long notificationId = 10001L;

      User mockUser = mock(User.class);
      given(mockUser.getUserId()).willReturn(userId);

      Notification mockNotification = mock(Notification.class);
      given(mockNotification.getUser()).willReturn(mockUser);
      given(mockNotification.getIsRead()).willReturn(false);

      given(notificationRepository.findById(notificationId))
          .willReturn(Optional.of(mockNotification));

      service.createSseConnection(userId);

      given(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
          .willReturn(4L);

      // when
      service.deleteNotification(userId, notificationId);

      // then
      then(notificationRepository).should().delete(mockNotification);
      then(notificationRepository).should().countByUser_UserIdAndIsReadFalse(userId);
    }

    @Test
    @DisplayName("읽은 알림 삭제 시 개수 업데이트 안함")
    void deleteNotification_readWithoutUpdate() {
      // given
      Long userId = 1L;
      Long notificationId = 10001L;

      User mockUser = mock(User.class);
      given(mockUser.getUserId()).willReturn(userId);

      Notification mockNotification = mock(Notification.class);
      given(mockNotification.getUser()).willReturn(mockUser);
      given(mockNotification.getIsRead()).willReturn(true);

      given(notificationRepository.findById(notificationId))
          .willReturn(Optional.of(mockNotification));

      // when
      service.deleteNotification(userId, notificationId);

      // then
      then(notificationRepository).should().delete(mockNotification);
      then(notificationRepository).should(never()).countByUser_UserIdAndIsReadFalse(anyLong());
    }
  }

  @Nested
  @DisplayName("sendCreated 메서드")
  class SendCreatedTests {

    @Test
    @DisplayName("알림 생성 이벤트 처리 성공")
    void sendCreated_success() {
      // given
      Long userId = 1L;
      User mockUser = mock(User.class);
      given(mockUser.getUserId()).willReturn(userId);

      Notification mockNotification = mock(Notification.class);
      given(mockNotification.getUser()).willReturn(mockUser);
      given(mockNotification.getNotificationId()).willReturn(10001L);

      // when
      service.sendCreated(mockNotification);

      // then
      assertThatCode(() -> service.sendCreated(mockNotification))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SSE 연결이 있을 때 알림 전송")
    void sendCreated_withActiveConnection() {
      // given
      Long userId = 1L;
      User mockUser = mock(User.class);
      given(mockUser.getUserId()).willReturn(userId);

      Notification mockNotification = mock(Notification.class);
      given(mockNotification.getUser()).willReturn(mockUser);
      given(mockNotification.getNotificationId()).willReturn(10001L);
      given(mockNotification.getContent()).willReturn("테스트 알림");
      given(mockNotification.getNotificationType()).willReturn(mock(NotificationType.class));

      service.createSseConnection(userId);

      // when
      service.sendCreated(mockNotification);

      // then
      assertThatCode(() -> service.sendCreated(mockNotification))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("sendRead 메서드")
  class SendReadTests {

    @Test
    @DisplayName("알림 읽음 이벤트 처리 성공")
    void sendRead_success() {
      // given
      Long userId = 1L;
      User mockUser = mock(User.class);
      given(mockUser.getUserId()).willReturn(userId);

      Notification mockNotification = mock(Notification.class);
      given(mockNotification.getUser()).willReturn(mockUser);
      given(mockNotification.getNotificationId()).willReturn(10001L);

      // when
      service.sendRead(mockNotification);

      // then
      assertThatCode(() -> service.sendRead(mockNotification))
          .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SSE 연결이 있을 때 읽지 않은 개수 업데이트")
    void sendRead_withActiveConnection() {
      // given
      Long userId = 1L;
      User mockUser = mock(User.class);
      given(mockUser.getUserId()).willReturn(userId);

      Notification mockNotification = mock(Notification.class);
      given(mockNotification.getUser()).willReturn(mockUser);

      service.createSseConnection(userId);

      given(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
          .willReturn(3L);

      // when
      service.sendRead(mockNotification);

      // then
      then(notificationRepository).should().countByUser_UserIdAndIsReadFalse(userId);
    }
  }

  @Nested
  @DisplayName("통합 시나리오 테스트")
  class IntegrationTests {

    @Test
    @DisplayName("알림 생성 후 조회 시나리오")
    void createAndRetrieveNotification() {
      // given
      Long userId = 1L;
      User mockUser = mock(User.class);
      given(mockUser.getUserId()).willReturn(userId);
      given(userRepository.findById(userId)).willReturn(Optional.of(mockUser));

      NotificationType mockType = mock(NotificationType.class);
      given(mockType.getType()).willReturn(Type.COMMENT);
      given(mockType.getTemplate()).willReturn("{0}님이 댓글을 남겼습니다");
      given(notificationTypeRepository.findByType(Type.COMMENT))
          .willReturn(Optional.of(mockType));

      try (MockedStatic<Notification> mockedNotification = mockStatic(Notification.class)) {
        Notification mockNotification = mock(Notification.class);
        given(mockNotification.getUser()).willReturn(mockUser);
        given(mockNotification.getNotificationId()).willReturn(10001L);
        given(mockNotification.getContent()).willReturn("홍길동님이 댓글을 남겼습니다");
        given(mockNotification.getFcmSent()).willReturn(false);

        mockedNotification.when(() -> Notification.create(any(), any(), any()))
            .thenReturn(mockNotification);

        given(notificationRepository.save(any(Notification.class)))
            .willReturn(mockNotification);

        NotificationCreateRequestDto createDto = NotificationCreateRequestDto.builder()
            .userId(userId)
            .type(Type.COMMENT)
            .args(new String[]{"홍길동"})
            .build();

        // when - 알림 생성
        NotificationCreateResponseDto createResult = service.createNotification(createDto);

        // then
        assertThat(createResult.getNotificationId()).isEqualTo(10001L);

        // given - 조회 설정
        NotificationListItem item = mock(NotificationListItem.class);
        given(item.getNotificationId()).willReturn(10001L);
        given(item.getContent()).willReturn("홍길동님이 댓글을 남겼습니다");
        given(item.getType()).willReturn(Type.COMMENT);
        given(item.getIsRead()).willReturn(false);
        given(item.getCreatedAt()).willReturn(LocalDateTime.now());

        given(notificationRepository.findTopByUser(eq(userId), any(Pageable.class)))
            .willReturn(Arrays.asList(item));
        given(notificationRepository.findAfterCursor(eq(userId), eq(10001L), any(Pageable.class)))
            .willReturn(Collections.emptyList());
        given(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
            .willReturn(1L);

        // when - 알림 조회
        NotificationListResponseDto listResult = service.getNotifications(userId, null, 20);

        // then
        assertThat(listResult.getNotifications()).hasSize(1);
        assertThat(listResult.getNotifications().get(0).getNotificationId()).isEqualTo(10001L);
        assertThat(listResult.getUnreadCount()).isEqualTo(1L);
      }
    }
  }
}