package com.example.onlyone.global.sse;

import com.example.onlyone.domain.notification.service.SseEmittersService;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.UserService;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SSE 스트림 컨트롤러 단위 테스트
 * - 순수 Mockito 기반으로 SSE 연결 로직 검증
 * - 실제 스트리밍은 SseEmittersService에서 처리
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SSE 스트림 컨트롤러 테스트")
class SseStreamControllerTest {

    @Mock
    private SseEmittersService sseEmittersService;

    @Mock
    private UserService userService;

    @InjectMocks
    private SseStreamController sseStreamController;

    private MockMvc mockMvc;
    private User testUser;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(sseStreamController).build();
        
        testUser = User.builder()
                .userId(1L)
                .kakaoId(12345L)
                .nickname("SSE테스트유저")
                .status(Status.ACTIVE)
                .build();
    }

    @Nested
    @DisplayName("SSE 구독")
    class SseSubscribe {

        @Test
        @DisplayName("정상: 기본 SSE 연결")
        void success_basic_sse_connection() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            SseEmitter mockEmitter = new SseEmitter();
            given(sseEmittersService.createSseConnection(1L, null)).willReturn(mockEmitter);

            // when & then
            mockMvc.perform(get("/sse/subscribe")
                    .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

            verify(sseEmittersService).createSseConnection(1L, null);
        }

        @Test
        @DisplayName("정상: Last-Event-ID와 함께 재연결")
        void success_reconnection_with_last_event_id() throws Exception {
            // given
            String lastEventId = "12345";
            given(userService.getCurrentUser()).willReturn(testUser);
            SseEmitter mockEmitter = new SseEmitter();
            given(sseEmittersService.createSseConnection(1L, lastEventId)).willReturn(mockEmitter);

            // when & then
            mockMvc.perform(get("/sse/subscribe")
                    .header("Last-Event-ID", lastEventId)
                    .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

            verify(sseEmittersService).createSseConnection(1L, lastEventId);
        }

        @Test
        @DisplayName("예외: JSON Accept로 SSE 요청")
        void handle_json_accept_for_sse() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            SseEmitter mockEmitter = new SseEmitter();
            given(sseEmittersService.createSseConnection(1L, null)).willReturn(mockEmitter);

            // when & then - JSON Accept도 처리됨 (컨트롤러에서 지원)
            mockMvc.perform(get("/sse/subscribe")
                    .accept(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk());

            verify(sseEmittersService).createSseConnection(1L, null);
        }
    }

    @Nested
    @DisplayName("연결 상태 확인")
    class ConnectionStatus {

        @Test
        @DisplayName("정상: 연결된 상태 확인")
        void success_check_connected_status() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            given(sseEmittersService.isUserConnected(1L)).willReturn(true);
            given(sseEmittersService.getActiveConnectionCount()).willReturn(5);

            // when & then
            mockMvc.perform(get("/sse/status"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1L))
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.totalConnections").value(5));

            verify(sseEmittersService).isUserConnected(1L);
            verify(sseEmittersService).getActiveConnectionCount();
        }

        @Test
        @DisplayName("정상: 연결되지 않은 상태 확인")
        void success_check_disconnected_status() throws Exception {
            // given
            given(userService.getCurrentUser()).willReturn(testUser);
            given(sseEmittersService.isUserConnected(1L)).willReturn(false);
            given(sseEmittersService.getActiveConnectionCount()).willReturn(0);

            // when & then
            mockMvc.perform(get("/sse/status"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1L))
                .andExpect(jsonPath("$.connected").value(false))
                .andExpect(jsonPath("$.totalConnections").value(0));

            verify(sseEmittersService).isUserConnected(1L);
            verify(sseEmittersService).getActiveConnectionCount();
        }
    }
}