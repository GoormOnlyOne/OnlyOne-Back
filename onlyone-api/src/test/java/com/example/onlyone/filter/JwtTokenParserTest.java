package com.example.onlyone.filter;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.global.filter.JwtTokenParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtTokenParser 단위 테스트")
class JwtTokenParserTest {

    private JwtTokenParser parser;
    private SecretKey signingKey;

    private static final String SECRET = "test-secret-key-that-is-at-least-32-chars-long!!";

    @BeforeEach
    void setUp() throws Exception {
        parser = new JwtTokenParser();
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

        // Reflectively set jwtSecret and call init()
        setField(parser, "jwtSecret", SECRET);
        invokeMethod(parser, "init");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== extractBearerToken ====================

    @Nested
    @DisplayName("extractBearerToken")
    class ExtractBearerToken {

        @Test
        @DisplayName("유효한 Bearer 헤더에서 토큰을 추출한다")
        void extractsTokenFromValidHeader() {
            assertThat(parser.extractBearerToken("Bearer my-token")).isEqualTo("my-token");
        }

        @Test
        @DisplayName("null 헤더이면 null을 반환한다")
        void returnsNullForNullHeader() {
            assertThat(parser.extractBearerToken(null)).isNull();
        }

        @Test
        @DisplayName("Bearer 접두사가 없으면 null을 반환한다")
        void returnsNullForNonBearerHeader() {
            assertThat(parser.extractBearerToken("Basic abc")).isNull();
        }

        @Test
        @DisplayName("빈 문자열이면 null을 반환한다")
        void returnsNullForEmptyHeader() {
            assertThat(parser.extractBearerToken("")).isNull();
        }
    }

    // ==================== setAuthentication ====================

    @Nested
    @DisplayName("setAuthentication")
    class SetAuthentication {

        @Test
        @DisplayName("SecurityContext에 인증 정보가 설정된다")
        void setsSecurityContext() {
            UserPrincipal principal = UserPrincipal.fromClaims("1", "10001", "ACTIVE", "ROLE_USER");

            parser.setAuthentication(principal);

            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull().isInstanceOf(UsernamePasswordAuthenticationToken.class);
            assertThat(auth.getPrincipal()).isEqualTo(principal);
            assertThat(auth.getAuthorities()).isNotEmpty();
        }
    }

    // ==================== parseToken ====================

    @Nested
    @DisplayName("parseToken")
    class ParseToken {

        @Test
        @DisplayName("유효한 토큰을 파싱하여 UserPrincipal을 반환한다")
        void parsesValidToken() {
            String token = buildToken("1", 10001L, "ACTIVE", "ROLE_USER");

            UserPrincipal result = parser.parseToken(token);

            assertThat(result.getUserId()).isEqualTo(1L);
            assertThat(result.getKakaoId()).isEqualTo(10001L);
            assertThat(result.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("만료된 토큰이면 JwtException이 발생한다")
        void throwsForExpiredToken() {
            String token = Jwts.builder()
                    .subject("1")
                    .claim("kakaoId", 10001L)
                    .claim("status", "ACTIVE")
                    .claim("role", "ROLE_USER")
                    .issuedAt(new Date(System.currentTimeMillis() - 20_000))
                    .expiration(new Date(System.currentTimeMillis() - 10_000))
                    .signWith(signingKey)
                    .compact();

            assertThatThrownBy(() -> parser.parseToken(token))
                    .isInstanceOf(io.jsonwebtoken.ExpiredJwtException.class);
        }

        @Test
        @DisplayName("subject가 누락되면 IllegalArgumentException이 발생한다")
        void throwsForMissingSubject() {
            String token = Jwts.builder()
                    .claim("kakaoId", 10001L)
                    .claim("status", "ACTIVE")
                    .claim("role", "ROLE_USER")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(signingKey)
                    .compact();

            assertThatThrownBy(() -> parser.parseToken(token))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("userId");
        }

        @Test
        @DisplayName("kakaoId가 누락되면 IllegalArgumentException이 발생한다")
        void throwsForMissingKakaoId() {
            String token = Jwts.builder()
                    .subject("1")
                    .claim("status", "ACTIVE")
                    .claim("role", "ROLE_USER")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(signingKey)
                    .compact();

            assertThatThrownBy(() -> parser.parseToken(token))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("kakaoId");
        }

        @Test
        @DisplayName("status가 누락되면 IllegalArgumentException이 발생한다")
        void throwsForMissingStatus() {
            String token = Jwts.builder()
                    .subject("1")
                    .claim("kakaoId", 10001L)
                    .claim("role", "ROLE_USER")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(signingKey)
                    .compact();

            assertThatThrownBy(() -> parser.parseToken(token))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("status");
        }

        @Test
        @DisplayName("role이 누락되면 IllegalArgumentException이 발생한다")
        void throwsForMissingRole() {
            String token = Jwts.builder()
                    .subject("1")
                    .claim("kakaoId", 10001L)
                    .claim("status", "ACTIVE")
                    .issuedAt(new Date())
                    .expiration(new Date(System.currentTimeMillis() + 60_000))
                    .signWith(signingKey)
                    .compact();

            assertThatThrownBy(() -> parser.parseToken(token))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("role");
        }
    }

    // ==================== init ====================

    @Nested
    @DisplayName("init")
    class Init {

        @Test
        @DisplayName("secret 길이가 32 미만이면 IllegalStateException이 발생한다")
        void throwsForShortSecret() throws Exception {
            JwtTokenParser shortParser = new JwtTokenParser();
            setField(shortParser, "jwtSecret", "short");

            assertThatThrownBy(() -> invokeMethod(shortParser, "init"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ==================== helpers ====================

    private String buildToken(String userId, Long kakaoId, String status, String role) {
        return Jwts.builder()
                .subject(userId)
                .claim("kakaoId", kakaoId)
                .claim("status", status)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(signingKey)
                .compact();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invokeMethod(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        try {
            method.invoke(target);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }
}
