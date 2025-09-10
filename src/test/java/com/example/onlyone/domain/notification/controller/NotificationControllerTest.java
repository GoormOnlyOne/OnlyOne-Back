package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.dto.response.NotificationListResponseDto;
import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.NotificationType;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.repository.NotificationRepository;
import com.example.onlyone.domain.notification.repository.NotificationTypeRepository;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 컨트롤러 통합 테스트
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@WithMockUser
@DisplayName("알림 컨트롤러 테스트")
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private NotificationService notificationService;
    
    @MockitoBean
    private UserService userService;
    
    @Autowired
    private ObjectMapper objectMapper;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private NotificationRepository notificationRepository;
    
    @Autowired
    private NotificationTypeRepository notificationTypeRepository;
    
    private User testUser;
    private NotificationType testNotificationType;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        userRepository.deleteAll();
        notificationTypeRepository.deleteAll();
        
        testUser = User.builder()
            .kakaoId(12345L)
            .nickname("테스트유저")
            .status(Status.ACTIVE)
            .build();
        testUser = userRepository.save(testUser);
        
        testNotificationType = NotificationType.of(Type.CHAT, "테스트 템플릿: %s");
        testNotificationType = notificationTypeRepository.save(testNotificationType);
        
        given(userService.getCurrentUser()).willReturn(testUser);
    }

    @Nested
    @DisplayName("읽지 않은 알림 개수 조회")
    class UnreadCountTest {

        @Test
        @DisplayName("읽지 않은 알림 개수 조회 성공")
        void getsUnreadCountSuccessfully() throws Exception {
            // given
            for (int i = 0; i < 5; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when & then
            mockMvc.perform(get("/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(5));
        }

        @Test
        @DisplayName("알림이 없을 때 0 반환")
        void returnsZeroWhenNoUnreadNotifications() throws Exception {
            // when & then
            mockMvc.perform(get("/notifications/unread-count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(0));
        }

        @Test
        @DisplayName("존재하지 않는 사용자 ID로 조회 시 예외")
        void throwsErrorWhenUserNotFound() {
            // when & then
            assertThatThrownBy(() -> notificationService.getUnreadCount(null))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("알림 읽음 처리")
    class MarkAsReadTest {

        @Test
        @DisplayName("특정 알림 읽음 처리 성공")
        void marksNotificationAsReadSuccessfully() throws Exception {
            // given
            Notification notification = Notification.create(testUser, testNotificationType, "테스트 알림");
            notification = notificationRepository.save(notification);

            // when & then
            mockMvc.perform(put("/notifications/" + notification.getId() + "/read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        }

        @Test
        @DisplayName("모든 알림 읽음 처리 성공")
        void marksAllNotificationsAsReadSuccessfully() throws Exception {
            // given
            for (int i = 0; i < 3; i++) {
                Notification notification = Notification.create(testUser, testNotificationType, "알림" + i);
                notificationRepository.save(notification);
            }

            // when & then
            mockMvc.perform(put("/notifications/read-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        }
    }

    @Nested
    @DisplayName("알림 삭제")
    class DeleteNotificationTest {

        @Test
        @DisplayName("알림 삭제 성공")
        void deletesNotificationSuccessfully() throws Exception {
            // given
            Notification notification = Notification.create(testUser, testNotificationType, "삭제할 알림");
            notification = notificationRepository.save(notification);

            // when & then
            mockMvc.perform(delete("/notifications/" + notification.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        }
    }
}