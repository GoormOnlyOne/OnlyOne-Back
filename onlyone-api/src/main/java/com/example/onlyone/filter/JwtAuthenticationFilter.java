package com.example.onlyone.filter;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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
@Log4j2
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenParser jwtTokenParser;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            UserPrincipal principal = jwtTokenParser.parseToken(token);

            // 상태 체크: INACTIVE인 경우 인증 거부 (로그아웃은 허용)
            if (!principal.isEnabled() && !"/api/v1/auth/logout".equals(request.getRequestURI())) {
                log.warn("Inactive user attempting to access: userId={}", principal.getUserId());
                throw new CustomException(ErrorCode.USER_WITHDRAWN);
            }

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            principal, null, principal.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("JWT authentication successful: {}", principal);

        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT validation failed: {}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        filterChain.doFilter(request, response);
    }
}
