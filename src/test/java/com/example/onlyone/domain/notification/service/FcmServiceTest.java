package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.user.entity.User;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
class FcmServiceTest {

  @Mock
  private FirebaseMessaging firebaseMessaging;
  @InjectMocks
  private FcmService fcmService;

  @Mock
  private User mockUser;
  @Mock
  private NotificationType mockNt;
  @Mock
  private Notification mockNotification;

  @Test
  @DisplayName("sendFcmNotification 성공 시 FirebaseMessaging.send 한 번 호출")
  void sendFcmNotification_success_invokesOnce() throws Exception {
    // given
    Notification notification = mock(Notification.class);

    // 1) user 세팅
    User user = mock(User.class);
    given(notification.getUser()).willReturn(user);
    given(user.getFcmToken()).willReturn("dummy-token");    // 토큰이 없으면 로그만 찍고 끝낼 수도 있으니 반드시 값 셋업

    // 2) notificationType 세팅
    NotificationType nt = mock(NotificationType.class);
    given(notification.getNotificationType()).willReturn(nt);
    given(nt.getType()).willReturn(Type.CHAT);              // enum 또는 문자열 형태

    // (선택) 만약 메시지 payload에 content가 들어간다면 미리 stub
    given(notification.getContent()).willReturn("테스트 메시지");

    // when
    fcmService.sendFcmNotification(notification);

    // then
    then(firebaseMessaging).should(times(1))
        .send(any(com.google.firebase.messaging.Message.class));
  }

  @Test
  void sendFcmNotification_noToken_skipsSend() throws Exception {
    // given
    // 1) notification.getUser() → mockUser
    given(mockNotification.getUser()).willReturn(mockUser);
    // 2) mockUser.getFcmToken() → null (토큰 없는 케이스)
    given(mockUser.getFcmToken()).willReturn(null);

    // when
    fcmService.sendFcmNotification(mockNotification);

    // then
    // 토큰이 없으므로 firebaseMessaging.send()는 절대 호출되지 않아야 한다
    then(firebaseMessaging).should(never()).send(any());
  }
}