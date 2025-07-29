package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = NotificationController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class
)
@Import(NotificationControllerTest.TestConfig.class)
class NotificationControllerTest {

    @TestConfiguration
    static class TestConfig {
        @Bean
        public UserService userService() {
            return Mockito.mock(UserService.class);
        }
        @Bean
        public NotificationService notificationService() {
            return Mockito.mock(NotificationService.class);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private NotificationService notificationService;

    @Test
    @DisplayName("POST /notifications - 201 Created + Body 검증")
    void sendNotification_success() throws Exception {
        String jsonRequest = """
            {
              "user_id": 1,
              "type_code": "COMMENT",
              "content": "댓글 내용"
            }
            """;

        // --- stub ---
        User fakeUser = new User();
        given(userService.getMemberById(1L))
                .willReturn(fakeUser);

        Notification fakeNoti = Mockito.mock(Notification.class);
        given(fakeNoti.getNotificationId()).willReturn(10001L);
        given(fakeNoti.getContent()).willReturn("댓글 내용");
        given(fakeNoti.getFcmSent()).willReturn(true);
        LocalDateTime ts = LocalDateTime.of(
                2024,1,15,10,30,0,0);
        given(fakeNoti.getCreatedAt()).willReturn(ts);

        // eq() 대신 실제 값 그대로 전달
        given(notificationService.sendNotification(
                fakeUser,
                Type.COMMENT,
                "댓글 내용"
        )).willReturn(fakeNoti);

        // --- perform & expect ---
        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notification_id").value(10001))
                .andExpect(jsonPath("$.data.content").value("댓글 내용"))
                .andExpect(jsonPath("$.data.fcm_sent").value(true))
                .andExpect(jsonPath("$.data.created_at")
                        .value("2024-01-15T10:30:00"))
                ;
    }

    @Test
    @DisplayName("POST /notifications - Validation 오류 (content blank)")
    void sendNotification_validationFail() throws Exception {
        String requestJson = """
            {
              "user_id": 1,
              "type_code": "COMMENT",
              "content": ""
            }
            """;

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                )
                .andExpect(status().isBadRequest());
    }
}