package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.entity.AppNotification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;

/**
 * SseEmittersService 개선된 테스트 - 실제 구현에 맞춘 테스트
 */
@ExtendWith(MockitoExtension.class)
class SseEmittersServiceTest {

  @Mock private NotificationRepository notificationRepository;
  @InjectMocks private SseEmittersService service;

  @BeforeEach
  void setUp() {
    // SSE 타임아웃 설정
    ReflectionTestUtils.setField(service, "sseTimeoutMillis", 30000L);
  }

  @Nested
  @DisplayName("SSE 연결 생성 테스트")
  class CreateConnectionTests {

    @Test
    @DisplayName("새로운 SSE 연결 생성 성공")
    void createConnection_Success() {
      // given
      Long userId = 1L;

      // when
      SseEmitter result = service.createSseConnection(userId);

      // then
      assertThat(result).isNotNull();
      assertThat(result.getTimeout()).isEqualTo(30000L);
    }

    @Test
    @DisplayName("Last-Event-ID와 함께 연결 생성 - 놓친 메시지 전송")
    void createConnection_WithLastEventId_ShouldSendMissedNotifications() {
      // given
      Long userId = 1L;
      String lastEventId = "notification_100_2025-01-15T10:00:00";
      LocalDateTime lastEventTime = LocalDateTime.of(2025, 1, 15, 10, 0, 0);
      
      AppNotification missedNotification = createMockNotification(userId);
      given(notificationRepository.findByUser_UserIdAndCreatedAtAfterOrderByCreatedAtAsc(userId, lastEventTime))
          .willReturn(List.of(missedNotification));

      // when
      SseEmitter result = service.createSseConnection(userId, lastEventId);

      // then
      assertThat(result).isNotNull();
      then(notificationRepository).should().findByUser_UserIdAndCreatedAtAfterOrderByCreatedAtAsc(userId, lastEventTime);
    }

    @Test
    @DisplayName("잘못된 Last-Event-ID 형식 - 놓친 메시지 전송 없이 연결만 생성")
    void createConnection_InvalidLastEventId_ShouldSkipMissedNotifications() {
      // given
      Long userId = 1L;
      String invalidLastEventId = "invalid-format";

      // when
      SseEmitter result = service.createSseConnection(userId, invalidLastEventId);

      // then
      assertThat(result).isNotNull();
      then(notificationRepository).shouldHaveNoInteractions();
    }
  }

  @Nested
  @DisplayName("SSE 알림 전송 테스트")
  class SendNotificationTests {

    @Test
    @DisplayName("연결된 사용자에게 알림 전송 성공")
    void sendNotification_ConnectedUser_Success() {
      // given
      Long userId = 1L;
      AppNotification mockNotification = createMockNotification(userId);
      
      // 먼저 연결 생성
      service.createSseConnection(userId);

      // when
      assertThatCode(() -> service.sendSseNotification(userId, mockNotification))
          .doesNotThrowAnyException();

      // then - 예외가 발생하지 않으면 성공
    }

    @Test
    @DisplayName("연결되지 않은 사용자에게 알림 전송 - 조용히 무시")
    void sendNotification_NoConnection_ShouldIgnore() {
      // given
      Long userId = 1L;
      AppNotification mockNotification = createMockNotification(userId);

      // when & then - 예외 발생하지 않아야 함
      assertThatCode(() -> service.sendSseNotification(userId, mockNotification))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("읽지 않은 개수 업데이트 테스트")
  class UnreadCountUpdateTests {

    @Test
    @DisplayName("연결된 사용자 읽지 않은 개수 업데이트 성공")
    void sendUnreadCountUpdate_ConnectedUser_Success() {
      // given
      Long userId = 1L;
      Long unreadCount = 5L;
      given(notificationRepository.countByUser_UserIdAndIsReadFalse(userId)).willReturn(unreadCount);
      
      // 먼저 연결 생성
      service.createSseConnection(userId);

      // when
      assertThatCode(() -> service.sendUnreadCountUpdate(userId))
          .doesNotThrowAnyException();

      // then
      then(notificationRepository).should().countByUser_UserIdAndIsReadFalse(userId);
    }

    @Test
    @DisplayName("연결되지 않은 사용자 개수 업데이트 - 무시")
    void sendUnreadCountUpdate_NoConnection_ShouldIgnore() {
      // given
      Long userId = 1L;

      // when & then
      assertThatCode(() -> service.sendUnreadCountUpdate(userId))
          .doesNotThrowAnyException();
      
      // 연결이 없으므로 레포지토리 호출 없음
      then(notificationRepository).shouldHaveNoInteractions();
    }
  }

  @Nested
  @DisplayName("Event ID 파싱 테스트")
  class EventIdParsingTests {

    @Test
    @DisplayName("유효한 Event ID 파싱 성공")
    void parseEventId_ValidFormat_Success() {
      // given
      Long userId = 1L;
      String validEventId = "notification_123_2025-01-15T10:30:00";
      LocalDateTime expectedTime = LocalDateTime.of(2025, 1, 15, 10, 30, 0);
      
      AppNotification mockNotification = createMockNotification(userId);
      given(notificationRepository.findByUser_UserIdAndCreatedAtAfterOrderByCreatedAtAsc(eq(userId), any(LocalDateTime.class)))
          .willReturn(List.of(mockNotification));

      // when
      service.createSseConnection(userId, validEventId);

      // then - 시간 파싱이 성공하여 놓친 메시지 조회가 실행됨
      then(notificationRepository).should().findByUser_UserIdAndCreatedAtAfterOrderByCreatedAtAsc(eq(userId), any(LocalDateTime.class));
    }
  }

  // ================================
  // Helper Methods
  // ================================

  private AppNotification createMockNotification(Long userId) {
    User mockUser = mock(User.class);
    given(mockUser.getUserId()).willReturn(userId);

    NotificationType mockType = mock(NotificationType.class);
    given(mockType.getType()).willReturn(Type.CHAT);

    AppNotification appNotification = mock(AppNotification.class);
    given(appNotification.getNotificationId()).willReturn(1L);
    given(appNotification.getUser()).willReturn(mockUser);
    given(appNotification.getNotificationType()).willReturn(mockType);
    given(appNotification.getContent()).willReturn("테스트 알림");
    given(appNotification.getCreatedAt()).willReturn(LocalDateTime.now());

    return appNotification;
  }
}