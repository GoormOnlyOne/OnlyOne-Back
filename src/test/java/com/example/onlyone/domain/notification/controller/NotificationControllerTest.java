package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.NotificationRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationResponseDto;
import com.example.onlyone.domain.notification.service.NotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = NotificationController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class
)
class NotificationControllerTest {

    @TestConfiguration
    static class Config {
        // NotificationService 만 Mock Bean 으로 등록
        @Bean
        public NotificationService notificationService() {
            return Mockito.mock(NotificationService.class);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationService notificationService;

    @Test
    @DisplayName("POST /notifications – 201 Created + NotificationResponseDto 반환")
    void sendNotification_success() throws Exception {
        String jsonRequest = """
            {
              "user_id": 1,
              "type_code": "COMMENT",
              "args": ["Alice","Bob"]
            }
            """;

        // 테스트용 반환 DTO 준비
        LocalDateTime ts = LocalDateTime.of(2024, 1, 15, 10, 30);
        NotificationResponseDto fakeResponse =
                new NotificationResponseDto(
                        10001L,
                        "회원 Alice님이 회원 Bob님의 글을 좋아합니다.",
                        true,
                        ts
                );

        // any(NotificationRequestDto.class) 로 stub
        given(notificationService.sendNotification(any(NotificationRequestDto.class)))
                .willReturn(fakeResponse);

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.notification_id").value(10001))
                .andExpect(jsonPath("$.content").value("회원 Alice님이 회원 Bob님의 글을 좋아합니다."))
                .andExpect(jsonPath("$.fcm_sent").value(true))
                .andExpect(jsonPath("$.created_at").value("2024-01-15T10:30:00"))
        ;
    }

    @Test
    @DisplayName("POST /notifications – Validation 오류 (args 누락)")
    void sendNotification_validationFail() throws Exception {
        String badRequest = """
            {
              "user_id": 1,
              "type_code": "COMMENT"
            }
            """;

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badRequest)
                )
                .andExpect(status().isBadRequest());
    }
}