package com.example.onlyone.global.filter;

import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
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
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
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
    
    private final UserRepository userRepository;
    private static final String COOKIE_NAME = "access_token";

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
            response.getWriter().write("{\"error\":\"JWT token required for SSE connection\"}");
            response.setContentType("application/json");
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

            // 사용자 상태 확인
            Optional<User> userOpt = userRepository.findByKakaoId(kakaoId);
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                if (Status.INACTIVE.equals(user.getStatus())) {
                    log.warn("SSE connection attempt by withdrawn user: kakaoId={}", kakaoId);
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.getWriter().write("{\"error\":\"User account is withdrawn\"}");
                    response.setContentType("application/json");
                    return;
                }
            }

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            kakaoIdString,
                            null,
                            Collections.emptyList()
                    );
            SecurityContextHolder.getContext().setAuthentication(auth);
            
            log.debug("SSE authentication successful: kakaoId={}", kakaoId);
            
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("SSE JWT validation failed: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\":\"Invalid JWT token\"}");
            response.setContentType("application/json");
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