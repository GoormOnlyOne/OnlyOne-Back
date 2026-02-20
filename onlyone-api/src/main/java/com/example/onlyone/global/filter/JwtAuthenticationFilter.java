package com.example.onlyone.global.filter;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.global.exception.ErrorCode;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 인증 필터 - DB 조회 없이 JWT 토큰 정보만으로 인증 처리
 *
 * 부하 테스트를 위해 매 요청마다 DB 조회를 하지 않습니다.
 * JWT 토큰에 userId, kakaoId, status, role 정보가 포함되어 있습니다.
 * UserPrincipal을 principal로 사용하여 도메인과 Security를 분리합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenParser jwtTokenParser;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/sse/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String token = jwtTokenParser.extractBearerToken(request.getHeader("Authorization"));

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            UserPrincipal principal = jwtTokenParser.parseToken(token);

            // 상태 체크: INACTIVE인 경우 인증 거부 (로그아웃은 허용)
            if (!principal.isEnabled() && !"/api/v1/auth/logout".equals(request.getRequestURI())) {
                log.warn("Inactive user attempting to access: userId={}", principal.getUserId());
                JwtTokenParser.writeErrorResponse(response, ErrorCode.USER_WITHDRAWN);
                return;
            }

            // GUEST 상태: 회원가입/로그아웃 외 접근 차단
            if (principal.isGuest()) {
                String uri = request.getRequestURI();
                if (!"/api/v1/auth/signup".equals(uri) && !"/api/v1/auth/logout".equals(uri)
                        && !"/api/v1/auth/withdraw".equals(uri)) {
                    log.warn("GUEST user attempting to access protected resource: userId={}, uri={}",
                            principal.getUserId(), uri);
                    JwtTokenParser.writeErrorResponse(response, ErrorCode.NO_PERMISSION);
                    return;
                }
            }

            jwtTokenParser.setAuthentication(principal);
            log.debug("JWT authentication successful: {}", principal);

        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT validation failed: {}", e.getClass().getSimpleName());
            JwtTokenParser.writeErrorResponse(response, ErrorCode.UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
