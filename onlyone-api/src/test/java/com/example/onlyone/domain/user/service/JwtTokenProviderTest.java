package com.example.onlyone.domain.user.service;

import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Role;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JwtTokenProvider 단위 테스트")
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-must-be-at-least-64-bytes-long-for-HS512-algorithm-padding";
    private static final long ACCESS_EXPIRATION = 3600000L;   // 1시간
    private static final long REFRESH_EXPIRATION = 604800000L; // 7일

    private JwtTokenProvider jwtTokenProvider;
    private User testUser;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(SECRET, ACCESS_EXPIRATION, REFRESH_EXPIRATION);

        testUser = User.builder()
                .userId(1L)
                .kakaoId(12345L)
                .nickname("테스트유저")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1995, 1, 1))
                .role(Role.ROLE_USER)
                .build();
    }

    private Claims parseClaims(String token) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes());
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
    }

    // =========================================================================
    // generateTokenPair
    // =========================================================================

    @Nested
    @DisplayName("generateTokenPair")
    class GenerateTokenPair {

        @Test
        @DisplayName("성공: access/refresh 토큰 쌍 반환")
        void success() {
            // when
            JwtTokenProvider.TokenPair pair = jwtTokenProvider.generateTokenPair(testUser);

            // then
            assertThat(pair.accessToken()).isNotBlank();
            assertThat(pair.refreshToken()).isNotBlank();
            assertThat(pair.accessToken()).isNotEqualTo(pair.refreshToken());
        }
    }

    // =========================================================================
    // generateAccessToken
    // =========================================================================

    @Nested
    @DisplayName("generateAccessToken")
    class GenerateAccessToken {

        @Test
        @DisplayName("성공: subject에 userId, claims에 사용자 정보 포함")
        void containsCorrectClaims() {
            // when
            String token = jwtTokenProvider.generateAccessToken(testUser);

            // then
            Claims claims = parseClaims(token);
            assertThat(claims.getSubject()).isEqualTo("1");
            assertThat(claims.get("kakaoId", Long.class)).isEqualTo(12345L);
            assertThat(claims.get("nickname", String.class)).isEqualTo("테스트유저");
            assertThat(claims.get("status", String.class)).isEqualTo("ACTIVE");
            assertThat(claims.get("role", String.class)).isEqualTo("ROLE_USER");
            assertThat(claims.get("type", String.class)).isEqualTo("access");
        }

        @Test
        @DisplayName("성공: 만료 시간이 설정된다")
        void hasExpiration() {
            // when
            String token = jwtTokenProvider.generateAccessToken(testUser);

            // then
            Claims claims = parseClaims(token);
            assertThat(claims.getExpiration()).isNotNull();
            assertThat(claims.getIssuedAt()).isNotNull();
            long diff = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
            assertThat(diff).isEqualTo(ACCESS_EXPIRATION);
        }
    }

    // =========================================================================
    // generateRefreshToken
    // =========================================================================

    @Nested
    @DisplayName("generateRefreshToken")
    class GenerateRefreshToken {

        @Test
        @DisplayName("성공: subject에 userId, type=refresh만 포함")
        void containsMinimalClaims() {
            // when
            String token = jwtTokenProvider.generateRefreshToken(testUser);

            // then
            Claims claims = parseClaims(token);
            assertThat(claims.getSubject()).isEqualTo("1");
            assertThat(claims.get("type", String.class)).isEqualTo("refresh");
            assertThat(claims.get("kakaoId")).isNull();
            assertThat(claims.get("nickname")).isNull();
        }

        @Test
        @DisplayName("성공: 만료 시간이 refresh 기간으로 설정된다")
        void hasRefreshExpiration() {
            // when
            String token = jwtTokenProvider.generateRefreshToken(testUser);

            // then
            Claims claims = parseClaims(token);
            long diff = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
            assertThat(diff).isEqualTo(REFRESH_EXPIRATION);
        }
    }
}
