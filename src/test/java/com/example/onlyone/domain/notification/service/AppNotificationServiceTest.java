package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.google.firebase.messaging.FirebaseMessagingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

/**
 * NotificationService 완전한 테스트
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppNotificationServiceTest {

  @Mock
  private UserRepository userRepository;
  @Mock
  private NotificationTypeRepository notificationTypeRepository;
  @Mock
  private NotificationRepository notificationRepository;
  @Mock
  private SseEmittersService sseEmittersService;
  @Mock
  private FcmService fcmService;
  @InjectMocks
  private NotificationService service;

  @Nested
  @DisplayName("알림 생성 테스트")
  class CreateAppNotificationTests {

    @Test
    @DisplayName("정상적인 알림 생성 - SSE와 FCM 전송 모두 성공")
    void createNotification_Success() throws FirebaseMessagingException {
      // given
      NotificationCreateRequestDto request = createValidRequest();
      User mockUser = createMockUser();
      NotificationType mockType = createMockNotificationType();
      AppNotification mockAppNotification = createMockNotification(mockUser);

      given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
      given(notificationTypeRepository.findByType(Type.CHAT)).willReturn(Optional.of(mockType));
      given(notificationRepository.save(any(AppNotification.class))).willReturn(mockAppNotification);

      try (MockedStatic<AppNotification> mockedStatic = mockStatic(AppNotification.class)) {
        mockedStatic.when(() -> AppNotification.create(mockUser, mockType, new String[]{"홍길동"}))
            .thenReturn(mockAppNotification);

        // when
        NotificationCreateResponseDto result = service.createNotification(request);

        // then
        assertThat(result.getNotificationId()).isEqualTo(1L);
        then(sseEmittersService).should().sendSseNotification(1L, mockAppNotification);
        // FCM 호출은 void 메서드이므로 검증만
        then(fcmService).should().sendFcmNotification(mockAppNotification);
      }
    }

    @Test
    @DisplayName("사용자 없음 - 예외 발생")
    void createNotification_UserNotFound() {
      // given
      NotificationCreateRequestDto request = createValidRequest();
      given(userRepository.findById(1L)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> service.createNotification(request))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);

      then(notificationTypeRepository).shouldHaveNoInteractions();
      then(sseEmittersService).shouldHaveNoInteractions();
      then(fcmService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("알림 타입 없음 - 예외 발생")
    void createNotification_TypeNotFound() {
      // given
      NotificationCreateRequestDto request = createValidRequest();
      User mockUser = createMockUser();

      given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
      given(notificationTypeRepository.findByType(Type.CHAT)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> service.createNotification(request))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);

      then(notificationRepository).shouldHaveNoInteractions();
      then(sseEmittersService).shouldHaveNoInteractions();
      then(fcmService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("SSE 전송 실패해도 알림 생성은 성공")
    void createNotification_SseFailureButCreationSuccess() throws Exception {
      // given
      NotificationCreateRequestDto request = createValidRequest();
      setupMocksForSuccessfulCreation();

      willThrow(new RuntimeException("SSE 전송 실패"))
          .given(sseEmittersService).sendSseNotification(any(), any());

      // when
      NotificationCreateResponseDto result = service.createNotification(request);

      // then
      assertThat(result.getNotificationId()).isEqualTo(1L);
      then(fcmService).should().sendFcmNotification(any());
    }

    @Test
    @DisplayName("FCM 전송 실패 시 fcmSent=false로 설정")
    void createNotification_FcmFailure() throws Exception {
      // given
      NotificationCreateRequestDto request = createValidRequest();
      AppNotification mockAppNotification = setupMocksForSuccessfulCreation();

      willThrow(new RuntimeException("FCM 전송 실패"))
          .given(fcmService).sendFcmNotification(any());

      // when
      service.createNotification(request);

      // then
      then(mockAppNotification).should().markFcmSent(false);
    }
  }

  @Nested
  @DisplayName("알림 삭제 테스트")
  class DeleteAppNotificationTests {

    @Test
    @DisplayName("읽지 않은 알림 삭제 - 읽지 않은 개수 업데이트")
    void deleteNotification_UnreadNotification() {
      // given
      Long userId = 1L;
      Long notificationId = 10L;
      User mockUser = createMockUser();
      AppNotification mockAppNotification = createMockNotification(mockUser);

      given(mockAppNotification.getIsRead()).willReturn(false);
      given(notificationRepository.findById(notificationId)).willReturn(Optional.of(
          mockAppNotification));

      // when
      service.deleteNotification(userId, notificationId);

      // then
      then(notificationRepository).should().delete(mockAppNotification);
      then(sseEmittersService).should().sendUnreadCountUpdate(userId);
    }

    @Test
    @DisplayName("읽은 알림 삭제 - 읽지 않은 개수 업데이트 없음")
    void deleteNotification_ReadNotification() {
      // given
      Long userId = 1L;
      Long notificationId = 10L;
      User mockUser = createMockUser();
      AppNotification mockAppNotification = createMockNotification(mockUser);

      given(mockAppNotification.getIsRead()).willReturn(true);
      given(notificationRepository.findById(notificationId)).willReturn(Optional.of(
          mockAppNotification));

      // when
      service.deleteNotification(userId, notificationId);

      // then
      then(notificationRepository).should().delete(mockAppNotification);
      then(sseEmittersService).should(never()).sendUnreadCountUpdate(any());
    }

    @Test
    @DisplayName("다른 사용자의 알림 삭제 시도 - 권한 오류")
    void deleteNotification_UnauthorizedAccess() {
      // given
      Long userId = 1L;
      Long notificationId = 10L;
      User otherUser = mock(User.class);
      AppNotification mockAppNotification = createMockNotification(otherUser);

      given(otherUser.getUserId()).willReturn(2L);
      given(notificationRepository.findById(notificationId)).willReturn(Optional.of(
          mockAppNotification));

      // when & then
      assertThatThrownBy(() -> service.deleteNotification(userId, notificationId))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);

      then(notificationRepository).should(never()).delete(any());
    }

    @Test
    @DisplayName("존재하지 않는 알림 삭제 - 예외 발생")
    void deleteNotification_NotFound() {
      // given
      given(notificationRepository.findById(999L)).willReturn(Optional.empty());

      // when & then
      assertThatThrownBy(() -> service.deleteNotification(1L, 999L))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.NOTIFICATION_NOT_FOUND);
    }
  }

  @Nested
  @DisplayName("전체 읽음 처리 테스트")
  class MarkAllAsReadTests {

    @Test
    @DisplayName("읽지 않은 알림이 없으면 아무것도 하지 않음")
    void markAllAsRead_NoUnreadNotifications() {
      // given
      Long userId = 1L;
      given(notificationRepository.findByUser_UserIdAndIsReadFalse(userId))
          .willReturn(Collections.emptyList());

      // when
      service.markAllAsRead(userId);

      // then
      then(sseEmittersService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("읽지 않은 알림들을 모두 읽음 처리")
    void markAllAsRead_HasUnreadNotifications() {
      // given
      Long userId = 1L;
      AppNotification appNotification1 = mock(AppNotification.class);
      AppNotification appNotification2 = mock(AppNotification.class);
      List<AppNotification> unreadAppNotifications = Arrays.asList(appNotification1,
          appNotification2);

      given(notificationRepository.findByUser_UserIdAndIsReadFalse(userId))
          .willReturn(unreadAppNotifications);

      // when
      service.markAllAsRead(userId);

      // then
      then(appNotification1).should().markAsRead();
      then(appNotification2).should().markAsRead();
      then(sseEmittersService).should().sendUnreadCountUpdate(userId);
    }
  }

  @Nested
  @DisplayName("이벤트 처리 테스트")
  class EventHandlingTests {

    @Test
    @DisplayName("알림 생성 이벤트 - SSE 전송")
    void sendCreated_Success() {
      // given
      User mockUser = createMockUser();
      AppNotification mockAppNotification = createMockNotification(mockUser);

      // when
      service.sendCreated(mockAppNotification);

      // then
      then(sseEmittersService).should().sendSseNotification(1L, mockAppNotification);
    }

    @Test
    @DisplayName("알림 읽음 이벤트 - 읽지 않은 개수 업데이트")
    void sendRead_Success() {
      // given
      User mockUser = createMockUser();
      AppNotification mockAppNotification = createMockNotification(mockUser);

      // when
      service.sendRead(mockAppNotification);

      // then
      then(sseEmittersService).should().sendUnreadCountUpdate(1L);
    }
  }

  // ================================
  // Helper Methods
  // ================================

  private NotificationCreateRequestDto createValidRequest() {
    return NotificationCreateRequestDto.builder()
        .userId(1L)
        .type(Type.CHAT)
        .args(new String[]{"홍길동"})
        .build();
  }

  private User createMockUser() {
    User user = mock(User.class);
    given(user.getUserId()).willReturn(1L);
    given(user.getFcmToken()).willReturn("test-fcm-token");
    return user;
  }

  private NotificationType createMockNotificationType() {
    NotificationType type = mock(NotificationType.class);
    given(type.getType()).willReturn(Type.CHAT);
    given(type.render(any())).willReturn("홍길동님이 메시지를 보냈습니다.");
    return type;
  }

  private AppNotification createMockNotification(User user) {
    AppNotification appNotification = mock(AppNotification.class);
    given(appNotification.getNotificationId()).willReturn(1L);
    given(appNotification.getUser()).willReturn(user);
    given(appNotification.getContent()).willReturn("홍길동님이 메시지를 보냈습니다.");
    return appNotification;
  }

  private AppNotification setupMocksForSuccessfulCreation() {
    User mockUser = createMockUser();
    NotificationType mockType = createMockNotificationType();
    AppNotification mockAppNotification = createMockNotification(mockUser);

    given(userRepository.findById(1L)).willReturn(Optional.of(mockUser));
    given(notificationTypeRepository.findByType(Type.CHAT)).willReturn(Optional.of(mockType));
    given(notificationRepository.save(any(AppNotification.class))).willReturn(mockAppNotification);

    return mockAppNotification;
  }
}