package com.example.onlyone.global.sse;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.notification.service.SseEmittersService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SSE 스트림 컨트롤러 통합 테스트
 * - Spring Boot 통합 테스트로 실제 SSE 연결 동작 검증
 * - Mock 의존성 제거하고 실제 서비스 사용
 */
@SpringBootTest
@Import(TestConfig.class)
@ActiveProfiles("test")
@Transactional
@DisplayName("SSE 스트림 컨트롤러 테스트")
class SseStreamControllerTest {

    @Autowired
    private WebApplicationContext context;
    
    @Autowired
    private SseEmittersService sseEmittersService;
    
    @Autowired
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        
        // SSE 연결 상태 초기화
        sseEmittersService.clearAllConnections();
        
        // 테스트용 사용자 생성
        testUser = User.builder()
                .kakaoId(12345L)
                .nickname("SSE테스트유저")
                .status(Status.ACTIVE)
                .build();
        testUser = userRepository.save(testUser);
    }

    @Nested
    @DisplayName("SSE 구독")
    class SseSubscribe {

        @Test
        @WithMockUser(username = "12345")
        @DisplayName("UT-NT-052: SSE 연결이 정상 수립되는가?")
        void UT_NT_052_establishes_sse_connection_successfully() throws Exception {
            // when & then
            mockMvc.perform(get("/sse/subscribe")
                    .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
        }

        @Test
        @WithMockUser(username = "12345")
        @DisplayName("UT-NT-055: 연결 끊김 후 재연결이 정상 동작하는가?")
        void UT_NT_055_handles_reconnection_with_last_event_id() throws Exception {
            // given
            String lastEventId = "notification_1_2024-01-01T00:00:00";

            // when & then
            mockMvc.perform(get("/sse/subscribe")
                    .header("Last-Event-ID", lastEventId)
                    .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
        }

        @Test
        @WithMockUser(username = "12345")
        @DisplayName("JSON Accept로 SSE 요청 시 정상 처리")
        void UT_NT_052_handles_json_accept_for_sse_request() throws Exception {
            // when & then - JSON Accept도 처리됨 (컨트롤러에서 지원)
            mockMvc.perform(get("/sse/subscribe")
                    .accept(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("연결 상태 확인")
    class ConnectionStatus {

        @Test
        @WithMockUser(username = "12345")
        @DisplayName("연결된 상태 확인")
        void UT_NT_052_checks_connected_status() throws Exception {
            // given - SSE 연결 생성
            sseEmittersService.createSseConnection(testUser.getUserId());

            // when & then
            mockMvc.perform(get("/sse/status"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(testUser.getUserId()))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.totalConnections").value(1));
        }

        @Test
        @WithMockUser(username = "12345")
        @DisplayName("연결되지 않은 상태 확인")
        void UT_NT_052_checks_disconnected_status() throws Exception {
            // when & then - 연결 없이 상태 확인
            mockMvc.perform(get("/sse/status"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(testUser.getUserId()))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.totalConnections").value(0));
        }
    }
}