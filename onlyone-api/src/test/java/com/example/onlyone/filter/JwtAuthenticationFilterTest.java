package com.example.onlyone.filter;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.global.filter.JwtAuthenticationFilter;
import com.example.onlyone.global.filter.JwtTokenParser;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter 단위 테스트")
class JwtAuthenticationFilterTest {

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @Mock
    private JwtTokenParser jwtTokenParser;

    @Mock
    private FilterChain filterChain;

    // ==================== 인증 성공 ====================

    @Nested
    @DisplayName("인증 성공")
    class AuthSuccess {

        @Test
        @DisplayName("유효한 Bearer 토큰이면 인증 후 필터 체인을 계속한다")
        void authenticatesWithValidToken() throws Exception {
            var request = new MockHttpServletRequest("GET", "/api/v1/clubs");
            request.addHeader("Authorization", "Bearer valid.token");
            var response = new MockHttpServletResponse();
            UserPrincipal principal = UserPrincipal.fromClaims("1", "10001", "ACTIVE", "ROLE_USER");

            given(jwtTokenParser.extractBearerToken("Bearer valid.token")).willReturn("valid.token");
            given(jwtTokenParser.parseToken("valid.token")).willReturn(principal);

            filter.doFilter(request, response, filterChain);

            then(jwtTokenParser).should().setAuthentication(principal);
            then(filterChain).should().doFilter(request, response);
        }

        @Test
        @DisplayName("INACTIVE 사용자도 로그아웃 경로는 허용한다")
        void allowsLogoutForInactiveUser() throws Exception {
            var request = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
            request.addHeader("Authorization", "Bearer valid.token");
            var response = new MockHttpServletResponse();
            UserPrincipal principal = UserPrincipal.fromClaims("1", "10001", "INACTIVE", "ROLE_USER");

            given(jwtTokenParser.extractBearerToken("Bearer valid.token")).willReturn("valid.token");
            given(jwtTokenParser.parseToken("valid.token")).willReturn(principal);

            filter.doFilter(request, response, filterChain);

            then(jwtTokenParser).should().setAuthentication(principal);
            then(filterChain).should().doFilter(request, response);
        }
    }

    // ==================== 토큰 없음 ====================

    @Nested
    @DisplayName("토큰 없음")
    class NoToken {

        @Test
        @DisplayName("Authorization 헤더가 없으면 인증 없이 통과한다")
        void passesWithoutAuthHeader() throws Exception {
            var request = new MockHttpServletRequest("GET", "/api/v1/clubs");
            var response = new MockHttpServletResponse();

            given(jwtTokenParser.extractBearerToken(null)).willReturn(null);

            filter.doFilter(request, response, filterChain);

            then(jwtTokenParser).should(never()).parseToken(any());
            then(filterChain).should().doFilter(request, response);
        }

        @Test
        @DisplayName("Bearer가 아닌 헤더이면 인증 없이 통과한다")
        void passesWithNonBearerHeader() throws Exception {
            var request = new MockHttpServletRequest("GET", "/api/v1/clubs");
            request.addHeader("Authorization", "Basic abc");
            var response = new MockHttpServletResponse();

            given(jwtTokenParser.extractBearerToken("Basic abc")).willReturn(null);

            filter.doFilter(request, response, filterChain);

            then(jwtTokenParser).should(never()).parseToken(any());
            then(filterChain).should().doFilter(request, response);
        }
    }

    // ==================== 인증 실패 ====================

    @Nested
    @DisplayName("인증 실패")
    class AuthFailure {

        @Test
        @DisplayName("유효하지 않은 JWT이면 401 에러 응답을 반환한다")
        void returnsUnauthorizedForInvalidJwt() throws Exception {
            var request = new MockHttpServletRequest("GET", "/api/v1/clubs");
            request.addHeader("Authorization", "Bearer bad.token");
            var response = new MockHttpServletResponse();

            given(jwtTokenParser.extractBearerToken("Bearer bad.token")).willReturn("bad.token");
            given(jwtTokenParser.parseToken("bad.token")).willThrow(new JwtException("Invalid"));

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("GLOBAL_401_1");
            then(filterChain).should(never()).doFilter(request, response);
        }

        @Test
        @DisplayName("클레임 누락이면 401 에러 응답을 반환한다")
        void returnsUnauthorizedForMissingClaims() throws Exception {
            var request = new MockHttpServletRequest("GET", "/api/v1/clubs");
            request.addHeader("Authorization", "Bearer bad.token");
            var response = new MockHttpServletResponse();

            given(jwtTokenParser.extractBearerToken("Bearer bad.token")).willReturn("bad.token");
            given(jwtTokenParser.parseToken("bad.token"))
                    .willThrow(new IllegalArgumentException("missing claim"));

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("GLOBAL_401_1");
            then(filterChain).should(never()).doFilter(request, response);
        }

        @Test
        @DisplayName("INACTIVE 사용자가 일반 경로 접근 시 403 에러 응답을 반환한다")
        void returnsForbiddenForInactiveUserOnNonLogout() throws Exception {
            var request = new MockHttpServletRequest("GET", "/api/v1/clubs");
            request.addHeader("Authorization", "Bearer valid.token");
            var response = new MockHttpServletResponse();
            UserPrincipal principal = UserPrincipal.fromClaims("1", "10001", "INACTIVE", "ROLE_USER");

            given(jwtTokenParser.extractBearerToken("Bearer valid.token")).willReturn("valid.token");
            given(jwtTokenParser.parseToken("valid.token")).willReturn(principal);

            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("USER_403_1");
            then(filterChain).should(never()).doFilter(request, response);
        }
    }
}
