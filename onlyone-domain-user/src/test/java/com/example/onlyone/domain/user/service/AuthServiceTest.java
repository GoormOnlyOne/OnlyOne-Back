package com.example.onlyone.domain.user.service;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.domain.user.dto.response.LoginResponse;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService 단위 테스트")
class AuthServiceTest {

    @InjectMocks
    private AuthService authService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private KakaoService kakaoService;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private User testUser;
    private UserPrincipal testPrincipal;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .userId(1L)
                .kakaoId(12345L)
                .nickname("테스트유저")
                .status(Status.ACTIVE)
                .gender(Gender.MALE)
                .birth(LocalDate.of(1995, 1, 1))
                .build();

        testPrincipal = UserPrincipal.from(testUser);
    }

    private void setAuthentication(Object principal) {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, null);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    // =========================================================================
    // kakaoLogin
    // =========================================================================

    @Nested
    @DisplayName("kakaoLogin")
    class KakaoLogin {

        @Test
        @DisplayName("성공: 기존 ACTIVE 사용자 → isNewUser=false")
        void existingActiveUser() {
            // given
            when(kakaoService.getAccessToken("code")).thenReturn("kakao-token");
            when(kakaoService.getUserInfo("kakao-token")).thenReturn(Map.of("id", 12345L));
            when(userRepository.findByKakaoId(12345L)).thenReturn(Optional.of(testUser));
            when(userRepository.save(any(User.class))).thenReturn(testUser);
            when(jwtTokenProvider.generateTokenPair(testUser))
                    .thenReturn(new JwtTokenProvider.TokenPair("access", "refresh"));

            // when
            LoginResponse result = authService.kakaoLogin("code");

            // then
            assertThat(result.accessToken()).isEqualTo("access");
            assertThat(result.refreshToken()).isEqualTo("refresh");
            assertThat(result.isNewUser()).isFalse();
        }

        @Test
        @DisplayName("성공: 기존 GUEST 사용자 → isNewUser=true")
        void existingGuestUser() {
            // given
            User guestUser = User.builder()
                    .userId(2L).kakaoId(99999L).nickname("guest")
                    .status(Status.GUEST).gender(Gender.MALE).birth(LocalDate.now())
                    .build();

            when(kakaoService.getAccessToken("code")).thenReturn("kakao-token");
            when(kakaoService.getUserInfo("kakao-token")).thenReturn(Map.of("id", 99999L));
            when(userRepository.findByKakaoId(99999L)).thenReturn(Optional.of(guestUser));
            when(userRepository.save(any(User.class))).thenReturn(guestUser);
            when(jwtTokenProvider.generateTokenPair(guestUser))
                    .thenReturn(new JwtTokenProvider.TokenPair("access", "refresh"));

            // when
            LoginResponse result = authService.kakaoLogin("code");

            // then
            assertThat(result.isNewUser()).isTrue();
        }

        @Test
        @DisplayName("성공: 신규 사용자 생성 → isNewUser=true")
        void newUser() {
            // given
            when(kakaoService.getAccessToken("code")).thenReturn("kakao-token");
            when(kakaoService.getUserInfo("kakao-token")).thenReturn(Map.of("id", 77777L));
            when(userRepository.findByKakaoId(77777L)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User saved = invocation.getArgument(0);
                return saved;
            });
            when(jwtTokenProvider.generateTokenPair(any(User.class)))
                    .thenReturn(new JwtTokenProvider.TokenPair("access", "refresh"));

            // when
            LoginResponse result = authService.kakaoLogin("code");

            // then
            assertThat(result.isNewUser()).isTrue();
            assertThat(result.accessToken()).isEqualTo("access");
        }

        @Test
        @DisplayName("실패: INACTIVE 사용자 → USER_WITHDRAWN")
        void inactiveUser() {
            // given
            User inactiveUser = User.builder()
                    .userId(3L).kakaoId(11111L).nickname("withdrawn")
                    .status(Status.INACTIVE).gender(Gender.FEMALE).birth(LocalDate.now())
                    .build();

            when(kakaoService.getAccessToken("code")).thenReturn("kakao-token");
            when(kakaoService.getUserInfo("kakao-token")).thenReturn(Map.of("id", 11111L));
            when(userRepository.findByKakaoId(11111L)).thenReturn(Optional.of(inactiveUser));

            // when & then
            assertThatThrownBy(() -> authService.kakaoLogin("code"))
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.USER_WITHDRAWN);
        }
    }

    // =========================================================================
    // getCurrentUser
    // =========================================================================

    @Nested
    @DisplayName("getCurrentUser")
    class GetCurrentUser {

        @Test
        @DisplayName("성공: 인증된 사용자의 User 엔티티를 반환한다")
        void success() {
            // given
            setAuthentication(testPrincipal);
            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

            // when
            User result = authService.getCurrentUser();

            // then
            assertThat(result).isEqualTo(testUser);
            assertThat(result.getUserId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("실패: DB에 사용자가 없으면 USER_NOT_FOUND")
        void failUserNotFound() {
            // given
            setAuthentication(testPrincipal);
            when(userRepository.findById(1L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> authService.getCurrentUser())
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("실패: 인증 정보가 없으면 UNAUTHORIZED")
        void failNoAuthentication() {
            // given
            SecurityContextHolder.clearContext();

            // when & then
            assertThatThrownBy(() -> authService.getCurrentUser())
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("실패: Principal이 UserPrincipal이 아니면 UNAUTHORIZED")
        void failInvalidPrincipal() {
            // given
            setAuthentication("invalid-principal");

            // when & then
            assertThatThrownBy(() -> authService.getCurrentUser())
                    .isInstanceOf(CustomException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }
    }

    // =========================================================================
    // getCurrentUserId
    // =========================================================================

    @Nested
    @DisplayName("getCurrentUserId")
    class GetCurrentUserId {

        @Test
        @DisplayName("성공: DB 조회 없이 userId를 반환한다")
        void success() {
            // given
            setAuthentication(testPrincipal);

            // when
            Long result = authService.getCurrentUserId();

            // then
            assertThat(result).isEqualTo(1L);
        }
    }
}
