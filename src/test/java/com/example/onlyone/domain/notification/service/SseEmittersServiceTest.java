package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.clearInvocations;

@ExtendWith(MockitoExtension.class)
class SseEmittersServiceTest {

  @Mock
  private SseEmitterFactory emitterFactory;

  @Mock
  private NotificationRepository notificationRepository;

  @Mock
  private NotificationType notificationType;

  @InjectMocks
  private SseEmittersService service;

  @Nested
  @DisplayName("createSseConnection() 테스트")
  class CreateConnectionTests {

    @Test
    @DisplayName("factory로 생성된 emitter가 맵에 저장되고 반환된다")
    void createConnection_success() throws Exception {
      // given
      long userId = 42L;
      SseEmitter spyEmitter = spy(new SseEmitter(0L));
      given(emitterFactory.create(anyLong())).willReturn(spyEmitter);

      // when
      SseEmitter returned = service.createSseConnection(userId);

      // then
      assertThat(returned).isSameAs(spyEmitter);
    }

    @Test
    @DisplayName("중복 연결 시 이전 emitter.complete() 호출 후 교체된다")
    void createConnection_replaceExisting() throws Exception {
      // given
      long userId = 99L;
      SseEmitter first = spy(new SseEmitter(0L));
      given(emitterFactory.create(anyLong())).willReturn(first);
      service.createSseConnection(userId);

      SseEmitter second = spy(new SseEmitter(0L));
      given(emitterFactory.create(anyLong())).willReturn(second);

      // when
      SseEmitter returned = service.createSseConnection(userId);

      // then
      assertThat(returned).isSameAs(second);
      then(first).should().complete();
    }

    @Test
    @DisplayName("heartbeat 전송 중 IOException 발생 시 CustomException")
    void createConnection_heartbeatFailure() throws IOException {
      // given
      long userId = 123L;
      SseEmitter badEmitter = mock(SseEmitter.class);
      given(emitterFactory.create(anyLong())).willReturn(badEmitter);
      willThrow(new IOException("oops"))
          .given(badEmitter).send(any(SseEmitter.SseEventBuilder.class));

      // then
      assertThatThrownBy(() -> service.createSseConnection(userId))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SSE_CONNECTION_FAILED);
    }
  }

  @Nested
  @DisplayName("sendSseNotification() & sendUnreadCountUpdate()")
  class SendEvents {

    @Test
    @DisplayName("등록된 emitter 가 있으면 notification 이벤트 전송")
    void sendSseNotification_withEmitter() throws Exception {
      // given
      long userId = 5L;
      SseEmitter spyEmitter = spy(new SseEmitter(0L));
      given(emitterFactory.create(anyLong())).willReturn(spyEmitter);

      // 연결 + 초기 heartbeat 리셋
      service.createSseConnection(userId);
      clearInvocations(spyEmitter);

      Notification notification = mock(Notification.class);
      given(notification.getNotificationId()).willReturn(12345L);
      given(notification.getContent()).willReturn("hello");
      given(notification.getNotificationType()).willReturn(notificationType);

      // when
      service.sendSseNotification(userId, notification);

      // then: send() 딱 1번 호출됐는지만 검증
      then(spyEmitter).should(times(1))
          .send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("sendUnreadCountUpdate() 는 count 조회 후 unread_count 이벤트 전송")
    void sendUnreadCountUpdate_withEmitter() throws Exception {
      // given
      long userId = 7L;
      SseEmitter spyEmitter = spy(new SseEmitter(0L));
      given(emitterFactory.create(anyLong())).willReturn(spyEmitter);

      // 연결 + 초기 heartbeat 리셋
      service.createSseConnection(userId);
      clearInvocations(spyEmitter);

      given(notificationRepository.countByUser_UserIdAndIsReadFalse(userId))
          .willReturn(42L);

      // when
      service.sendUnreadCountUpdate(userId);

      // then: send() 딱 1번 호출됐는지만 검증
      then(spyEmitter).should(times(1))
          .send(any(SseEmitter.SseEventBuilder.class));
    }
  }
}