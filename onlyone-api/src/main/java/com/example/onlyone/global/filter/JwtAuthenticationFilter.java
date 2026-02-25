package com.example.onlyone.global.filter;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.global.exception.ErrorCode;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.DispatcherType;
import java.io.IOException;

/**
 * JWT 인증 필터 - DB 조회 없이 JWT 토큰 정보만으로 인증 처리
 *
 * JWT 토큰에 userId, kakaoId, status, role 정보가 포함되어 있습니다.
 * SSE 경로(/api/v1/sse/**)의 경우 쿠키에서도 토큰을 추출합니다.
 * ASYNC dispatch에서도 실행되어 SSE 비동기 이벤트 전송 시 SecurityContext를 유지합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String SSE_PATH_PREFIX = "/api/v1/sse/";
    private static final String COOKIE_NAME = "access_token";

    private final JwtTokenParser jwtTokenParser;

    /** ASYNC dispatch에서도 필터가 실행되도록 설정 (SSE 비동기 이벤트 전송 지원) */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        // ASYNC dispatch에서 이미 인증된 컨텍스트가 있으면 스킵
        if (request.getDispatcherType() == DispatcherType.ASYNC
                && org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = jwtTokenParser.extractBearerToken(request.getHeader("Authorization"));

        // SSE 경로: EventSource API가 커스텀 헤더를 지원하지 않으므로 쿠키 fallback
        if (token == null && isSsePath(request)) {
            token = extractTokenFromCookie(request);
        }

        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            UserPrincipal principal = jwtTokenParser.parseToken(token);

            if (!principal.isEnabled() && !"/api/v1/auth/logout".equals(request.getRequestURI())) {
                log.warn("Inactive user attempting to access: userId={}", principal.getUserId());
                JwtTokenParser.writeErrorResponse(response, ErrorCode.USER_WITHDRAWN);
                return;
            }

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

    private boolean isSsePath(HttpServletRequest request) {
        return request.getRequestURI().startsWith(SSE_PATH_PREFIX);
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
