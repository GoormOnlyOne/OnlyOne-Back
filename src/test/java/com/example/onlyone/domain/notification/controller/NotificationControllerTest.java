package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.*;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
    controllers = NotificationController.class,
    excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@Import(GlobalExceptionHandler.class)
class NotificationControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockBean
  private NotificationService notificationService;

  @Test
  @DisplayName("GET /notifications/stream - SSE 연결 성공")
  void streamNotifications_success() throws Exception {
    // given
    SseEmitter emitter = new SseEmitter(30 * 60_000L);
    given(notificationService.createSseConnection(1L))
        .willReturn(emitter);

    // when & then
    MvcResult mvcResult = mockMvc.perform(get("/notifications/stream")
            .header("Authorization", "Bearer test-token"))
        .andExpect(request().asyncStarted())
        .andReturn();

    // SSE 연결 완료
    emitter.complete();

    // asyncDispatch 후 최종 응답 검증
    mockMvc.perform(asyncDispatch(mvcResult))
        .andExpect(status().isOk())
        .andExpect(header().string(
            HttpHeaders.CONTENT_TYPE,
            MediaType.TEXT_EVENT_STREAM_VALUE
        ));

    then(notificationService).should().createSseConnection(1L);
  }

  @Test
  @DisplayName("POST /notifications - 알림 생성 성공 (API 명세서 기반)")
  void createNotification_success() throws Exception {
    String requestJson = """
            {
              "userId": 1,
              "type": "COMMENT",
              "args": ["홍길동"]
            }
            """;

    LocalDateTime now = LocalDateTime.of(2024, 1, 15, 10, 30);
    NotificationCreateResponseDto responseDto = NotificationCreateResponseDto.builder()
        .notificationId(10001L)
        .content("홍길동님이 회원님의 게시글에 댓글을 남겼습니다")
        .fcmSent(true)
        .createdAt(now)
        .build();

    given(notificationService.createNotification(any(NotificationCreateRequestDto.class)))
        .willReturn(responseDto);

    mockMvc.perform(post("/notifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.notificationId").value(10001))
        .andExpect(jsonPath("$.data.content")
            .value("홍길동님이 회원님의 게시글에 댓글을 남겼습니다"))
        .andExpect(jsonPath("$.data.fcmSent").value(true))
        .andExpect(jsonPath("$.data.createdAt").value("2024-01-15T10:30:00"));

    then(notificationService).should()
        .createNotification(any(NotificationCreateRequestDto.class));
  }

  @Test
  @DisplayName("POST /notifications - Validation 실패 (user_id 누락)")
  void createNotification_validationFail() throws Exception {
    String badRequest = """
            {
              "type": "COMMENT",
              "args": ["홍길동"]
            }
            """;

    mockMvc.perform(post("/notifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content(badRequest))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"));

    then(notificationService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("GET /notifications - 알림 목록 조회 성공 (커서 페이징)")
  void getNotifications_success() throws Exception {
    NotificationItemDto notification1 = NotificationItemDto.builder()
        .notificationId(10001L)
        .content("홍길동님이 댓글을 남겼습니다")
        .type(Type.COMMENT)
        .isRead(false)
        .createdAt(LocalDateTime.of(2024, 1, 15, 10, 30))
        .build();

    NotificationItemDto notification2 = NotificationItemDto.builder()
        .notificationId(10002L)
        .content("김철수님이 좋아요를 눌렀습니다")
        .type(Type.LIKE)
        .isRead(true)
        .createdAt(LocalDateTime.of(2024, 1, 15, 9, 0))
        .build();

    NotificationListResponseDto responseDto = NotificationListResponseDto.builder()
        .notifications(Arrays.asList(notification1, notification2))
        .cursor(10002L)
        .hasMore(true)
        .unreadCount(12L)
        .build();

    given(notificationService.getNotifications(eq(1L), isNull(), eq(20)))
        .willReturn(responseDto);

    mockMvc.perform(get("/notifications")
            .header("Authorization", "Bearer test-token")
            .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.notifications").isArray())
        .andExpect(jsonPath("$.data.notifications[0].notificationId").value(10001))
        .andExpect(jsonPath("$.data.notifications[0].content")
            .value("홍길동님이 댓글을 남겼습니다"))
        .andExpect(jsonPath("$.data.notifications[0].type").value("COMMENT"))
        .andExpect(jsonPath("$.data.notifications[0].isRead").value(false))
        .andExpect(jsonPath("$.data.cursor").value(10002))
        .andExpect(jsonPath("$.data.hasMore").value(true))
        .andExpect(jsonPath("$.data.unreadCount").value(12));

    then(notificationService).should()
        .getNotifications(1L, null, 20);
  }

  @Test
  @DisplayName("GET /notifications - 커서 페이징 (두 번째 페이지)")
  void getNotifications_withCursor() throws Exception {
    NotificationListResponseDto responseDto = NotificationListResponseDto.builder()
        .notifications(Arrays.asList())
        .cursor(9999L)
        .hasMore(false)
        .unreadCount(5L)
        .build();

    given(notificationService.getNotifications(eq(1L), eq(10000L), eq(20)))
        .willReturn(responseDto);

    mockMvc.perform(get("/notifications")
            .header("Authorization", "Bearer test-token")
            .param("cursor", "10000")
            .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.cursor").value(9999))
        .andExpect(jsonPath("$.data.hasMore").value(false))
        .andExpect(jsonPath("$.data.unreadCount").value(5));

    then(notificationService).should()
        .getNotifications(1L, 10000L, 20);
  }

  @Test
  @DisplayName("PATCH /notifications/read - 개별 알림 읽음 처리 성공")
  void markNotificationsAsRead_success() throws Exception {
    String requestJson = """
            {
              "notificationIds": [10001, 10002, 10003]
            }
            """;

    NotificationReadResponseDto responseDto = NotificationReadResponseDto.builder()
        .updatedCount(3)
        .notificationIds(Arrays.asList(10001L, 10002L, 10003L))
        .build();

    given(notificationService.markAsRead(eq(1L), any(NotificationReadRequestDto.class)))
        .willReturn(responseDto);

    mockMvc.perform(patch("/notifications/read")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.updatedCount").value(3))
        .andExpect(jsonPath("$.data.notificationIds").isArray())
        .andExpect(jsonPath("$.data.notificationIds[0]").value(10001))
        .andExpect(jsonPath("$.data.notificationIds[1]").value(10002))
        .andExpect(jsonPath("$.data.notificationIds[2]").value(10003));

    then(notificationService).should()
        .markAsRead(eq(1L), any(NotificationReadRequestDto.class));
  }

  @Test
  @DisplayName("PATCH /notifications/read - Validation 실패 (notificationIds 누락)")
  void markNotificationsAsRead_validationFail() throws Exception {
    String badRequest = """
    {
      "some_field": "value"
    }
    """;

    mockMvc.perform(patch("/notifications/read")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(badRequest))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data.code").value("GLOBAL_400_1"));

    then(notificationService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("DELETE /notifications/{notificationId} - 알림 삭제 성공")
  void deleteNotification_success() throws Exception {
    Long notificationId = 10001L;

    mockMvc.perform(delete("/notifications/{notificationId}", notificationId)
            .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    then(notificationService).should()
        .deleteNotification(1L, notificationId);
  }

  @Test
  @DisplayName("DELETE /notifications/{notificationId} - 알림 없음 (404)")
  void deleteNotification_notFound() throws Exception {
    Long notificationId = 99999L;

    willThrow(new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND))
        .given(notificationService)
        .deleteNotification(1L, notificationId);

    mockMvc.perform(delete("/notifications/{notificationId}", notificationId)
            .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data.code")
            .value(ErrorCode.NOTIFICATION_NOT_FOUND.name()));

    then(notificationService).should()
        .deleteNotification(1L, notificationId);
  }
}