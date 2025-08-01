package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.requestDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTest {

  @Mock
  private NotificationService notificationService;

  @InjectMocks
  private NotificationController notificationController;

  private MockMvc mockMvc;
  private Long userId;
  private NotificationListResponseDto listResponseDto;
  private NotificationCreateResponseDto createResponseDto;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders
        .standaloneSetup(notificationController)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    userId = 1L;

    createResponseDto = NotificationCreateResponseDto.builder()
        .notificationId(1L)
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
    when(notificationService.createSseConnection(userId))
        .thenReturn(mockEmitter);

    // when & then
    mockMvc.perform(get("/notifications/stream")
            .param("userId", userId.toString())
            .accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isOk());               // Content‐Type 검증은 제거

    verify(notificationService).createSseConnection(userId);
  }


  @Test
  @DisplayName("알림 생성 - 성공")
  void createNotification_Success() throws Exception {
    String jsonContent = """
            {
              "userId": 1,
              "type": "CHAT",
              "args": ["홍길동"]
            }
            """;

    when(notificationService.createNotification(any(NotificationCreateRequestDto.class)))
        .thenReturn(createResponseDto);

    mockMvc.perform(post("/notifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content(jsonContent))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.notificationId").value(1L));

    verify(notificationService).createNotification(any(NotificationCreateRequestDto.class));
  }

  @Test
  @DisplayName("알림 목록 조회 - 첫 페이지 성공")
  void getNotifications_FirstPage_Success() throws Exception {
    when(notificationService.getNotifications(userId, null, 20))
        .thenReturn(listResponseDto);

    mockMvc.perform(get("/notifications")
            .param("userId", userId.toString())
            .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.notifications").isArray())
        .andExpect(jsonPath("$.data.notifications", hasSize(5)))
        .andExpect(jsonPath("$.data.cursor").value(5L))
        .andExpect(jsonPath("$.data.hasMore").value(true))
        .andExpect(jsonPath("$.data.unreadCount").value(3L));

    verify(notificationService).getNotifications(userId, null, 20);
  }

  @Test
  @DisplayName("알림 목록 조회 - 커서 기반 페이지 성공")
  void getNotifications_WithCursor_Success() throws Exception {
    Long cursor = 10L;
    int size = 15;

    when(notificationService.getNotifications(userId, cursor, size))
        .thenReturn(listResponseDto);

    mockMvc.perform(get("/notifications")
            .param("userId", userId.toString())
            .param("cursor", cursor.toString())
            .param("size", String.valueOf(size)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.cursor").value(5L));

    verify(notificationService).getNotifications(userId, cursor, size);
  }

  @Test
  @DisplayName("알림 목록 조회 - 페이지 크기 제한 적용")
  void getNotifications_SizeLimit_Applied() throws Exception {
    int requestSize = 150;
    int expectedSize = 100;

    when(notificationService.getNotifications(userId, null, expectedSize))
        .thenReturn(listResponseDto);

    mockMvc.perform(get("/notifications")
            .param("userId", userId.toString())
            .param("size", String.valueOf(requestSize)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    verify(notificationService).getNotifications(userId, null, expectedSize);
  }

  @Test
  @DisplayName("알림 목록 조회 - 기본 페이지 크기 적용")
  void getNotifications_DefaultSize_Applied() throws Exception {
    int defaultSize = 20;

    when(notificationService.getNotifications(userId, null, defaultSize))
        .thenReturn(listResponseDto);

    mockMvc.perform(get("/notifications")
            .param("userId", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    verify(notificationService).getNotifications(userId, null, defaultSize);
  }

  @Test
  @DisplayName("모든 알림 읽음 처리 - 성공")
  void markAllNotificationsAsRead_Success() throws Exception {
    doNothing().when(notificationService).markAllAsRead(userId);

    mockMvc.perform(patch("/notifications/read-all")
            .param("userId", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    verify(notificationService).markAllAsRead(userId);
  }

  @Test
  @DisplayName("알림 삭제 - 성공")
  void deleteNotification_Success() throws Exception {
    Long notificationId = 123L;
    doNothing().when(notificationService).deleteNotification(userId, notificationId);

    mockMvc.perform(delete("/notifications/{notificationId}", notificationId)
            .param("userId", userId.toString()))
        .andExpect(status().isNoContent());

    verify(notificationService).deleteNotification(userId, notificationId);
  }

  @Test
  @DisplayName("알림 생성 - 유효성 검증 실패 (userId null)")
  void createNotification_ValidationFailed_UserIdNull() throws Exception {
    String invalidJson = """
            {
              "userId": null,
              "type": "CHAT",
              "args": ["홍길동"]
            }
            """;

    mockMvc.perform(post("/notifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalidJson))
        .andExpect(status().isBadRequest());

    verify(notificationService, never()).createNotification(any());
  }

  @Test
  @DisplayName("알림 생성 - 유효성 검증 실패 (type null)")
  void createNotification_ValidationFailed_TypeNull() throws Exception {
    String invalidJson = """
            {
              "userId": 1,
              "type": null,
              "args": ["홍길동"]
            }
            """;

    mockMvc.perform(post("/notifications")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalidJson))
        .andExpect(status().isBadRequest());

    verify(notificationService, never()).createNotification(any());
  }

  @Test
  @DisplayName("SSE 스트림 연결 - 서비스 예외 발생")
  void streamNotifications_ServiceException() throws Exception {
    when(notificationService.createSseConnection(userId))
        .thenThrow(new CustomException(ErrorCode.SSE_CONNECTION_FAILED));

    mockMvc.perform(get("/notifications/stream")
            .param("userId", userId.toString())
            .accept(MediaType.ALL))
        .andExpect(status().isServiceUnavailable())    // 503
        .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(jsonPath("$.success").value(false))
        // 에러 정보는 data 필드 안에 있으므로 data.code, data.message 로 검증
        .andExpect(jsonPath("$.data.code")
            .value(ErrorCode.SSE_CONNECTION_FAILED.name()))
        .andExpect(jsonPath("$.data.message")
            .value(ErrorCode.SSE_CONNECTION_FAILED.getMessage()))
        .andExpect(jsonPath("$.data.validation").isEmpty());

    verify(notificationService).createSseConnection(userId);
  }

  @Test
  @DisplayName("알림 목록 조회 - 서비스 예외 발생")
  void getNotifications_ServiceException() throws Exception {
    when(notificationService.getNotifications(userId, null, 20))
        .thenThrow(new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND));

    mockMvc.perform(get("/notifications")
            .param("userId", userId.toString())
            .param("size", "20")
            .accept(MediaType.ALL))
        .andExpect(status().isNotFound())             // 404
        .andExpect(content().contentType(MediaType.APPLICATION_JSON_VALUE))
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data.code")
            .value(ErrorCode.NOTIFICATION_NOT_FOUND.name()))
        .andExpect(jsonPath("$.data.message")
            .value(ErrorCode.NOTIFICATION_NOT_FOUND.getMessage()))
        .andExpect(jsonPath("$.data.validation").isEmpty());

    verify(notificationService).getNotifications(userId, null, 20);
  }

  @Test
  @DisplayName("모든 알림 읽음 처리 - 서비스 예외 발생")
  void markAllNotificationsAsRead_ServiceException() throws Exception {
    doThrow(new RuntimeException("DB 오류"))
        .when(notificationService).markAllAsRead(userId);

    mockMvc.perform(patch("/notifications/read-all")
            .param("userId", userId.toString()))
        .andExpect(status().is5xxServerError());

    verify(notificationService).markAllAsRead(userId);
  }

  @Test
  @DisplayName("알림 삭제 - 서비스 예외 발생")
  void deleteNotification_ServiceException() throws Exception {
    Long notificationId = 123L;
    doThrow(new RuntimeException("삭제 권한 없음"))
        .when(notificationService).deleteNotification(userId, notificationId);

    mockMvc.perform(delete("/notifications/{notificationId}", notificationId)
            .param("userId", userId.toString()))
        .andExpect(status().is5xxServerError());

    verify(notificationService).deleteNotification(userId, notificationId);
  }

  private List<NotificationItemDto> createMockNotificationDtos() {
    return List.of(
        NotificationItemDto.builder()
            .notificationId(1L)
            .content("새로운 채팅 메시지가 도착했습니다.")
            .type(Type.CHAT)
            .isRead(false)
            .createdAt(LocalDateTime.now().minusHours(1))
            .build(),
        NotificationItemDto.builder()
            .notificationId(2L)
            .content("정산이 완료되었습니다.")
            .type(Type.SETTLEMENT)
            .isRead(true)
            .createdAt(LocalDateTime.now().minusHours(2))
            .build(),
        NotificationItemDto.builder()
            .notificationId(3L)
            .content("게시글이 좋아요를 받았습니다.")
            .type(Type.LIKE)
            .isRead(false)
            .createdAt(LocalDateTime.now().minusHours(3))
            .build(),
        NotificationItemDto.builder()
            .notificationId(4L)
            .content("새로운 댓글이 등록되었습니다.")
            .type(Type.COMMENT)
            .isRead(false)
            .createdAt(LocalDateTime.now().minusHours(4))
            .build(),
        NotificationItemDto.builder()
            .notificationId(5L)
            .content("채팅방에 새로운 참가자가 추가되었습니다.")
            .type(Type.CHAT)
            .isRead(true)
            .createdAt(LocalDateTime.now().minusHours(5))
            .build()
    );
  }
}