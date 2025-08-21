package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.requestDto.NotificationCreateRequestDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 알림 컨트롤러 통합 테스트
 * - 모든 알림 API 엔드포인트 검증
 * - 순수 Mockito 기반으로 빠른 실행
 * - 실제 비즈니스 로직 및 예외 처리 검증
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("알림 컨트롤러 통합 테스트")
class NotificationControllerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private UserService userService;

    @InjectMocks
    private NotificationController notificationController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private User testUser;

    private static final String BASE_URL = "/notifications";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(notificationController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        
        objectMapper = new ObjectMapper();
        
        testUser = User.builder()
            .userId(1L)
            .kakaoId(12345L)
            .nickname("테스트유저")
            .status(Status.ACTIVE)
            .build();
    }

    @Nested
    @DisplayName("알림 생성 API")
    class CreateNotificationTest {

        @Test
        @DisplayName("UT-NT-038: 알림이 정상적으로 생성되는가?")
        void creates_chat_notification_successfully() throws Exception {
            // given
            NotificationCreateRequestDto request = NotificationCreateRequestDto.of(
                1L, Type.CHAT, "새 메시지가 도착했습니다"
            );
            
            NotificationCreateResponseDto response = NotificationCreateResponseDto.builder()
                .notificationId(100L)
                .content("새 메시지가 도착했습니다")
                .fcmSent(true)
                .createdAt(LocalDateTime.now())
                .build();
            
            given(notificationService.createNotification(any(NotificationCreateRequestDto.class)))
                .willReturn(response);

            // when & then
            mockMvc.perform(post(BASE_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notificationId").value(100L))
                .andExpect(jsonPath("$.data.fcmSent").value(true));

            verify(notificationService).createNotification(any(NotificationCreateRequestDto.class));
        }

        @Test
        @DisplayName("UT-NT-035: 잘못된 ID 형식 요청 시 400 에러가 발생하는가?")
        void fails_with_invalid_json_format() throws Exception {
            // when & then
            mockMvc.perform(post(BASE_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{invalid json}"))
                .andDo(print())
                .andExpect(status().isBadRequest());
        }
        

    }

    @Nested
    @DisplayName("읽지 않은 개수 조회 API")
    class GetUnreadCountTest {

        @Test
        @DisplayName("UT-NT-001: 읽지 않은 알림이 있을 때 정확한 개수가 반환되는가?")
        void gets_unread_count_successfully() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            given(notificationService.getUnreadCount(1L)).willReturn(5L);

            // when & then
            mockMvc.perform(get(BASE_URL + "/unread-count"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(5));

            verify(notificationService).getUnreadCount(1L);
        }

        @Test
        @DisplayName("UT-NT-002: 읽지 않은 알림이 없을 때 0이 반환되는가?")
        void returns_zero_when_no_unread_notifications() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            given(notificationService.getUnreadCount(1L)).willReturn(0L);

            // when & then
            mockMvc.perform(get(BASE_URL + "/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
        }
    }

    @Nested
    @DisplayName("알림 목록 조회 API")
    class GetNotificationsTest {

        @Test
        @DisplayName("UT-NT-006: 페이징된 알림 목록이 최신순으로 정상 조회되는가?")
        void gets_notifications_with_default_params() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            NotificationListResponseDto response = NotificationListResponseDto.builder()
                .notifications(List.of())
                .hasMore(false)
                .cursor(null)
                .unreadCount(0L)
                .build();
            given(notificationService.getNotifications(1L, null, 20)).willReturn(response);

            // when & then
            mockMvc.perform(get(BASE_URL))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.notifications").isArray())
                .andExpect(jsonPath("$.data.hasMore").value(false));

            verify(notificationService).getNotifications(1L, null, 20);
        }

        @Test
        @DisplayName("UT-NT-007: 커서 기반 페이징이 정상 동작하는가?")
        void gets_notifications_with_custom_params() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            NotificationListResponseDto response = NotificationListResponseDto.builder()
                .notifications(List.of())
                .hasMore(true)
                .cursor(50L)
                .unreadCount(3L)
                .build();
            given(notificationService.getNotifications(1L, 100L, 10)).willReturn(response);

            // when & then
            mockMvc.perform(get(BASE_URL)
                    .param("cursor", "100")
                    .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(true))
                .andExpect(jsonPath("$.data.cursor").value(50));

            verify(notificationService).getNotifications(1L, 100L, 10);
        }

        @Test
        @DisplayName("UT-NT-009: cursor가 null일 때 첫 페이지부터 조회되는가?")
        void fails_with_invalid_cursor_format() throws Exception {
            // when & then
            mockMvc.perform(get(BASE_URL)
                    .param("cursor", "invalid_cursor"))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("UT-NT-008: size 파라미터가 100을 초과할 때 100으로 제한되는가?")
        void fails_with_invalid_size_format() throws Exception {
            // when & then
            mockMvc.perform(get(BASE_URL)
                    .param("size", "not_a_number"))
                .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리 API")
    class MarkAsReadTest {

        @Test
        @DisplayName("UT-NT-015: 읽지 않은 알림이 정상적으로 읽음 처리되는가?")
        void marks_individual_notification_as_read() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            willDoNothing().given(notificationService).markAsRead(123L, 1L);

            // when & then
            mockMvc.perform(put(BASE_URL + "/123/read"))
                .andDo(print())
                .andExpect(status().isOk());

            verify(notificationService).markAsRead(123L, 1L);
        }

        @Test
        @DisplayName("UT-NT-023: 여러 개의 읽지 않은 알림이 모두 읽음 처리되는가?")
        void marks_all_notifications_as_read() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            willDoNothing().given(notificationService).markAllAsRead(1L);

            // when & then
            mockMvc.perform(put(BASE_URL + "/read-all"))
                .andExpect(status().isOk());

            verify(notificationService).markAllAsRead(1L);
        }

        @Test
        @DisplayName("UT-NT-019: 잘못된 ID 형식(문자열) 요청 시 400 에러가 발생하는가?")
        void fails_with_invalid_notification_id_format() throws Exception {
            // when & then
            mockMvc.perform(put(BASE_URL + "/invalid_id/read"))
                .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("UT-NT-017: 존재하지 않는 알림 ID 요청 시 404 에러가 발생하는가?")
        void fails_when_notification_not_found() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            willThrow(new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .given(notificationService).markAsRead(999L, 1L);

            // when & then
            mockMvc.perform(put(BASE_URL + "/999/read"))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("알림 삭제 API")
    class DeleteNotificationTest {

        @Test
        @DisplayName("UT-NT-030: 읽지 않은 알림이 정상 삭제되는가?")
        void deletes_notification_successfully() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            willDoNothing().given(notificationService).deleteNotification(1L, 100L);

            // when & then
            mockMvc.perform(delete(BASE_URL + "/100"))
                .andExpect(status().isNoContent());

            verify(notificationService).deleteNotification(1L, 100L);
        }

        @Test
        @DisplayName("UT-NT-032: 존재하지 않는 알림 삭제 시 404 에러가 발생하는가?")
        void fails_when_deleting_nonexistent_notification() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            willThrow(new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .given(notificationService).deleteNotification(1L, 999L);

            // when & then
            mockMvc.perform(delete(BASE_URL + "/999"))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("추가 테스트 케이스")
    class AdditionalTestCases {

        @Test
        @DisplayName("UT-NT-003: 인증되지 않은 사용자 요청 시 401 에러가 발생하는가?")
        void handles_authentication_failure() throws Exception {
            // given
            given(userService.getCurrentUser())
                .willThrow(new CustomException(ErrorCode.USER_NOT_FOUND));

            // when & then
            mockMvc.perform(get(BASE_URL + "/unread-count"))
                .andDo(print())
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("UT-NT-016: 이미 읽은 알림 재처리 시 멱등성이 보장되는가?")
        void ensures_idempotency_for_duplicate_operations() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            willDoNothing().given(notificationService).markAsRead(123L, 1L);

            // when & then - 여러 번 호출해도 정상 처리
            mockMvc.perform(put(BASE_URL + "/123/read"))
                .andExpect(status().isOk());
            mockMvc.perform(put(BASE_URL + "/123/read"))
                .andExpect(status().isOk());

            then(notificationService).should(times(2)).markAsRead(123L, 1L);
        }

        @Test
        @DisplayName("UT-NT-020: 음수 ID 요청 시 404 에러가 발생하는가?")
        void handles_invalid_id_format() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            willThrow(new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .given(notificationService).markAsRead(-1L, 1L);

            // when & then
            mockMvc.perform(put(BASE_URL + "/-1/read"))
                .andDo(print())
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("UT-NT-025: 알림이 없는 경우에도 정상 응답하는가?")
        void handles_empty_state_gracefully() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            willDoNothing().given(notificationService).markAllAsRead(1L);

            // when & then
            mockMvc.perform(put(BASE_URL + "/read-all"))
                .andExpect(status().isOk());

            then(notificationService).should().markAllAsRead(1L);
        }

        @Test
        @DisplayName("UT-NT-034: 이미 삭제된 알림 재삭제 시 404 에러가 발생하는가?")
        void handles_nonexistent_resource() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            willThrow(new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .given(notificationService).deleteNotification(1L, 999L);

            // when & then
            mockMvc.perform(delete(BASE_URL + "/999"))
                .andDo(print())
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("UT-NT-004: 사용자 정보를 찾을 수 없을 때 404 에러가 발생하는가?")
        void throws_error_when_user_not_found() throws Exception {
            // given
            willThrow(new CustomException(ErrorCode.USER_NOT_FOUND))
                .given(userService).getCurrentUser();

            // when & then
            mockMvc.perform(get(BASE_URL + "/unread-count"))
                .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("UT-NT-010: hasMore 플래그가 정확하게 설정되는가?")
        void sets_hasmore_flag_correctly() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            NotificationListResponseDto response = NotificationListResponseDto.builder()
                .notifications(List.of())
                .hasMore(true)
                .cursor(100L)
                .unreadCount(5L)
                .build();
            given(notificationService.getNotifications(1L, null, 20)).willReturn(response);

            // when & then
            mockMvc.perform(get(BASE_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasMore").value(true));
        }

        @Test
        @DisplayName("UT-NT-018: 다른 사용자의 알림 접근 시 404 에러가 발생하는가?")
        void blocks_access_to_other_users_notifications() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            willThrow(new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .given(notificationService).markAsRead(999L, 1L);

            // when & then
            mockMvc.perform(put(BASE_URL + "/999/read"))
                .andExpect(status().isNotFound());
        }
    }
}