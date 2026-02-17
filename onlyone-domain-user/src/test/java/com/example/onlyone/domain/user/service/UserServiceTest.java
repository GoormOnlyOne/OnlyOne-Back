package com.example.onlyone.domain.user.service;

import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.domain.user.dto.request.ProfileUpdateRequestDto;
import com.example.onlyone.domain.user.dto.request.SignupRequestDto;
import com.example.onlyone.domain.user.dto.response.MyPageResponse;
import com.example.onlyone.domain.user.dto.response.ProfileResponseDto;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Role;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.entity.UserInterest;
import com.example.onlyone.domain.user.repository.UserInterestRepository;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 단위 테스트")
class UserServiceTest {

    @InjectMocks
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserInterestRepository userInterestRepository;

    @Mock
    private InterestRepository interestRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .userId(1L)
                .kakaoId(123456L)
                .nickname("testUser")
                .birth(LocalDate.of(1995, 5, 15))
                .status(Status.ACTIVE)
                .profileImage("https://example.com/profile.jpg")
                .gender(Gender.MALE)
                .city("서울")
                .district("강남구")
                .kakaoAccessToken("kakao-access-token-123")
                .role(Role.ROLE_USER)
                .build();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(User user) {
        UserPrincipal principal = UserPrincipal.from(user);
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(principal);
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    // =========================================================================
    // getCurrentUser
    // =========================================================================

    @Nested
    @DisplayName("getCurrentUser 테스트")
    class GetCurrentUserTest {

        @Test
        @DisplayName("성공 - 인증된 사용자가 존재하면 User 반환")
        void getCurrentUser_성공() {
            // given
            setSecurityContext(testUser);
            when(userRepository.findById(testUser.getUserId())).thenReturn(Optional.of(testUser));

            // when
            User result = userService.getCurrentUser();

            // then
            assertThat(result).isNotNull();
            assertThat(result.getUserId()).isEqualTo(testUser.getUserId());
            assertThat(result.getNickname()).isEqualTo("testUser");
            verify(userRepository).findById(testUser.getUserId());
        }

        @Test
        @DisplayName("실패 - Authentication이 null이면 UNAUTHORIZED 예외")
        void getCurrentUser_Authentication이_null이면_UNAUTHORIZED() {
            // given
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(null);
            SecurityContextHolder.setContext(ctx);

            // when & then
            assertThatThrownBy(() -> userService.getCurrentUser())
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("실패 - isAuthenticated가 false이면 UNAUTHORIZED 예외")
        void getCurrentUser_isAuthenticated_false면_UNAUTHORIZED() {
            // given
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(false);
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(auth);
            SecurityContextHolder.setContext(ctx);

            // when & then
            assertThatThrownBy(() -> userService.getCurrentUser())
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("실패 - Principal이 UserPrincipal이 아니면 UNAUTHORIZED 예외")
        void getCurrentUser_Principal이_UserPrincipal이_아니면_UNAUTHORIZED() {
            // given
            Authentication auth = mock(Authentication.class);
            when(auth.isAuthenticated()).thenReturn(true);
            when(auth.getPrincipal()).thenReturn("anonymousUser");
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(auth);
            SecurityContextHolder.setContext(ctx);

            // when & then
            assertThatThrownBy(() -> userService.getCurrentUser())
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED);
        }

        @Test
        @DisplayName("실패 - 유저가 DB에 없으면 USER_NOT_FOUND 예외")
        void getCurrentUser_유저가_없으면_USER_NOT_FOUND() {
            // given
            setSecurityContext(testUser);
            when(userRepository.findById(testUser.getUserId())).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> userService.getCurrentUser())
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    // =========================================================================
    // getCurrentUserId
    // =========================================================================

    @Nested
    @DisplayName("getCurrentUserId 테스트")
    class GetCurrentUserIdTest {

        @Test
        @DisplayName("성공 - 인증된 사용자의 userId 반환")
        void getCurrentUserId_성공() {
            // given
            setSecurityContext(testUser);

            // when
            Long userId = userService.getCurrentUserId();

            // then
            assertThat(userId).isEqualTo(testUser.getUserId());
        }
    }

    // =========================================================================
    // getMemberById
    // =========================================================================

    @Nested
    @DisplayName("getMemberById 테스트")
    class GetMemberByIdTest {

        @Test
        @DisplayName("성공 - memberId로 User 조회")
        void getMemberById_성공() {
            // given
            when(userRepository.findById(1L)).thenReturn(Optional.of(testUser));

            // when
            User result = userService.getMemberById(1L);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getUserId()).isEqualTo(1L);
            assertThat(result.getNickname()).isEqualTo("testUser");
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 memberId이면 USER_NOT_FOUND 예외")
        void getMemberById_USER_NOT_FOUND() {
            // given
            when(userRepository.findById(999L)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> userService.getMemberById(999L))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    // =========================================================================
    // processKakaoLogin
    // =========================================================================

    @Nested
    @DisplayName("processKakaoLogin 테스트")
    class ProcessKakaoLoginTest {

        private Map<String, Object> createKakaoUserInfo(Long kakaoId) {
            Map<String, Object> kakaoUserInfo = new HashMap<>();
            kakaoUserInfo.put("id", kakaoId);
            return kakaoUserInfo;
        }

        @Test
        @DisplayName("성공 - 기존 ACTIVE 사용자는 isNewUser=false 반환")
        void processKakaoLogin_기존_ACTIVE_사용자() {
            // given
            Map<String, Object> kakaoUserInfo = createKakaoUserInfo(123456L);
            String kakaoAccessToken = "new-kakao-token";
            when(userRepository.findByKakaoId(123456L)).thenReturn(Optional.of(testUser));
            when(userRepository.save(any(User.class))).thenReturn(testUser);

            // when
            Map<String, Object> result = userService.processKakaoLogin(kakaoUserInfo, kakaoAccessToken);

            // then
            assertThat(result.get("user")).isEqualTo(testUser);
            assertThat(result.get("isNewUser")).isEqualTo(false);
            verify(userRepository).save(testUser);
        }

        @Test
        @DisplayName("성공 - 기존 GUEST 사용자는 isNewUser=true 반환")
        void processKakaoLogin_기존_GUEST_사용자() {
            // given
            User guestUser = User.builder()
                    .userId(2L)
                    .kakaoId(654321L)
                    .nickname("guest")
                    .birth(LocalDate.now())
                    .status(Status.GUEST)
                    .gender(Gender.MALE)
                    .build();
            Map<String, Object> kakaoUserInfo = createKakaoUserInfo(654321L);
            String kakaoAccessToken = "kakao-token";
            when(userRepository.findByKakaoId(654321L)).thenReturn(Optional.of(guestUser));
            when(userRepository.save(any(User.class))).thenReturn(guestUser);

            // when
            Map<String, Object> result = userService.processKakaoLogin(kakaoUserInfo, kakaoAccessToken);

            // then
            assertThat(result.get("user")).isEqualTo(guestUser);
            assertThat(result.get("isNewUser")).isEqualTo(true);
            verify(userRepository).save(guestUser);
        }

        @Test
        @DisplayName("실패 - INACTIVE 사용자이면 USER_WITHDRAWN 예외")
        void processKakaoLogin_INACTIVE_사용자_예외() {
            // given
            User inactiveUser = User.builder()
                    .userId(3L)
                    .kakaoId(111111L)
                    .nickname("withdrawn")
                    .birth(LocalDate.now())
                    .status(Status.INACTIVE)
                    .gender(Gender.FEMALE)
                    .build();
            Map<String, Object> kakaoUserInfo = createKakaoUserInfo(111111L);
            when(userRepository.findByKakaoId(111111L)).thenReturn(Optional.of(inactiveUser));

            // when & then
            assertThatThrownBy(() -> userService.processKakaoLogin(kakaoUserInfo, "token"))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_WITHDRAWN);
        }

        @Test
        @DisplayName("성공 - 신규 사용자 생성 시 isNewUser=true 반환")
        void processKakaoLogin_신규_사용자_생성() {
            // given
            Map<String, Object> kakaoUserInfo = createKakaoUserInfo(999999L);
            String kakaoAccessToken = "new-user-token";
            when(userRepository.findByKakaoId(999999L)).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
                User savedUser = invocation.getArgument(0);
                ReflectionTestUtils.setField(savedUser, "userId", 10L);
                return savedUser;
            });

            // when
            Map<String, Object> result = userService.processKakaoLogin(kakaoUserInfo, kakaoAccessToken);

            // then
            User createdUser = (User) result.get("user");
            assertThat(result.get("isNewUser")).isEqualTo(true);
            assertThat(createdUser).isNotNull();
            assertThat(createdUser.getKakaoId()).isEqualTo(999999L);
            assertThat(createdUser.getStatus()).isEqualTo(Status.GUEST);
            assertThat(createdUser.getNickname()).isEqualTo("guest");
            verify(userRepository).save(any(User.class));
        }
    }

    // =========================================================================
    // JWT Token 생성 테스트
    // =========================================================================

    @Nested
    @DisplayName("JWT 토큰 생성 테스트")
    class JwtTokenTest {

        @BeforeEach
        void setJwtFields() {
            ReflectionTestUtils.setField(userService, "jwtSecret",
                    "a]very-long-secret-key-that-is-at-least-64-characters-long-for-hs512-algorithm!!");
            ReflectionTestUtils.setField(userService, "accessTokenExpiration", 3600000L);
            ReflectionTestUtils.setField(userService, "refreshTokenExpiration", 86400000L);
        }

        @Test
        @DisplayName("성공 - AccessToken 생성")
        void generateAccessToken_토큰_생성_성공() {
            // when
            String accessToken = userService.generateAccessToken(testUser);

            // then
            assertThat(accessToken).isNotNull();
            assertThat(accessToken).isNotBlank();
            assertThat(accessToken.split("\\.")).hasSize(3); // JWT: header.payload.signature
        }

        @Test
        @DisplayName("성공 - RefreshToken 생성")
        void generateRefreshToken_토큰_생성_성공() {
            // when
            String refreshToken = userService.generateRefreshToken(testUser);

            // then
            assertThat(refreshToken).isNotNull();
            assertThat(refreshToken).isNotBlank();
            assertThat(refreshToken.split("\\.")).hasSize(3);
        }

        @Test
        @DisplayName("성공 - generateTokenPair로 두 토큰 모두 반환")
        void generateTokenPair_두_토큰_반환() {
            // when
            Map<String, String> tokens = userService.generateTokenPair(testUser);

            // then
            assertThat(tokens).isNotNull();
            assertThat(tokens).containsKeys("accessToken", "refreshToken");
            assertThat(tokens.get("accessToken")).isNotBlank();
            assertThat(tokens.get("refreshToken")).isNotBlank();
            assertThat(tokens.get("accessToken")).isNotEqualTo(tokens.get("refreshToken"));
        }
    }

    // =========================================================================
    // signup
    // =========================================================================

    @Nested
    @DisplayName("signup 테스트")
    class SignupTest {

        @Test
        @DisplayName("성공 - 회원가입 처리 및 관심사 저장")
        void signup_성공() {
            // given
            User guestUser = User.builder()
                    .userId(1L)
                    .kakaoId(123456L)
                    .nickname("guest")
                    .birth(LocalDate.now())
                    .status(Status.GUEST)
                    .gender(Gender.MALE)
                    .kakaoAccessToken("kakao-token")
                    .build();

            setSecurityContext(guestUser);
            when(userRepository.findById(guestUser.getUserId())).thenReturn(Optional.of(guestUser));

            SignupRequestDto dto = new SignupRequestDto(
                    "newNickname",
                    LocalDate.of(1995, 5, 15),
                    Gender.MALE,
                    "https://example.com/img.jpg",
                    "서울",
                    "강남구",
                    List.of("CULTURE", "EXERCISE"));

            Interest cultureInterest = Interest.builder()
                    .interestId(1L)
                    .category(Category.CULTURE)
                    .build();
            Interest exerciseInterest = Interest.builder()
                    .interestId(2L)
                    .category(Category.EXERCISE)
                    .build();

            when(interestRepository.findByCategory(Category.CULTURE))
                    .thenReturn(Optional.of(cultureInterest));
            when(interestRepository.findByCategory(Category.EXERCISE))
                    .thenReturn(Optional.of(exerciseInterest));
            when(userInterestRepository.save(any(UserInterest.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // when
            userService.signup(dto);

            // then
            assertThat(guestUser.getNickname()).isEqualTo("newNickname");
            assertThat(guestUser.getCity()).isEqualTo("서울");
            assertThat(guestUser.getDistrict()).isEqualTo("강남구");
            assertThat(guestUser.getStatus()).isEqualTo(Status.ACTIVE);
            verify(interestRepository).findByCategory(Category.CULTURE);
            verify(interestRepository).findByCategory(Category.EXERCISE);
            verify(userInterestRepository, times(2)).save(any(UserInterest.class));
        }

        @Test
        @DisplayName("실패 - 관심사가 존재하지 않으면 INTEREST_NOT_FOUND 예외")
        void signup_관심사_없으면_INTEREST_NOT_FOUND() {
            // given
            setSecurityContext(testUser);
            when(userRepository.findById(testUser.getUserId())).thenReturn(Optional.of(testUser));

            SignupRequestDto dto = new SignupRequestDto(
                    "newUser",
                    LocalDate.of(1995, 5, 15),
                    Gender.MALE,
                    "https://example.com/img.jpg",
                    "서울",
                    "강남구",
                    List.of("CULTURE"));

            when(interestRepository.findByCategory(Category.CULTURE)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> userService.signup(dto))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INTEREST_NOT_FOUND);
        }
    }

    // =========================================================================
    // logoutUser
    // =========================================================================

    @Nested
    @DisplayName("logoutUser 테스트")
    class LogoutUserTest {

        @Test
        @DisplayName("성공 - 카카오 토큰이 있으면 제거 후 저장")
        void logoutUser_토큰_있을_때_제거() {
            // given
            setSecurityContext(testUser);
            when(userRepository.findById(testUser.getUserId())).thenReturn(Optional.of(testUser));
            when(userRepository.save(any(User.class))).thenReturn(testUser);

            // when
            userService.logoutUser();

            // then
            assertThat(testUser.getKakaoAccessToken()).isNull();
            verify(userRepository).save(testUser);
        }

        @Test
        @DisplayName("성공 - 카카오 토큰이 없으면 save 호출 안 함")
        void logoutUser_토큰_없을_때_저장_안_함() {
            // given
            User userWithoutToken = User.builder()
                    .userId(2L)
                    .kakaoId(654321L)
                    .nickname("noTokenUser")
                    .birth(LocalDate.of(1990, 1, 1))
                    .status(Status.ACTIVE)
                    .gender(Gender.FEMALE)
                    .kakaoAccessToken(null)
                    .build();

            setSecurityContext(userWithoutToken);
            when(userRepository.findById(userWithoutToken.getUserId()))
                    .thenReturn(Optional.of(userWithoutToken));

            // when
            userService.logoutUser();

            // then
            verify(userRepository, never()).save(any(User.class));
        }
    }

    // =========================================================================
    // withdrawUser
    // =========================================================================

    @Nested
    @DisplayName("withdrawUser 테스트")
    class WithdrawUserTest {

        @Test
        @DisplayName("성공 - 사용자 상태를 INACTIVE로 변경")
        void withdrawUser_성공() {
            // given
            setSecurityContext(testUser);
            when(userRepository.findById(testUser.getUserId())).thenReturn(Optional.of(testUser));
            when(userRepository.save(any(User.class))).thenReturn(testUser);

            // when
            userService.withdrawUser();

            // then
            assertThat(testUser.getStatus()).isEqualTo(Status.INACTIVE);
            assertThat(testUser.getKakaoAccessToken()).isNull();
            verify(userRepository).save(testUser);
        }
    }

    // =========================================================================
    // getMyPage
    // =========================================================================

    @Nested
    @DisplayName("getMyPage 테스트")
    class GetMyPageTest {

        @Test
        @DisplayName("성공 - 마이페이지 정보 반환")
        void getMyPage_성공() {
            // given
            setSecurityContext(testUser);
            when(userRepository.findById(testUser.getUserId())).thenReturn(Optional.of(testUser));
            when(userInterestRepository.findCategoriesByUserId(testUser.getUserId()))
                    .thenReturn(List.of(Category.CULTURE, Category.EXERCISE));

            // when
            MyPageResponse response = userService.getMyPage();

            // then
            assertThat(response).isNotNull();
            assertThat(response.nickname()).isEqualTo("testUser");
            assertThat(response.profileImage()).isEqualTo("https://example.com/profile.jpg");
            assertThat(response.city()).isEqualTo("서울");
            assertThat(response.district()).isEqualTo("강남구");
            assertThat(response.birth()).isEqualTo(LocalDate.of(1995, 5, 15));
            assertThat(response.gender()).isEqualTo(Gender.MALE);
            assertThat(response.interestsList()).containsExactly("culture", "exercise");
            assertThat(response.balance()).isEqualTo(0L);
            verify(userInterestRepository).findCategoriesByUserId(testUser.getUserId());
        }
    }

    // =========================================================================
    // getUserProfile
    // =========================================================================

    @Nested
    @DisplayName("getUserProfile 테스트")
    class GetUserProfileTest {

        @Test
        @DisplayName("성공 - 프로필 정보 반환")
        void getUserProfile_성공() {
            // given
            setSecurityContext(testUser);
            when(userRepository.findById(testUser.getUserId())).thenReturn(Optional.of(testUser));
            when(userInterestRepository.findCategoriesByUserId(testUser.getUserId()))
                    .thenReturn(List.of(Category.MUSIC, Category.TRAVEL));

            // when
            ProfileResponseDto response = userService.getUserProfile();

            // then
            assertThat(response).isNotNull();
            assertThat(response.userId()).isEqualTo(testUser.getUserId());
            assertThat(response.nickname()).isEqualTo("testUser");
            assertThat(response.birth()).isEqualTo(LocalDate.of(1995, 5, 15));
            assertThat(response.profileImage()).isEqualTo("https://example.com/profile.jpg");
            assertThat(response.gender()).isEqualTo(Gender.MALE);
            assertThat(response.city()).isEqualTo("서울");
            assertThat(response.district()).isEqualTo("강남구");
            assertThat(response.interestsList()).containsExactly("music", "travel");
            verify(userInterestRepository).findCategoriesByUserId(testUser.getUserId());
        }
    }

    // =========================================================================
    // updateUserProfile
    // =========================================================================

    @Nested
    @DisplayName("updateUserProfile 테스트")
    class UpdateUserProfileTest {

        @Test
        @DisplayName("성공 - 프로필 업데이트 및 관심사 재설정")
        void updateUserProfile_성공() {
            // given
            setSecurityContext(testUser);
            when(userRepository.findById(testUser.getUserId())).thenReturn(Optional.of(testUser));

            ProfileUpdateRequestDto request = new ProfileUpdateRequestDto(
                    "updatedNick",
                    LocalDate.of(1990, 12, 25),
                    "https://example.com/new-profile.jpg",
                    Gender.FEMALE,
                    "부산",
                    "해운대구",
                    List.of("SOCIAL", "LANGUAGE"));

            Interest socialInterest = Interest.builder()
                    .interestId(5L)
                    .category(Category.SOCIAL)
                    .build();
            Interest languageInterest = Interest.builder()
                    .interestId(6L)
                    .category(Category.LANGUAGE)
                    .build();

            when(interestRepository.findByCategory(Category.SOCIAL))
                    .thenReturn(Optional.of(socialInterest));
            when(interestRepository.findByCategory(Category.LANGUAGE))
                    .thenReturn(Optional.of(languageInterest));
            when(userInterestRepository.save(any(UserInterest.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // when
            userService.updateUserProfile(request);

            // then
            assertThat(testUser.getNickname()).isEqualTo("updatedNick");
            assertThat(testUser.getBirth()).isEqualTo(LocalDate.of(1990, 12, 25));
            assertThat(testUser.getProfileImage()).isEqualTo("https://example.com/new-profile.jpg");
            assertThat(testUser.getGender()).isEqualTo(Gender.FEMALE);
            assertThat(testUser.getCity()).isEqualTo("부산");
            assertThat(testUser.getDistrict()).isEqualTo("해운대구");
            verify(userInterestRepository).deleteByUserId(testUser.getUserId());
            verify(interestRepository).findByCategory(Category.SOCIAL);
            verify(interestRepository).findByCategory(Category.LANGUAGE);
            verify(userInterestRepository, times(2)).save(any(UserInterest.class));
        }

        @Test
        @DisplayName("실패 - 관심사가 존재하지 않으면 INTEREST_NOT_FOUND 예외")
        void updateUserProfile_관심사_없으면_INTEREST_NOT_FOUND() {
            // given
            setSecurityContext(testUser);
            when(userRepository.findById(testUser.getUserId())).thenReturn(Optional.of(testUser));

            ProfileUpdateRequestDto request = new ProfileUpdateRequestDto(
                    "updatedNick",
                    LocalDate.of(1990, 12, 25),
                    "https://example.com/new-profile.jpg",
                    Gender.FEMALE,
                    "부산",
                    "해운대구",
                    List.of("FINANCE"));

            when(interestRepository.findByCategory(Category.FINANCE)).thenReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> userService.updateUserProfile(request))
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INTEREST_NOT_FOUND);
        }
    }
}
