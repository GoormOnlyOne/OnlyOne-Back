package com.example.onlyone.global.filter;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 토큰 파싱 및 UserPrincipal 생성 공통 유틸리티
 *
 * JwtAuthenticationFilter, SseAuthenticationFilter 양쪽에서 사용합니다.
 * JWT Secret 길이 검증을 시작 시점에 수행합니다.
 */
@Slf4j
@Component
public class JwtTokenParser {

    private static final int MIN_SECRET_LENGTH = 32; // 256 bits
    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${jwt.secret}")
    private String jwtSecret;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        if (jwtSecret == null || jwtSecret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT secret must be at least " + MIN_SECRET_LENGTH + " characters (256 bits). "
                            + "Current length: " + (jwtSecret == null ? 0 : jwtSecret.length()));
        }
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        log.info("JWT token parser initialized (secret length: {} chars)", jwtSecret.length());
    }

    /**
     * JWT 토큰을 파싱하여 UserPrincipal을 생성합니다.
     *
     * @throws io.jsonwebtoken.JwtException 토큰이 유효하지 않은 경우
     * @throws IllegalArgumentException 필수 클레임이 누락된 경우
     */
    public UserPrincipal parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String userIdString = claims.getSubject();
        if (userIdString == null || userIdString.isBlank()) {
            throw new IllegalArgumentException("JWT subject (userId) is missing");
        }

        Object kakaoIdObj = claims.get("kakaoId");
        if (kakaoIdObj == null) {
            throw new IllegalArgumentException("JWT claim 'kakaoId' is missing");
        }
        String kakaoIdString = String.valueOf(kakaoIdObj);

        String statusString = claims.get("status", String.class);
        if (statusString == null || statusString.isBlank()) {
            throw new IllegalArgumentException("JWT claim 'status' is missing");
        }

        String roleString = claims.get("role", String.class);
        if (roleString == null || roleString.isBlank()) {
            throw new IllegalArgumentException("JWT claim 'role' is missing");
        }

        return UserPrincipal.fromClaims(userIdString, kakaoIdString, statusString, roleString);
    }

    /**
     * "Authorization" 헤더에서 Bearer 토큰을 추출합니다.
     *
     * @return 토큰 문자열, 유효한 Bearer 헤더가 아니면 null
     */
    public String extractBearerToken(String header) {
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * UserPrincipal로 SecurityContext에 인증 정보를 설정합니다.
     */
    public void setAuthentication(UserPrincipal principal) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
