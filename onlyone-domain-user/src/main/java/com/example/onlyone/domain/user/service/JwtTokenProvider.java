package com.example.onlyone.domain.user.service;

import com.example.onlyone.domain.user.entity.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT 토큰 생성 전담 서비스.
 * Access/Refresh 토큰 생성 책임만 담당한다.
 */
@Service
public class JwtTokenProvider {

    private final String jwtSecret;
    private final long accessTokenExpiration;
    private final long refreshTokenExpiration;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String jwtSecret,
            @Value("${jwt.access-expiration}") long accessTokenExpiration,
            @Value("${jwt.refresh-expiration}") long refreshTokenExpiration) {
        this.jwtSecret = jwtSecret;
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }

    public record TokenPair(String accessToken, String refreshToken) {}

    public TokenPair generateTokenPair(User user) {
        return new TokenPair(generateAccessToken(user), generateRefreshToken(user));
    }

    public String generateAccessToken(User user) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpiration);
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());

        return Jwts.builder()
                .subject(user.getUserId().toString())
                .claim("kakaoId", user.getKakaoId())
                .claim("nickname", user.getNickname())
                .claim("status", user.getStatus().name())
                .claim("role", user.getRole().name())
                .claim("type", "access")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }

    public String generateRefreshToken(User user) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + refreshTokenExpiration);
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());

        return Jwts.builder()
                .subject(user.getUserId().toString())
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }
}
