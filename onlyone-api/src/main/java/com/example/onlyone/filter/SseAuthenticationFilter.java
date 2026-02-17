package com.example.onlyone.filter;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * SSE 전용 인증 필터 - DB 조회 없이 JWT 토큰 정보만으로 인증 처리
 *
 * /sse/** 경로에서만 동작하며, 쿠키와 헤더 모두에서 JWT 토큰 추출 지원
 * 부하 테스트를 위해 매 요청마다 DB 조회를 하지 않습니다.
 * UserPrincipal을 principal로 사용하여 도메인과 Security를 분리합니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenParser jwtTokenParser;

    private static final String COOKIE_NAME = "access_token";
    private static final String CONTENT_TYPE_JSON = "application/json";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        return !path.startsWith("/sse/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);

        if (token == null) {
            log.debug("No JWT token found in header or cookie for SSE request: {}", request.getRequestURI());
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "JWT token required for SSE connection");
            return;
        }

        try {
            UserPrincipal principal = jwtTokenParser.parseToken(token);

            if (!principal.isEnabled()) {
                log.warn("SSE connection attempt by withdrawn user: {}", principal);
                sendErrorResponse(response, HttpServletResponse.SC_FORBIDDEN, "User account is withdrawn");
                return;
            }

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("SSE authentication successful: {}", principal);

        } catch (JwtException | IllegalArgumentException e) {
            log.warn("SSE JWT validation failed: {}", e.getClass().getSimpleName());
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid JWT token");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        // 1. Authorization 헤더에서 추출 시도
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }

        // 2. 쿠키에서 추출 시도
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

    private void sendErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(CONTENT_TYPE_JSON);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
