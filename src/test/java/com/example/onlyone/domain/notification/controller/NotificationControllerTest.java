package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.entity.Notification;
import com.example.onlyone.domain.notification.entity.Type;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = NotificationController.class,
        // SecurityAutoConfiguration 을 제외하면 필터가 전혀 걸리지 않습니다.
        excludeAutoConfiguration = { SecurityAutoConfiguration.class }
)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @MockBean
    private NotificationService notificationService;

    @Test
    @DisplayName("POST /notifications - 201 Created + Body 검증")
    void sendNotification_success() throws Exception {
        //---given---
        String requestJson = """
            {
              "user_id": 1,
              "type_code": "COMMENT",
              "content": "홍길동님이 회원님의 게시글에 댓글을 남겼습니다"
            }
            """;

        // 1) userService stub
        User fakeUser = new User();
        given(userService.getMemberById(1L)).willReturn(fakeUser);

        // 2) notificationService stub
        Notification fakeNotification = mock(Notification.class);
        given(fakeNotification.getNotificationId()).willReturn(10001L);
        given(fakeNotification.getContent())
                .willReturn("홍길동님이 회원님의 게시글에 댓글을 남겼습니다");
        given(fakeNotification.getFcmSent()).willReturn(true);
        LocalDateTime ts = LocalDateTime.of(
                2024,1,15,10,30,0,0);
        given(fakeNotification.getCreatedAt()).willReturn(ts);

        given(notificationService.sendNotification(
                eq(fakeUser),
                eq(Type.COMMENT),
                ArgumentMatchers.eq("홍길동님이 회원님의 게시글에 댓글을 남겼습니다")
        )).willReturn(fakeNotification);

        //---when & then---
        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notification_id").value(10001))
                .andExpect(jsonPath("$.data.content")
                        .value("홍길동님이 회원님의 게시글에 댓글을 남겼습니다"))
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