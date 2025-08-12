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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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

@Log4j2
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String jwtSecret;
    
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String requestURI = request.getRequestURI();
        String header = request.getHeader("Authorization");
        
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(7);
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
            Claims claims = Jwts.parser()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            String kakaoIdString = claims.getSubject();
            Long kakaoId = Long.valueOf(kakaoIdString);

            // 사용자 상태 확인 - 탈퇴한 사용자인 경우 인증 거부, GUEST와 ACTIVE는 허용
            Optional<User> userOpt = userRepository.findByKakaoId(kakaoId);
            if (userOpt.isPresent()) {
                User user = userOpt.get();

                if (Status.INACTIVE.name().equals(user.getStatus())) {
                    // 로그아웃 요청은 탈퇴한 사용자도 허용 (토큰 정리를 위해)
                    if (!"/auth/logout".equals(request.getRequestURI())) {
                        throw new CustomException(ErrorCode.USER_WITHDRAWN);
                    }
                }
            }

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            kakaoIdString,   // principal
                            null,            // credentials
                            Collections.emptyList()  // 권한 목록
                    );
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (JwtException | IllegalArgumentException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        filterChain.doFilter(request, response);
    }
}
