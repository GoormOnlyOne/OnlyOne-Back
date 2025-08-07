package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.notification.service.SseEmittersService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

  @Mock
  private NotificationService notificationService;

  @Mock
  private SseEmittersService sseEmittersService;

  @InjectMocks
  private NotificationController notificationController;

  private MockMvc mockMvc;
  private Long userId;
  private NotificationCreateResponseDto createResponseDto;
  private NotificationListResponseDto listResponseDto;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders
        .standaloneSetup(notificationController)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    userId = 1L;

    createResponseDto = NotificationCreateResponseDto.builder()
        .notificationId(1L)
        .content("테스트 알림")
        .fcmSent(false)
        .createdAt(LocalDateTime.now())
        .build();

    listResponseDto = NotificationListResponseDto.builder()
        .notifications(createMockNotificationDtos())
        .cursor(5L)
        .hasMore(true)
        .unreadCount(3L)
        .build();
  }

  @Test
  @DisplayName("SSE 스트림 연결 - 성공")
  void streamNotifications_Success() throws Exception {
    // given
    SseEmitter mockEmitter = new SseEmitter();
    given(sseEmittersService.createSseConnection(userId))
        .willReturn(mockEmitter);

    // when & then
    mockMvc.perform(get("/notifications/stream")
            .param("userId", userId.toString())
            .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isOk());

    then(sseEmittersService).should().createSseConnection(userId);
    then(notificationService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("알림 생성 - 성공")
  void createNotification_Success() throws Exception {
    String json = """
      {
        "userId": 1,
        "type": "CHAT",
        "args": ["홍길동"]
      }
      """;

    given(notificationService.createNotification(any(NotificationCreateRequestDto.class)))
        .willReturn(createResponseDto);

    mockMvc.perform(post("/notifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.notificationId").value(1));

    then(notificationService).should().createNotification(any(NotificationCreateRequestDto.class));
    then(sseEmittersService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("알림 목록 조회 - 첫 페이지 성공")
  void getNotifications_FirstPage_Success() throws Exception {
    given(notificationService.getNotifications(userId, null, 20))
        .willReturn(listResponseDto);

    mockMvc.perform(get("/notifications")
            .param("userId", userId.toString())
            .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.notifications", hasSize(5)))
        .andExpect(jsonPath("$.data.cursor").value(5))
        .andExpect(jsonPath("$.data.hasMore").value(true))
        .andExpect(jsonPath("$.data.unreadCount").value(3));

    then(notificationService).should().getNotifications(userId, null, 20);
    then(sseEmittersService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("모든 알림 읽음 처리 - 성공")
  void markAllNotificationsAsRead_Success() throws Exception {
    willDoNothing().given(notificationService).markAllAsRead(userId);

    mockMvc.perform(patch("/notifications/read-all")
            .param("userId", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    then(notificationService).should().markAllAsRead(userId);
    then(sseEmittersService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("알림 삭제 - 성공")
  void deleteNotification_Success() throws Exception {
    Long nid = 123L;
    willDoNothing().given(notificationService).deleteNotification(userId, nid);

    mockMvc.perform(delete("/notifications/{notificationId}", nid)
            .param("userId", userId.toString()))
        .andExpect(status().isNoContent());

    then(notificationService).should().deleteNotification(userId, nid);
    then(sseEmittersService).shouldHaveNoInteractions();
  }

  @Test
  @DisplayName("SSE 스트림 연결 - 서비스 예외 발생")
  void streamNotifications_ServiceException() throws Exception {
    given(sseEmittersService.createSseConnection(userId))
        .willThrow(new CustomException(ErrorCode.SSE_CONNECTION_FAILED));

    mockMvc.perform(get("/notifications/stream")
            .param("userId", userId.toString())
            .accept(MediaType.ALL))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data.code")
            .value(ErrorCode.SSE_CONNECTION_FAILED.name()))
        .andExpect(jsonPath("$.data.message")
            .value(ErrorCode.SSE_CONNECTION_FAILED.getMessage()));

    then(sseEmittersService).should().createSseConnection(userId);
    then(notificationService).shouldHaveNoInteractions();
  }

  private List<NotificationItemDto> createMockNotificationDtos() {
    return List.of(
        NotificationItemDto.builder()
            .notificationId(1L).content("A").type(Type.CHAT).isRead(false)
            .createdAt(LocalDateTime.now()).build(),
        NotificationItemDto.builder()
            .notificationId(2L).content("B").type(Type.LIKE).isRead(true)
            .createdAt(LocalDateTime.now()).build(),
        NotificationItemDto.builder()
            .notificationId(3L).content("C").type(Type.COMMENT).isRead(false)
            .createdAt(LocalDateTime.now()).build(),
        NotificationItemDto.builder()
            .notificationId(4L).content("D").type(Type.SETTLEMENT).isRead(false)
            .createdAt(LocalDateTime.now()).build(),
        NotificationItemDto.builder()
            .notificationId(5L).content("E").type(Type.CHAT).isRead(true)
            .createdAt(LocalDateTime.now()).build()
    );
  }
}