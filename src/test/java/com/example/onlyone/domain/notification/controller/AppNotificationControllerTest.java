package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
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


import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AppNotificationControllerTest {

  @Mock
  private NotificationService notificationService;

  @Mock
  private UserService userService;

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
  }

  @Test
  @DisplayName("알림 목록 조회 - 첫 페이지 자동 읽음 처리")
  void getNotifications_FirstPage_AutoMarkAsRead() throws Exception {
    given(notificationService.getNotifications(userId, null, 20))
        .willReturn(listResponseDto);

    mockMvc.perform(get("/notifications")
            .param("userId", userId.toString())
            .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.notifications", hasSize(5)))
        .andExpect(jsonPath("$.data.unreadCount").value(3));

    // 첫 페이지 조회 시 자동 읽음 처리 확인
    then(notificationService).should().markAllAsRead(userId);
    then(notificationService).should().getNotifications(userId, null, 20);
  }

  @Test
  @DisplayName("알림 목록 조회 - 커서 기반 페이징 (읽음 처리 없음)")
  void getNotifications_WithCursor_NoAutoRead() throws Exception {
    Long cursor = 10L;
    given(notificationService.getNotifications(userId, cursor, 20))
        .willReturn(listResponseDto);

    mockMvc.perform(get("/notifications")
            .param("userId", userId.toString())
            .param("cursor", cursor.toString())
            .param("size", "20"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    // 커서가 있으므로 자동 읽음 처리 없음
    then(notificationService).should(never()).markAllAsRead(any());
    then(notificationService).should().getNotifications(userId, cursor, 20);
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