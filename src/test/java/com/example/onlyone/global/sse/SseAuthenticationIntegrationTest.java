package com.example.onlyone.global.sse;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * SSE 인증 통합 테스트
 * 실제 Spring Security 필터 체인을 통한 인증 테스트
 */
@SpringBootTest
@AutoConfigureMockMvc
@SpringJUnitConfig
@Transactional
class SseAuthenticationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    
    @Autowired
    private UserService userService;
    
    @Autowired
    private UserRepository userRepository;
    
    private String validToken;
    private User testUser;
    
    @BeforeEach
    void setUp() {
        // 테스트 사용자 생성
        testUser = User.builder()
                .kakaoId(12345L)
                .nickname("테스트유저")
                .profileImage("http://example.com/profile.jpg")
                .status(Status.ACTIVE)
                .build();
        
        testUser = userRepository.save(testUser);
        
        // 유효한 JWT 토큰 생성
        validToken = userService.generateAccessToken(testUser);
    }
    
    @Test
    @DisplayName("토큰 없이 SSE 연결 시도 시 401 Unauthorized 반환")
    void sseConnectWithoutToken() throws Exception {
        mockMvc.perform(get("/sse/subscribe")
                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
            .andDo(print())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("No JWT token found in request"));
    }
    
    @Test
    @DisplayName("유효하지 않은 토큰으로 SSE 연결 시도 시 401 Unauthorized 반환")
    void sseConnectWithInvalidToken() throws Exception {
        mockMvc.perform(get("/sse/subscribe")
                .header("Authorization", "Bearer invalid.token.here")
                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
            .andDo(print())
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").exists());
    }
    
    @Test
    @DisplayName("만료된 토큰으로 SSE 연결 시도 시 401 Unauthorized 반환")
    void sseConnectWithExpiredToken() throws Exception {
        // 만료된 토큰 생성 (테스트용 - 실제로는 JwtService에 만료 시간을 조작하는 메서드가 필요)
        String expiredToken = "expired.jwt.token";
        
        mockMvc.perform(get("/sse/subscribe")
                .header("Authorization", "Bearer " + expiredToken)
                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
            .andDo(print())
            .andExpect(status().isUnauthorized());
    }
    
    @Test
    @DisplayName("유효한 토큰으로 SSE 연결 성공")
    void sseConnectWithValidToken() throws Exception {
        mockMvc.perform(get("/sse/subscribe")
                .header("Authorization", "Bearer " + validToken)
                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
            .andDo(print())
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted());
    }
    
    @Test
    @DisplayName("쿠키를 통한 SSE 연결 성공")
    void sseConnectWithCookie() throws Exception {
        mockMvc.perform(get("/sse/subscribe")
                .cookie(new jakarta.servlet.http.Cookie("access_token", validToken))
                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
            .andDo(print())
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted());
    }
    
    @Test
    @DisplayName("Last-Event-ID 헤더와 함께 SSE 재연결 성공")
    void sseReconnectWithLastEventId() throws Exception {
        String lastEventId = "evt_1234567890_abcd1234";
        
        mockMvc.perform(get("/sse/subscribe")
                .header("Authorization", "Bearer " + validToken)
                .header("Last-Event-ID", lastEventId)
                .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
            .andDo(print())
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted());
    }
    
    @Test
    @DisplayName("토큰 없이 SSE 상태 조회 시 403 Forbidden 반환")
    void sseStatusWithoutToken() throws Exception {
        mockMvc.perform(get("/sse/status"))
            .andDo(print())
            .andExpect(status().isForbidden());
    }
    
    @Test
    @DisplayName("유효한 토큰으로 SSE 상태 조회 성공")
    void sseStatusWithValidToken() throws Exception {
        mockMvc.perform(get("/sse/status")
                .header("Authorization", "Bearer " + validToken))
            .andDo(print())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(testUser.getUserId()))
            .andExpect(jsonPath("$.connected").value(false))
            .andExpect(jsonPath("$.totalConnections").exists());
    }
}