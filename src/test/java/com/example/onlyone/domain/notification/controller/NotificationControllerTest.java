package com.example.onlyone.domain.notification.controller;

import com.example.onlyone.domain.notification.dto.NotificationListRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationRequestDto;
import com.example.onlyone.domain.notification.dto.NotificationResponseDto;
import com.example.onlyone.domain.notification.service.NotificationService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = NotificationController.class,
        excludeAutoConfiguration = SecurityAutoConfiguration.class
)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 실제 빈 대신 MockBean 으로 주입합니다.
     * 그래야 서비스 내부 로직이 실행되지 않고
     * willReturn / willThrow 로 stub 할 수 있습니다.
     */
    @MockBean
    private NotificationService notificationService;

    @Test
    @DisplayName("POST /notifications – 201 Created + NotificationResponseDto 반환")
    void sendNotification_success() throws Exception {
        String jsonRequest = """
            {
              "userId": 1,
              "type": "COMMENT",
              "args": ["Alice","Bob"]
            }
            """;

        // 리턴할 fake DTO 준비
        LocalDateTime ts = LocalDateTime.of(2024, 1, 15, 10, 30);
        NotificationResponseDto fake =
                NotificationResponseDto.builder()
                        .notificationId(10001L)
                        .content("회원 Alice님이 회원 Bob님의 글에 댓글을 달았습니다.")
                        .isRead(true)
                        .fcmSent(true)
                        .createdAt(ts)
                        .build();

        // 서비스가 호출되면 fake 를 리턴하도록 stub
        given(notificationService.sendNotification(any(NotificationRequestDto.class)))
                .willReturn(fake);

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.notificationId").value(10001))
                .andExpect(jsonPath("$.data.content")
                        .value("회원 Alice님이 회원 Bob님의 글에 댓글을 달았습니다."))
                .andExpect(jsonPath("$.data.isRead").value(true))
                .andExpect(jsonPath("$.data.fcmSent").value(true))
                .andExpect(jsonPath("$.data.createdAt")
                        .value("2024-01-15T10:30:00"))
        ;

        then(notificationService).should()
                .sendNotification(any(NotificationRequestDto.class));
    }

    @Test
    @DisplayName("POST /notifications – Validation 오류 (args 누락)")
    void sendNotification_validationFail() throws Exception {
        String bad = """
            {
              "userId": 1,
              "type": "COMMENT"
            }
            """;

        mockMvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bad)
                )
                .andExpect(status().isBadRequest());

        // 서비스 호출 전이므로, 호출되지 않아야 합니다.
        then(notificationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("PATCH /notifications/read-all – 204 No Content (성공)")
    void readAll_success() throws Exception {
        String body = """
            {
              "userId": 42
            }
            """;

        // markAllAsRead 는 void 이므로 별도 stub 없이 willDoNothing 이 기본
        mockMvc.perform(patch("/notifications/read-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                )
                .andExpect(status().isNoContent());

        then(notificationService).should()
                .markAllAsRead(any(NotificationListRequestDto.class));
    }

    @Test
    @DisplayName("PATCH /notifications/read-all – 404 Not Found (미읽음 알림 없음)")
    void readAll_notFound() throws Exception {
        String body = """
            {
              "userId": 99
            }
            """;

        // 서비스 호출 시 NOTIFICATION_NOT_FOUND 예외를 던지도록 stub
        willThrow(new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND))
                .given(notificationService)
                .markAllAsRead(any(NotificationListRequestDto.class));

        mockMvc.perform(patch("/notifications/read-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                // CommonResponse.fail 의 구조에 따라 data.code 또는 errorCode 를 검증
                .andExpect(jsonPath("$.data.code")
                        .value(ErrorCode.NOTIFICATION_NOT_FOUND.name()))
        ;

        then(notificationService).should()
                .markAllAsRead(any(NotificationListRequestDto.class));
    }
}