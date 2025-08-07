package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

/**
 * SseEmittersService 개선된 테스트
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SseEmittersServiceTest {

  @Mock private SseEmitterFactory emitterFactory;
  @Mock private NotificationRepository notificationRepository;
  @InjectMocks private SseEmittersService service;

  @Nested
  @DisplayName("SSE 연결 생성 테스트")
  class CreateConnectionTests {

    @Test
    @DisplayName("새로운 연결 생성 성공")
    void createConnection_Success() throws Exception {
      // given
      Long userId = 1L;
      SseEmitter mockEmitter = spy(new SseEmitter(0L));
      given(emitterFactory.create(0L)).willReturn(mockEmitter);

      // when
      SseEmitter result = service.createSseConnection(userId);

      // then
      assertThat(result).isSameAs(mockEmitter);
      verify(mockEmitter).send(any(SseEmitter.SseEventBuilder.class)); // 하트비트 전송 확인
    }

    @Test
    @DisplayName("중복 연결 시 기존 연결 정리 후 새 연결 생성")
    void createConnection_ReplaceExisting() throws Exception {
      // given
      Long userId = 1L;
      SseEmitter firstEmitter = spy(new SseEmitter(0L));
      SseEmitter secondEmitter = spy(new SseEmitter(0L));

      given(emitterFactory.create(0L))
          .willReturn(firstEmitter)
          .willReturn(secondEmitter);

      // when
      service.createSseConnection(userId); // 첫 번째 연결
      SseEmitter result = service.createSseConnection(userId); // 두 번째 연결

      // then
      assertThat(result).isSameAs(secondEmitter);
      verify(firstEmitter).complete(); // 첫 번째 연결이 정리되었는지 확인
    }

    @Test
    @DisplayName("하트비트 전송 실패 시 예외 발생")
    void createConnection_HeartbeatFailure() throws Exception {
      // given
      Long userId = 1L;
      SseEmitter mockEmitter = mock(SseEmitter.class);
      given(emitterFactory.create(0L)).willReturn(mockEmitter);

      doThrow(new IOException("하트비트 전송 실패"))
          .when(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));

      // when & then
      assertThatThrownBy(() -> service.createSseConnection(userId))
          .isInstanceOf(CustomException.class)
          .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SSE_CONNECTION_FAILED);
    }
  }

  @Nested
  @DisplayName("SSE 알림 전송 테스트")
  class SendAppNotificationTests {

    @Test
    @DisplayName("연결된 사용자에게 알림 전송 성공")
    void sendNotification_Success() throws Exception {
      // given
      Long userId = 1L;
      SseEmitter mockEmitter = spy(new SseEmitter(0L));
      AppNotification mockAppNotification = createMockNotification();

      given(emitterFactory.create(0L)).willReturn(mockEmitter);
      service.createSseConnection(userId); // 연결 생성

      // when
      service.sendSseNotification(userId, mockAppNotification);

      // then
      // 하트비트(1회) + 알림(1회) = 총 2회 호출
      verify(mockEmitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("연결되지 않은 사용자에게 알림 전송 시 무시")
    void sendNotification_NoConnection() {
      // given
      Long userId = 1L;
      AppNotification mockAppNotification = createMockNotification();

      // when
      service.sendSseNotification(userId, mockAppNotification);

      // then
      // 로그만 남기고 예외 발생하지 않음
      verifyNoInteractions(emitterFactory);
    }

    @Test
    @DisplayName("전송 실패 시 연결 정리")
    void sendNotification_SendFailure() throws Exception {
      // given
      Long userId = 1L;
      SseEmitter mockEmitter = mock(SseEmitter.class);
      AppNotification mockAppNotification = createMockNotification();

      given(emitterFactory.create(0L)).willReturn(mockEmitter);

      // 하트비트는 성공, 알림 전송은 실패하도록 설정
      doNothing().doThrow(new IOException("전송 실패"))
          .when(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));

      service.createSseConnection(userId);

      // when
      service.sendSseNotification(userId, mockAppNotification);

      // then
      // 연결이 정리되었는지 확인 (다음 전송 시도에서 연결 없음)
      service.sendSseNotification(userId, mockAppNotification); // 두 번째 시도
      verify(mockEmitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }
  }

  @Nested
  @DisplayName("읽지 않은 개수 업데이트 테스트")
  class UnreadCountUpdateTests {

    @Test
    @DisplayName("읽지 않은 개수 업데이트 전송 성공")
    void sendUnreadCountUpdate_Success() throws Exception {
      // given
      Long userId = 1L;
      Long unreadCount = 5L;
      SseEmitter mockEmitter = spy(new SseEmitter(0L));

      given(emitterFactory.create(0L)).willReturn(mockEmitter);
      given(notificationRepository.countByUser_UserIdAndIsReadFalse(userId)).willReturn(unreadCount);

      service.createSseConnection(userId);

      // when
      service.sendUnreadCountUpdate(userId);

      // then
      // 하트비트(1회) + 개수 업데이트(1회) = 총 2회 호출
      verify(mockEmitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
      then(notificationRepository).should().countByUser_UserIdAndIsReadFalse(userId);
    }

    @Test
    @DisplayName("연결되지 않은 사용자 개수 업데이트 시 무시")
    void sendUnreadCountUpdate_NoConnection() {
      // given
      Long userId = 1L;

      // when
      service.sendUnreadCountUpdate(userId);

      // then
      verifyNoInteractions(notificationRepository);
    }
  }

  @Nested
  @DisplayName("연결 생명주기 테스트")
  class ConnectionLifecycleTests {

    @Test
    @DisplayName("연결 완료 콜백 등록 확인")
    void connectionCallbacks_Registration() throws Exception {
      // given
      Long userId = 1L;
      SseEmitter mockEmitter = spy(new SseEmitter(0L));
      given(emitterFactory.create(0L)).willReturn(mockEmitter);

      // when
      service.createSseConnection(userId);

      // then
      verify(mockEmitter).onCompletion(any(Runnable.class));
      verify(mockEmitter).onTimeout(any(Runnable.class));
      verify(mockEmitter).onError(any());
    }
  }

  // ================================
  // Helper Methods
  // ================================

  private AppNotification createMockNotification() {
    AppNotification appNotification = mock(AppNotification.class);
    NotificationType notificationType = mock(NotificationType.class);

    given(appNotification.getNotificationId()).willReturn(1L);
    given(appNotification.getContent()).willReturn("테스트 알림");
    given(appNotification.getNotificationType()).willReturn(notificationType);

    return appNotification;
  }
}