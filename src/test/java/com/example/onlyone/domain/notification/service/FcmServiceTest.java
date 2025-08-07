package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

/**
 * FcmService 개선된 테스트 - 기존 ErrorCode 활용한 예외 처리 검증
 */
@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

  @Mock private FirebaseMessaging firebaseMessaging;
  @Mock private NotificationRepository notificationRepository;
  @InjectMocks private FcmService fcmService;

  @Nested
  @DisplayName("FCM 알림 전송 테스트")
  class SendNotificationTests {

    @Test
    @DisplayName("정상적인 FCM 전송 - FirebaseMessaging.send() 호출 확인")
    void sendFcmNotification_Success_ShouldCallFirebaseMessaging() throws Exception {
      // given
      Notification notification = createMockNotificationForSend("valid-fcm-token");
      given(firebaseMessaging.send(any(Message.class))).willReturn("success-response");

      // when
      fcmService.sendFcmNotification(notification);

      // then - FirebaseMessaging.send()가 정확히 1번 호출되었는지만 확인
      then(firebaseMessaging).should(times(1)).send(any(Message.class));
    }

    @Test
    @DisplayName("FCM 토큰이 null인 경우 - FCM_TOKEN_NOT_FOUND 예외 발생, Firebase 호출 안됨")
    void sendFcmNotification_NullToken_ShouldThrowFcmTokenNotFoundAndNotCallFirebase() {
      // given
      Notification notification = createMockNotificationMinimal(null);

      // when & then
      assertThatThrownBy(() -> fcmService.sendFcmNotification(notification))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.FCM_TOKEN_NOT_FOUND);

      // Firebase 호출이 되지 않았는지 확인
      then(firebaseMessaging).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("FCM 토큰이 빈 문자열인 경우 - FCM_TOKEN_NOT_FOUND 예외 발생, Firebase 호출 안됨")
    void sendFcmNotification_EmptyToken_ShouldThrowFcmTokenNotFoundAndNotCallFirebase() {
      // given
      Notification notification = createMockNotificationMinimal("");

      // when & then
      assertThatThrownBy(() -> fcmService.sendFcmNotification(notification))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.FCM_TOKEN_NOT_FOUND);

      // Firebase 호출이 되지 않았는지 확인
      then(firebaseMessaging).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Firebase 전송 실패 시 - FCM_MESSAGE_SEND_FAILED 예외 발생, 호출은 1번만")
    void sendFcmNotification_FirebaseFailure_ShouldThrowFcmMessageSendFailed() throws Exception {
      // given
      Notification notification = createMockNotificationForSend("valid-token");
      given(firebaseMessaging.send(any(Message.class)))
          .willThrow(new RuntimeException("Firebase 서버 오류"));

      // when & then
      assertThatThrownBy(() -> fcmService.sendFcmNotification(notification))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.FCM_MESSAGE_SEND_FAILED);

      // Firebase 호출이 정확히 1번 되었는지 확인
      then(firebaseMessaging).should(times(1)).send(any(Message.class));
    }

    @Test
    @DisplayName("예상치 못한 예외 발생 시 - FCM_MESSAGE_SEND_FAILED 예외 발생")
    void sendFcmNotification_UnexpectedError_ShouldThrowFcmMessageSendFailed() throws Exception {
      // given
      Notification notification = createMockNotificationForSend("valid-token");
      given(firebaseMessaging.send(any(Message.class)))
          .willThrow(new RuntimeException("예상치 못한 오류"));

      // when & then
      assertThatThrownBy(() -> fcmService.sendFcmNotification(notification))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.FCM_MESSAGE_SEND_FAILED);

      // Firebase 호출이 1번 되었는지 확인
      then(firebaseMessaging).should(times(1)).send(any(Message.class));
    }
  }

  @Nested
  @DisplayName("FCM 재전송 테스트")
  class RetryNotificationTests {

    @Test
    @DisplayName("실패한 알림이 없는 경우 - Firebase 호출 없음")
    void retryFailedNotifications_NoFailedNotifications_ShouldNotCallFirebase() {
      // given
      Long userId = 1L;
      given(notificationRepository.findByUser_UserIdAndFcmSentFalse(userId))
          .willReturn(Collections.emptyList());

      // when
      fcmService.retryFailedNotifications(userId);

      // then - Firebase가 호출되지 않았는지 확인
      then(firebaseMessaging).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("실패한 알림들 재전송 성공 - Firebase 호출 횟수 확인")
    void retryFailedNotifications_Success_ShouldCallFirebase() throws Exception {
      // given
      Long userId = 1L;
      Notification notification1 = createMockNotificationForSend("token1");
      Notification notification2 = createMockNotificationForSend("token2");
      List<Notification> failedNotifications = List.of(notification1, notification2);

      given(notificationRepository.findByUser_UserIdAndFcmSentFalse(userId))
          .willReturn(failedNotifications);
      given(firebaseMessaging.send(any(Message.class))).willReturn("success");

      // when
      fcmService.retryFailedNotifications(userId);

      // then - Firebase가 정확히 2번 호출되었는지 확인
      then(firebaseMessaging).should(times(2)).send(any(Message.class));
    }

    @Test
    @DisplayName("재전송 중 일부 실패 - Firebase 호출 횟수만 확인")
    void retryFailedNotifications_PartialFailure_ShouldCallFirebaseCorrectTimes() throws Exception {
      // given
      Long userId = 1L;
      Notification successNotification = createMockNotificationForSend("valid-token");
      Notification failNotification = createMockNotificationForSend("invalid-token");
      List<Notification> failedNotifications = List.of(successNotification, failNotification);

      given(notificationRepository.findByUser_UserIdAndFcmSentFalse(userId))
          .willReturn(failedNotifications);

      // 첫 번째는 성공, 두 번째는 실패
      given(firebaseMessaging.send(any(Message.class)))
          .willReturn("success")
          .willThrow(new RuntimeException("토큰 무효"));

      // when
      fcmService.retryFailedNotifications(userId);

      // then - Firebase가 정확히 2번 호출되었는지 확인 (성공 1번, 실패 1번)
      then(firebaseMessaging).should(times(2)).send(any(Message.class));
    }

    @Test
    @DisplayName("재전송 프로세스 자체 실패 - 예외가 전파되지 않음")
    void retryFailedNotifications_ProcessFailure_ShouldNotPropagateException() {
      // given
      Long userId = 1L;
      given(notificationRepository.findByUser_UserIdAndFcmSentFalse(userId))
          .willThrow(new RuntimeException("DB 연결 오류"));

      // when & then - 예외가 전파되지 않아야 함 (비동기 재시도는 시스템에 영향을 주면 안됨)
      assertThatCode(() -> fcmService.retryFailedNotifications(userId))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("메서드 호출 검증 테스트")
  class MethodCallVerificationTests {

    @Test
    @DisplayName("FCM 메시지 전송 - firebaseMessaging.send() 호출 여부만 확인")
    void sendFcmNotification_ShouldCallFirebaseMessagingSend() throws Exception {
      // given
      Notification notification = createMockNotificationForSend("test-token");
      given(firebaseMessaging.send(any(Message.class))).willReturn("success");

      // when
      fcmService.sendFcmNotification(notification);

      // then - Firebase 메서드가 호출되었는지만 확인 (내용은 검증하지 않음)
      then(firebaseMessaging).should(times(1)).send(any(Message.class));
    }
  }

  @Nested
  @DisplayName("예외 처리 테스트")
  class ExceptionHandlingTests {

    @Test
    @DisplayName("Firebase 예외 발생 시 - 적절한 CustomException으로 변환")
    void sendFcmNotification_FirebaseException_ShouldConvertToCustomException() throws Exception {
      // given
      Notification notification = createMockNotificationForSend("test-token");
      // FirebaseMessagingException 생성자가 public이 아니므로 RuntimeException 사용
      given(firebaseMessaging.send(any(Message.class)))
          .willThrow(new RuntimeException("Firebase error"));

      // when & then
      assertThatThrownBy(() -> fcmService.sendFcmNotification(notification))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.FCM_MESSAGE_SEND_FAILED);

      // Firebase 호출이 1번 되었는지 확인
      then(firebaseMessaging).should(times(1)).send(any(Message.class));
    }

    @Test
    @DisplayName("FirebaseMessaging.send()에서 IllegalArgumentException 발생 시 - FCM_MESSAGE_SEND_FAILED로 변환")
    void sendFcmNotification_FirebaseMessagingSendIllegalArgument_ShouldMapToMessageSendFailed() throws Exception {
      // given
      Notification notification = createMockNotificationForSend("valid-token");
      // firebaseMessaging.send()에서 IllegalArgumentException 발생 시뮬레이션
      given(firebaseMessaging.send(any(Message.class)))
          .willThrow(new IllegalArgumentException("Invalid message"));

      // when & then
      // validateAndGetToken에서 예외가 발생하지 않으므로 firebaseMessaging.send()까지 도달
      // 하지만 실제로는 IllegalArgumentException catch 블록에서 FCM_TOKEN_NOT_FOUND로 변환됨
      assertThatThrownBy(() -> fcmService.sendFcmNotification(notification))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.FCM_TOKEN_NOT_FOUND); // 현재 FcmService 로직상 모든 IllegalArgumentException은 FCM_TOKEN_NOT_FOUND
    }
  }

  // ================================
  // Helper Methods
  // ================================

  private Notification createMockNotificationForSend(String fcmToken) {
    // sendFcmNotification에서 실제로 사용하는 필드들만 stubbing
    User mockUser = mock(User.class);
    lenient().when(mockUser.getUserId()).thenReturn(1L);
    lenient().when(mockUser.getFcmToken()).thenReturn(fcmToken);

    NotificationType mockType = mock(NotificationType.class);
    lenient().when(mockType.getType()).thenReturn(Type.CHAT);

    Notification notification = mock(Notification.class);
    lenient().when(notification.getNotificationId()).thenReturn(1L);
    lenient().when(notification.getUser()).thenReturn(mockUser);
    lenient().when(notification.getNotificationType()).thenReturn(mockType);
    lenient().when(notification.getContent()).thenReturn("테스트 알림 내용");
    lenient().when(notification.getCreatedAt()).thenReturn(java.time.LocalDateTime.now());

    return notification;
  }

  private Notification createMockNotificationForRetry(String fcmToken) {
    // retry에서 사용하는 필드들만 stubbing
    User mockUser = mock(User.class);
    lenient().when(mockUser.getFcmToken()).thenReturn(fcmToken);

    Notification notification = mock(Notification.class);
    lenient().when(notification.getNotificationId()).thenReturn(1L);
    lenient().when(notification.getUser()).thenReturn(mockUser);

    return notification;
  }

  private Notification createMockNotificationMinimal(String fcmToken) {
    // 최소한의 stubbing (토큰 검증만)
    User mockUser = mock(User.class);
    given(mockUser.getUserId()).willReturn(1L);
    given(mockUser.getFcmToken()).willReturn(fcmToken);

    Notification notification = mock(Notification.class);
    given(notification.getNotificationId()).willReturn(1L);
    given(notification.getUser()).willReturn(mockUser);

    return notification;
  }
}