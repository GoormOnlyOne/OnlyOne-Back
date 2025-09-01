package com.example.onlyone.global.sse;

import com.example.onlyone.config.TestConfig;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SSE 스트림 컨트롤러 통합 테스트
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
    
    @MockBean
    private UserService userService;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
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
        
        // UserService Mock 설정
        given(userService.getCurrentUser()).willReturn(testUser);
    }

    @Nested
    @DisplayName("SSE 구독")
    class SseSubscribe {

        @Test
        @DisplayName("SSE 연결 성공")
        void sseConnection_success() throws Exception {
            // when & then
            mockMvc.perform(get("/sse/subscribe")
                    .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
        }

        @Test
        @DisplayName("Last-Event-ID와 함께 재연결 성공")
        void reconnectionWithLastEventId_success() throws Exception {
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
        @DisplayName("JSON Accept 헤더로 요청 성공")
        void jsonAcceptHeader_success() throws Exception {
            // when & then
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
        @DisplayName("연결된 상태 확인 성공")
        void connectedStatus_success() throws Exception {
            // given
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
        @DisplayName("연결되지 않은 상태 확인 성공")
        void disconnectedStatus_success() throws Exception {
            // when & then
            mockMvc.perform(get("/sse/status"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(testUser.getUserId()))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.totalConnections").value(0));
        }
    }
}