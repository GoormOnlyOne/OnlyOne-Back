package com.example.onlyone.global.filter;

import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SseAuthenticationFilterTest {

    @InjectMocks
    private SseAuthenticationFilter sseAuthenticationFilter;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FilterChain filterChain;

    private String jwtSecret = "testSecretKeyForJwtTokenGenerationMustBeLongEnough";
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(sseAuthenticationFilter, "jwtSecret", jwtSecret);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("SSE 경로가 아닌 경우 필터를 적용하지 않음")
    void shouldNotFilterNonSsePaths() throws Exception {
        // given
        request.setRequestURI("/api/users");

        // when
        boolean shouldNotFilter = sseAuthenticationFilter.shouldNotFilter(request);

        // then
        assertThat(shouldNotFilter).isTrue();
    }

    @Test
    @DisplayName("SSE 경로인 경우 필터를 적용함")
    void shouldFilterSsePaths() throws Exception {
        // given
        request.setRequestURI("/sse/subscribe");

        // when
        boolean shouldNotFilter = sseAuthenticationFilter.shouldNotFilter(request);

        // then
        assertThat(shouldNotFilter).isFalse();
    }

    @Test
    @DisplayName("Authorization 헤더로 JWT 토큰 인증 성공")
    void authenticateWithAuthorizationHeader() throws Exception {
        // given
        Long kakaoId = 12345L;
        String token = generateTestToken(kakaoId);
        
        request.setRequestURI("/sse/subscribe");
        request.addHeader("Authorization", "Bearer " + token);
        
        User mockUser = User.builder()
                .userId(1L)
                .kakaoId(kakaoId)
                .status(Status.ACTIVE)
                .build();
        
        when(userRepository.findByKakaoId(kakaoId)).thenReturn(Optional.of(mockUser));

        // when
        sseAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(kakaoId.toString());
    }

    @Test
    @DisplayName("쿠키로 JWT 토큰 인증 성공")
    void authenticateWithCookie() throws Exception {
        // given
        Long kakaoId = 12345L;
        String token = generateTestToken(kakaoId);
        
        request.setRequestURI("/sse/subscribe");
        Cookie cookie = new Cookie("access_token", token);
        request.setCookies(cookie);
        
        User mockUser = User.builder()
                .userId(1L)
                .kakaoId(kakaoId)
                .status(Status.ACTIVE)
                .build();
        
        when(userRepository.findByKakaoId(kakaoId)).thenReturn(Optional.of(mockUser));

        // when
        sseAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(kakaoId.toString());
    }

    @Test
    @DisplayName("헤더가 쿠키보다 우선순위가 높음")
    void headerTakesPriorityOverCookie() throws Exception {
        // given
        Long headerKakaoId = 11111L;
        Long cookieKakaoId = 22222L;
        String headerToken = generateTestToken(headerKakaoId);
        String cookieToken = generateTestToken(cookieKakaoId);
        
        request.setRequestURI("/sse/subscribe");
        request.addHeader("Authorization", "Bearer " + headerToken);
        Cookie cookie = new Cookie("access_token", cookieToken);
        request.setCookies(cookie);
        
        User mockUser = User.builder()
                .userId(1L)
                .kakaoId(headerKakaoId)
                .status(Status.ACTIVE)
                .build();
        
        when(userRepository.findByKakaoId(headerKakaoId)).thenReturn(Optional.of(mockUser));

        // when
        sseAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo(headerKakaoId.toString());
    }

    @Test
    @DisplayName("토큰이 없으면 401 반환")
    void returnUnauthorizedWhenNoToken() throws Exception {
        // given
        request.setRequestURI("/sse/subscribe");

        // when
        sseAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("JWT token required for SSE connection");
    }

    @Test
    @DisplayName("유효하지 않은 토큰인 경우 401 반환")
    void returnUnauthorizedWhenInvalidToken() throws Exception {
        // given
        request.setRequestURI("/sse/subscribe");
        request.addHeader("Authorization", "Bearer invalid-token");

        // when
        sseAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid JWT token");
    }

    @Test
    @DisplayName("탈퇴한 사용자(INACTIVE)는 403 반환")
    void returnForbiddenForInactiveUser() throws Exception {
        // given
        Long kakaoId = 12345L;
        String token = generateTestToken(kakaoId);
        
        request.setRequestURI("/sse/subscribe");
        request.addHeader("Authorization", "Bearer " + token);
        
        User mockUser = User.builder()
                .userId(1L)
                .kakaoId(kakaoId)
                .nickname("testuser")
                .status(Status.INACTIVE)
                .build();
        
        when(userRepository.findByKakaoId(kakaoId)).thenReturn(Optional.of(mockUser));

        // when
        sseAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("User account is withdrawn");
    }

    @Test
    @DisplayName("사용자가 DB에 없어도 인증은 통과 (신규 사용자 허용)")
    void allowAuthenticationWhenUserNotInDb() throws Exception {
        // given
        Long kakaoId = 99999L;
        String token = generateTestToken(kakaoId);
        
        request.setRequestURI("/sse/subscribe");
        request.addHeader("Authorization", "Bearer " + token);
        
        when(userRepository.findByKakaoId(kakaoId)).thenReturn(Optional.empty());

        // when
        sseAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // then
        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    private String generateTestToken(Long kakaoId) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + 3600000); // 1 hour
        
        return Jwts.builder()
                .subject(kakaoId.toString())
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }
}