package com.example.onlyone.global.filter;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import io.jsonwebtoken.Jwts;

@Log4j2
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String requestURI = request.getRequestURI();
        String header = request.getHeader("Authorization");
        
        log.debug("JWT Filter - URI: {}, Authorization header: {}", requestURI, header != null ? "present" : "missing");
        
        if (header == null || !header.startsWith("Bearer ")) {
            log.debug("JWT Filter - No valid Authorization header, proceeding without authentication");
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

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            kakaoIdString,   // principal
                            null,            // credentials
                            Collections.emptyList()  // 권한 목록
                    );
            SecurityContextHolder.getContext().setAuthentication(auth);
            log.debug("JWT Filter - Authentication successful for kakaoId: {}", kakaoId);
        } catch (JwtException | IllegalArgumentException e) {
            log.error("JWT Filter - Authentication failed for URI: {}, error: {}", requestURI, e.getMessage());
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        filterChain.doFilter(request, response);
    }
}
