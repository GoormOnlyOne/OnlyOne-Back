package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.AppNotification;
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
 * FcmService 간단한 테스트 - 예외 처리만 검증
 */
@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

  @Mock private FirebaseMessaging firebaseMessaging;
  @Mock private NotificationRepository notificationRepository;
  @InjectMocks private FcmService fcmService;

  @Nested
  @DisplayName("FCM 알림 전송 테스트")
  class SendAppNotificationTests {

    @Test
    @DisplayName("정상적인 FCM 전송 - FirebaseMessaging.send() 호출 확인")
    void sendFcmNotification_Success_ShouldCallFirebaseMessaging() throws Exception {
      // given - 유효한 FCM 토큰 형식 사용 (152자)
      String validToken = "d1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890:APA91bF123456789012345";
      AppNotification appNotification = createMockNotificationForSend(validToken);
      given(firebaseMessaging.send(any(Message.class))).willReturn("success-response");

      // when
      fcmService.sendFcmNotification(appNotification);

      // then - FirebaseMessaging.send()가 정확히 1번 호출되었는지만 확인
      then(firebaseMessaging).should(times(1)).send(any(Message.class));
    }

    @Test
    @DisplayName("FCM 토큰이 null인 경우 - FCM_TOKEN_NOT_FOUND 예외 발생, Firebase 호출 안됨")
    void sendFcmNotification_NullToken_ShouldThrowFcmTokenNotFoundAndNotCallFirebase() {
      // given
      AppNotification appNotification = createMockNotificationMinimal(null);

      // when & then
      assertThatThrownBy(() -> fcmService.sendFcmNotification(appNotification))
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
      AppNotification appNotification = createMockNotificationMinimal("");

      // when & then
      assertThatThrownBy(() -> fcmService.sendFcmNotification(appNotification))
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
      String validToken = "d1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890:APA91bF123456789012345";
      AppNotification appNotification = createMockNotificationForSend(validToken);
      given(firebaseMessaging.send(any(Message.class)))
          .willThrow(new RuntimeException("Firebase 서버 오류"));

      // when & then
      assertThatThrownBy(() -> fcmService.sendFcmNotification(appNotification))
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
      String validToken = "d1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890:APA91bF123456789012345";
      AppNotification appNotification = createMockNotificationForSend(validToken);
      given(firebaseMessaging.send(any(Message.class)))
          .willThrow(new RuntimeException("예상치 못한 오류"));

      // when & then
      assertThatThrownBy(() -> fcmService.sendFcmNotification(appNotification))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.FCM_MESSAGE_SEND_FAILED);

      // Firebase 호출이 1번 되었는지 확인
      then(firebaseMessaging).should(times(1)).send(any(Message.class));
    }
  }

  @Nested
  @DisplayName("FCM 재전송 테스트")
  class RetryAppNotificationTests {

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
      String validToken1 = "d1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890:APA91bF123456789012345";
      String validToken2 = "e2345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901:APA91bF234567890123456";
      AppNotification appNotification1 = createMockNotificationForSend(validToken1);
      AppNotification appNotification2 = createMockNotificationForSend(validToken2);
      List<AppNotification> failedAppNotifications = List.of(appNotification1, appNotification2);

      given(notificationRepository.findByUser_UserIdAndFcmSentFalse(userId))
          .willReturn(failedAppNotifications);
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
      String validToken = "d1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890:APA91bF123456789012345";
      String invalidToken = "short"; // 의도적으로 짧은 토큰
      AppNotification successAppNotification = createMockNotificationForSend(validToken);
      AppNotification failAppNotification = createMockNotificationForSend(invalidToken);
      List<AppNotification> failedAppNotifications = List.of(successAppNotification, failAppNotification);

      given(notificationRepository.findByUser_UserIdAndFcmSentFalse(userId))
          .willReturn(failedAppNotifications);

      // 첫 번째는 성공, 두 번째는 토큰 검증 실패로 Firebase 호출 안됨
      given(firebaseMessaging.send(any(Message.class))).willReturn("success");

      // when
      fcmService.retryFailedNotifications(userId);

      // then - Firebase가 정확히 1번만 호출되었는지 확인 (성공한 것만)
      then(firebaseMessaging).should(times(1)).send(any(Message.class));
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
      String validToken = "d1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890:APA91bF123456789012345";
      AppNotification appNotification = createMockNotificationForSend(validToken);
      given(firebaseMessaging.send(any(Message.class))).willReturn("success");

      // when
      fcmService.sendFcmNotification(appNotification);

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
      // given - 유효한 FCM 토큰 사용 (검증 통과)
      String validToken = "d1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890:APA91bF123456789012345";
      AppNotification appNotification = createMockNotificationForSend(validToken);
      // FirebaseMessagingException 생성자가 public이 아니므로 RuntimeException 사용
      given(firebaseMessaging.send(any(Message.class)))
          .willThrow(new RuntimeException("Firebase error"));

      // when & then
      assertThatThrownBy(() -> fcmService.sendFcmNotification(appNotification))
          .isInstanceOf(CustomException.class)
          .extracting("errorCode")
          .isEqualTo(ErrorCode.FCM_MESSAGE_SEND_FAILED);

      // Firebase 호출이 1번 되었는지 확인
      then(firebaseMessaging).should(times(1)).send(any(Message.class));
    }

  }

  // ================================
  // Helper Methods
  // ================================

  private AppNotification createMockNotificationForSend(String fcmToken) {
    // sendFcmNotification에서 실제로 사용하는 필드들만 stubbing
    User mockUser = mock(User.class);
    lenient().when(mockUser.getUserId()).thenReturn(1L);
    lenient().when(mockUser.getFcmToken()).thenReturn(fcmToken);

    NotificationType mockType = mock(NotificationType.class);
    lenient().when(mockType.getType()).thenReturn(Type.CHAT);

    AppNotification appNotification = mock(AppNotification.class);
    lenient().when(appNotification.getNotificationId()).thenReturn(1L);
    lenient().when(appNotification.getUser()).thenReturn(mockUser);
    lenient().when(appNotification.getNotificationType()).thenReturn(mockType);
    lenient().when(appNotification.getContent()).thenReturn("테스트 알림 내용");
    lenient().when(appNotification.getCreatedAt()).thenReturn(java.time.LocalDateTime.now());

    return appNotification;
  }

  private AppNotification createMockNotificationForRetry(String fcmToken) {
    // retry에서 사용하는 필드들만 stubbing
    User mockUser = mock(User.class);
    lenient().when(mockUser.getFcmToken()).thenReturn(fcmToken);

    AppNotification appNotification = mock(AppNotification.class);
    lenient().when(appNotification.getNotificationId()).thenReturn(1L);
    lenient().when(appNotification.getUser()).thenReturn(mockUser);

    return appNotification;
  }

  private AppNotification createMockNotificationMinimal(String fcmToken) {
    // 최소한의 stubbing (토큰 검증만)
    User mockUser = mock(User.class);
    given(mockUser.getUserId()).willReturn(1L);
    given(mockUser.getFcmToken()).willReturn(fcmToken);

    AppNotification appNotification = mock(AppNotification.class);
    given(appNotification.getNotificationId()).willReturn(1L);
    given(appNotification.getUser()).willReturn(mockUser);

    return appNotification;
  }
}