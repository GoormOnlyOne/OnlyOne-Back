package com.example.onlyone.domain.user.service;

import com.example.onlyone.domain.user.dto.UserPrincipal;
import com.example.onlyone.domain.user.dto.response.LoginResponse;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * 인증 서비스.
 * SecurityContext 기반 사용자 조회 + 카카오 로그인 오케스트레이션을 담당한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final KakaoService kakaoService;
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * 카카오 로그인 전체 플로우: 토큰 발급 → 사용자 정보 조회 → 회원 처리 → JWT 생성
     */
    @Transactional
    public LoginResponse kakaoLogin(String code) {
        String kakaoAccessToken = kakaoService.getAccessToken(code);
        Map<String, Object> kakaoUserInfo = kakaoService.getUserInfo(kakaoAccessToken);

        Long kakaoId = Long.valueOf(kakaoUserInfo.get("id").toString());
        User user = findOrCreateUser(kakaoId, kakaoAccessToken);

        JwtTokenProvider.TokenPair tokens = jwtTokenProvider.generateTokenPair(user);
        boolean isNewUser = Status.GUEST.equals(user.getStatus());

        log.info("카카오 로그인 성공: userId={}, isNewUser={}", user.getUserId(), isNewUser);
        return new LoginResponse(tokens.accessToken(), tokens.refreshToken(), isNewUser);
    }

    /**
     * 현재 인증된 사용자 엔티티 조회
     */
    public User getCurrentUser() {
        UserPrincipal principal = getAuthenticatedPrincipal();
        return userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * 현재 인증된 사용자 ID 조회 (DB 조회 없음)
     */
    public Long getCurrentUserId() {
        return getAuthenticatedPrincipal().getUserId();
    }

    /**
     * Refresh Token으로 새 Access Token 발급
     */
    @Transactional(readOnly = true)
    public LoginResponse refreshAccessToken(String refreshToken) {
        Long userId;
        try {
            userId = jwtTokenProvider.parseRefreshToken(refreshToken);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        if (Status.INACTIVE.equals(user.getStatus())) {
            throw new CustomException(ErrorCode.USER_WITHDRAWN);
        }

        String newAccessToken = jwtTokenProvider.generateAccessToken(user);
        return new LoginResponse(newAccessToken, refreshToken, false);
    }

    // ========== PRIVATE HELPERS ==========

    private User findOrCreateUser(Long kakaoId, String kakaoAccessToken) {
        Optional<User> existingUser = userRepository.findByKakaoId(kakaoId);

        if (existingUser.isEmpty()) {
            return createNewKakaoUser(kakaoId, kakaoAccessToken);
        }

        User user = existingUser.get();
        if (Status.INACTIVE.equals(user.getStatus())) {
            throw new CustomException(ErrorCode.USER_WITHDRAWN);
        }

        user.updateKakaoAccessToken(kakaoAccessToken);
        userRepository.save(user);
        return user;
    }

    private User createNewKakaoUser(Long kakaoId, String kakaoAccessToken) {
        User newUser = User.builder()
                .kakaoId(kakaoId)
                .nickname("guest")
                .birth(LocalDate.now())
                .status(Status.GUEST)
                .gender(Gender.MALE)
                .kakaoAccessToken(kakaoAccessToken)
                .build();

        User saved = userRepository.save(newUser);
        log.info("신규 유저 생성: userId={}, kakaoId={}", saved.getUserId(), kakaoId);
        return saved;
    }

    private UserPrincipal getAuthenticatedPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof UserPrincipal userPrincipal)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        return userPrincipal;
    }
}
