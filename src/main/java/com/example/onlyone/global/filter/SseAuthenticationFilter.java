package com.example.onlyone.global.filter;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import io.jsonwebtoken.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import io.jsonwebtoken.Jwts;

/**
 * SSE 전용 인증 필터
 * /sse/** 경로에서만 동작하며, 쿠키와 헤더 모두에서 JWT 토큰 추출 지원
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseAuthenticationFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String jwtSecret;
    
    private static final String COOKIE_NAME = "access_token";
    private static final String CONTENT_TYPE_JSON = "application/json";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        // /sse/** 경로가 아니면 필터 적용 안함
        return !path.startsWith("/sse/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        // 토큰 추출: 헤더 우선, 쿠키 fallback
        String token = extractToken(request);
        
        if (token == null) {
            log.debug("No JWT token found in header or cookie for SSE request: {}", request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(CONTENT_TYPE_JSON);
            response.getWriter().write("{\"error\":\"JWT token required for SSE connection\"}");
            return;
        }

        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
            Claims claims = Jwts.parser()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String kakaoIdString = claims.getSubject();
            Long kakaoId = Long.valueOf(kakaoIdString);

            // JWT가 유효하면 사용자는 존재하고 활성 상태라고 가정 (DB 조회 제거)
            // INACTIVE 사용자는 토큰 재발급 시점에서 차단되므로 여기서는 불필요

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            kakaoIdString,   // principal
                            null,            // credentials
                            Collections.emptyList()  // 권한 목록
                    );
            SecurityContextHolder.getContext().setAuthentication(auth);
            
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("SSE JWT validation failed: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(CONTENT_TYPE_JSON);
            response.getWriter().write("{\"error\":\"Invalid JWT token\"}");
            return;
        }
        
        filterChain.doFilter(request, response);
    }

    /**
     * JWT 토큰 추출: 헤더에서 우선 시도, 없으면 쿠키에서 추출
     */
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
}