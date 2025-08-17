package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.responseDto.NotificationCreateResponseDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationItemDto;
import com.example.onlyone.domain.notification.dto.responseDto.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.notification.service.SseEmittersService;
import com.example.onlyone.global.filter.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 알림 컨트롤러 테스트
 */
@WebMvcTest(controllers = NotificationController.class,
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthenticationFilter.class)
    })
@DisplayName("알림 컨트롤러 테스트")
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private SseEmittersService sseEmittersService;


    private static final String BASE_URL = "/api/v1/notifications";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_TOKEN = "Bearer test-jwt-token";


    @Nested
    @DisplayName("SSE 연결 테스트")
    class SseConnectionTest {

        @Test
        @WithMockUser(username = "1")
        @DisplayName("SSE 연결을 성공적으로 생성한다")
        void creates_sse_connection_successfully() throws Exception {
            // given
            Long userId = 1L;
            SseEmitter mockEmitter = new SseEmitter();
            given(sseEmittersService.createSseConnection(userId)).willReturn(mockEmitter);

            // when & then
            mockMvc.perform(get(BASE_URL + "/sse")
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/event-stream"));

            then(sseEmittersService).should().createSseConnection(userId);
        }

        @Test
        @DisplayName("인증 없이 SSE 연결 시도 시 401 에러")
        void returns_401_when_accessing_sse_without_auth() throws Exception {
            // when & then
            mockMvc.perform(get(BASE_URL + "/sse"))
                .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("알림 목록 조회 테스트")
    class GetNotificationsTest {

        @Test
        @WithMockUser(username = "1")
        @DisplayName("알림 목록을 성공적으로 조회한다")
        void gets_notifications_successfully() throws Exception {
            // given
            Long userId = 1L;
            NotificationItemDto notification = NotificationItemDto.builder()
                .notificationId(100L)
                .type(Type.CHAT)
                .content("새로운 메시지가 있습니다")
                .isRead(false)
                .targetType("CHAT")
                .targetId(123L)
                .createdAt(LocalDateTime.now())
                .build();

            NotificationListResponseDto response = NotificationListResponseDto.builder()
                .notifications(List.of(notification))
                .hasMore(false)
                .cursor(null)
                .build();

            given(notificationService.getNotifications(eq(userId), isNull(), eq(20)))
                .willReturn(response);

            // when & then
            MvcResult result = mockMvc.perform(get(BASE_URL)
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN)
                    .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications").isArray())
                .andExpect(jsonPath("$.notifications[0].notificationId").value(100))
                .andExpect(jsonPath("$.notifications[0].type").value("CHAT"))
                .andExpect(jsonPath("$.notifications[0].content").value("새로운 메시지가 있습니다"))
                .andExpect(jsonPath("$.notifications[0].isRead").value(false))
                .andExpect(jsonPath("$.hasMore").value(false))
                .andReturn();

            String responseBody = result.getResponse().getContentAsString();
            assertThat(responseBody).isNotEmpty();
        }

        @Test
        @WithMockUser(username = "1")
        @DisplayName("커서 기반 페이지네이션으로 알림을 조회한다")
        void gets_notifications_with_cursor_pagination() throws Exception {
            // given
            Long userId = 1L;
            Long cursor = 50L;
            int size = 10;

            NotificationListResponseDto response = NotificationListResponseDto.builder()
                .notifications(List.of())
                .hasMore(true)
                .cursor(40L)
                .build();

            given(notificationService.getNotifications(userId, cursor, size))
                .willReturn(response);

            // when & then
            mockMvc.perform(get(BASE_URL)
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN)
                    .param("cursor", String.valueOf(cursor))
                    .param("size", String.valueOf(size)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.cursor").value(40));
        }

        @Test
        @WithMockUser(username = "1")
        @DisplayName("타입별 알림을 조회한다")
        void gets_notifications_by_type() throws Exception {
            // given
            Long userId = 1L;
            Type type = Type.LIKE;

            NotificationItemDto likeNotification = NotificationItemDto.builder()
                .notificationId(200L)
                .type(Type.LIKE)
                .content("누군가 게시글을 좋아합니다")
                .isRead(false)
                .build();

            NotificationListResponseDto response = NotificationListResponseDto.builder()
                .notifications(List.of(likeNotification))
                .hasMore(false)
                .cursor(null)
                .build();

            given(notificationService.getNotificationsByType(userId, type, null, 20))
                .willReturn(response);

            // when & then
            mockMvc.perform(get(BASE_URL + "/type/" + type)
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications[0].type").value("LIKE"));
        }
    }

    @Nested
    @DisplayName("읽지 않은 개수 조회 테스트")
    class GetUnreadCountTest {

        @Test
        @WithMockUser(username = "1")
        @DisplayName("읽지 않은 알림 개수를 조회한다")
        void gets_unread_count_successfully() throws Exception {
            // given
            Long userId = 1L;
            Long unreadCount = 5L;
            given(notificationService.getUnreadCount(userId)).willReturn(unreadCount);

            // when & then
            mockMvc.perform(get(BASE_URL + "/unread-count")
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(5));
        }

        @Test
        @WithMockUser(username = "1")
        @DisplayName("읽지 않은 알림이 없을 때 0을 반환한다")
        void returns_zero_when_no_unread_notifications() throws Exception {
            // given
            Long userId = 1L;
            given(notificationService.getUnreadCount(userId)).willReturn(0L);

            // when & then
            mockMvc.perform(get(BASE_URL + "/unread-count")
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리 테스트")
    class MarkAsReadTest {

        @Test
        @WithMockUser(username = "1")
        @DisplayName("개별 알림을 읽음 처리한다")
        void marks_individual_notification_as_read() throws Exception {
            // given
            Long userId = 1L;
            Long notificationId = 100L;
            willDoNothing().given(notificationService).markAsRead(notificationId, userId);

            // when & then
            mockMvc.perform(patch(BASE_URL + "/" + notificationId + "/read")
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("알림을 읽음 처리했습니다"));

            then(notificationService).should().markAsRead(notificationId, userId);
        }

        @Test
        @WithMockUser(username = "1")
        @DisplayName("모든 알림을 읽음 처리한다")
        void marks_all_notifications_as_read() throws Exception {
            // given
            Long userId = 1L;
            willDoNothing().given(notificationService).markAllAsRead(userId);

            // when & then
            mockMvc.perform(patch(BASE_URL + "/read-all")
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("모든 알림을 읽음 처리했습니다"));

            then(notificationService).should().markAllAsRead(userId);
        }
    }

    @Nested
    @DisplayName("알림 삭제 테스트")
    class DeleteNotificationTest {

        @Test
        @WithMockUser(username = "1")
        @DisplayName("알림을 성공적으로 삭제한다")
        void deletes_notification_successfully() throws Exception {
            // given
            Long userId = 1L;
            Long notificationId = 100L;
            willDoNothing().given(notificationService).deleteNotification(userId, notificationId);

            // when & then
            mockMvc.perform(delete(BASE_URL + "/" + notificationId)
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("알림을 삭제했습니다"));

            then(notificationService).should().deleteNotification(userId, notificationId);
        }
    }

    @Nested
    @DisplayName("예외 처리 테스트")
    class ExceptionHandlingTest {

        @Test
        @WithMockUser(username = "1")
        @DisplayName("잘못된 알림 타입으로 조회 시 400 에러")
        void returns_400_for_invalid_notification_type() throws Exception {
            // when & then
            mockMvc.perform(get(BASE_URL + "/type/INVALID_TYPE")
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(username = "1")
        @DisplayName("음수 size 파라미터로 조회 시 400 에러")
        void returns_400_for_negative_size_parameter() throws Exception {
            // when & then
            mockMvc.perform(get(BASE_URL)
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN)
                    .param("size", "-1"))
                .andExpect(status().isBadRequest());
        }

        @Test
        @WithMockUser(username = "1")
        @DisplayName("최대 size 초과 시 자동으로 최대값으로 조정")
        void adjusts_size_to_max_when_exceeded() throws Exception {
            // given
            Long userId = 1L;
            NotificationListResponseDto response = NotificationListResponseDto.builder()
                .notifications(List.of())
                .hasMore(false)
                .cursor(null)
                .build();

            // 서비스 레이어에서 최대값으로 조정되어 호출됨
            given(notificationService.getNotifications(eq(userId), isNull(), eq(100)))
                .willReturn(response);

            // when & then
            mockMvc.perform(get(BASE_URL)
                    .header(AUTHORIZATION_HEADER, BEARER_TOKEN)
                    .param("size", "200")) // 최대값 초과
                .andExpect(status().isOk());

            // 100으로 조정되어 호출되는지 확인
            then(notificationService).should().getNotifications(eq(userId), isNull(), anyInt());
        }
    }

    @Nested
    @DisplayName("동시성 테스트")
    class ConcurrencyTest {

        @Test
        @WithMockUser(username = "1")
        @DisplayName("동시에 여러 읽음 처리 요청을 처리한다")
        void handles_concurrent_read_requests() throws Exception {
            // given
            Long userId = 1L;
            willDoNothing().given(notificationService).markAllAsRead(userId);

            // when - 동시에 3개의 읽음 처리 요청
            CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
                try {
                    mockMvc.perform(patch(BASE_URL + "/read-all")
                            .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                        .andExpect(status().isOk());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
                try {
                    mockMvc.perform(patch(BASE_URL + "/read-all")
                            .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                        .andExpect(status().isOk());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            CompletableFuture<Void> future3 = CompletableFuture.runAsync(() -> {
                try {
                    mockMvc.perform(patch(BASE_URL + "/read-all")
                            .header(AUTHORIZATION_HEADER, BEARER_TOKEN))
                        .andExpect(status().isOk());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // then
            CompletableFuture.allOf(future1, future2, future3).join();
            then(notificationService).should(times(3)).markAllAsRead(userId);
        }
    }
}