package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.example.onlyone.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 알림 컨트롤러 테스트
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("알림 컨트롤러 테스트")
class NotificationControllerTest {

    @Mock
    private NotificationService notificationService;
    @Mock
    private UserService userService;
    @InjectMocks
    private NotificationController notificationController;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        // given - MockMvc 설정
        mockMvc = MockMvcBuilders
            .standaloneSetup(notificationController)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
        
        // given - 테스트 데이터 설정
        testUser = createTestUser(1L, "테스트유저");
    }

    @Nested
    @DisplayName("읽지 않은 개수 조회 API")
    class GetUnreadCountApiTest {

        @Test
        @DisplayName("읽지 않은 개수를 정확히 반환한다")
        void 읽지_않은_개수를_정확히_반환한다() throws Exception {
            // given
            Long unreadCount = 7L;
            given(userService.getCurrentUser()).willReturn(testUser);
            given(notificationService.getUnreadCount(testUser.getUserId())).willReturn(unreadCount);

            // when & then
            mockMvc.perform(get("/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(7L));

            then(notificationService).should().getUnreadCount(testUser.getUserId());
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리 API")
    class MarkAsReadApiTest {

        @Test
        @DisplayName("특정 알림을 읽음 처리한다")
        void 특정_알림을_읽음_처리한다() throws Exception {
            // given
            Long notificationId = 123L;
            given(userService.getCurrentUser()).willReturn(testUser);
            willDoNothing().given(notificationService).markAsRead(notificationId, testUser.getUserId());

            // when & then
            mockMvc.perform(put("/notifications/{notificationId}/read", notificationId))
                .andExpect(status().isOk());

            then(notificationService).should().markAsRead(notificationId, testUser.getUserId());
        }

        @Test
        @DisplayName("존재하지 않는 알림 읽음 처리 시 404 에러를 반환한다")
        void 존재하지_않는_알림_읽음_처리_시_404_에러를_반환한다() throws Exception {
            // given
            Long notificationId = 999L;
            given(userService.getCurrentUser()).willReturn(testUser);
            willThrow(new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .given(notificationService).markAsRead(notificationId, testUser.getUserId());

            // when & then
            mockMvc.perform(put("/notifications/{notificationId}/read", notificationId))
                .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("모든 알림 읽음 처리 API")
    class MarkAllAsReadApiTest {

        @Test
        @DisplayName("모든 알림을 읽음 처리한다")
        void 모든_알림을_읽음_처리한다() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            willDoNothing().given(notificationService).markAllAsRead(testUser.getUserId());

            // when & then
            mockMvc.perform(put("/notifications/read-all"))
                .andExpect(status().isOk());

            then(notificationService).should().markAllAsRead(testUser.getUserId());
        }
    }

    @Nested
    @DisplayName("알림 삭제 API")
    class DeleteNotificationApiTest {

        @Test
        @DisplayName("알림을 성공적으로 삭제한다")
        void 알림을_성공적으로_삭제한다() throws Exception {
            // given
            Long notificationId = 123L;
            given(userService.getCurrentUser()).willReturn(testUser);
            willDoNothing().given(notificationService).deleteNotification(testUser.getUserId(), notificationId);

            // when & then
            mockMvc.perform(delete("/notifications/{notificationId}", notificationId))
                .andExpect(status().isNoContent());

            then(notificationService).should().deleteNotification(testUser.getUserId(), notificationId);
        }

        @Test
        @DisplayName("존재하지 않는 알림 삭제 시 404 에러를 반환한다")
        void 존재하지_않는_알림_삭제_시_404_에러를_반환한다() throws Exception {
            // given
            Long notificationId = 999L;
            given(userService.getCurrentUser()).willReturn(testUser);
            willThrow(new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .given(notificationService).deleteNotification(testUser.getUserId(), notificationId);

            // when & then
            mockMvc.perform(delete("/notifications/{notificationId}", notificationId))
                .andExpect(status().isNotFound());
        }
    }

    // ================================
    // Helper Methods
    // ================================

    private User createTestUser(Long id, String nickname) {
        return User.builder()
            .userId(id)
            .kakaoId(12345L)
            .nickname(nickname)
            .status(Status.ACTIVE)
            .build();
    }
}