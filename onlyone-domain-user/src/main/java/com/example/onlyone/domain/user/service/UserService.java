package com.example.onlyone.domain.user.service;

import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.user.dto.request.ProfileUpdateRequestDto;
import com.example.onlyone.domain.user.dto.request.SignupRequestDto;
import com.example.onlyone.domain.user.dto.response.MyPageResponse;
import com.example.onlyone.domain.user.dto.response.ProfileResponseDto;
import com.example.onlyone.domain.user.entity.ProfileUpdateCommand;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.entity.UserInterest;
import com.example.onlyone.domain.user.repository.UserInterestRepository;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final UserInterestRepository userInterestRepository;
    private final InterestRepository interestRepository;
    private final AuthService authService;
    private final KakaoService kakaoService;

    @Transactional(readOnly = true)
    public User getCurrentUser() {
        return authService.getCurrentUser();
    }

    @Transactional(readOnly = true)
    public Long getCurrentUserId() {
        return authService.getCurrentUserId();
    }

    @Transactional(readOnly = true)
    public User getMemberById(Long memberId) {
        return userRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * 회원가입 처리 - 기존 사용자의 추가 정보 업데이트
     */
    @Transactional
    public void signup(SignupRequestDto signupRequest) {
        User user = authService.getCurrentUser();

        user.updateProfile(new ProfileUpdateCommand(
                signupRequest.city(),
                signupRequest.district(),
                signupRequest.profileImage(),
                signupRequest.nickname(),
                signupRequest.gender(),
                signupRequest.birth()
        ));

        user.completeSignup();
        saveUserInterests(user, signupRequest.categories());
        log.info("회원가입 완료: userId={}", user.getUserId());
    }

    /**
     * 로그아웃 처리 - 카카오 연결 해제 + 토큰 제거
     */
    @Transactional
    public void logoutUser() {
        User user = authService.getCurrentUser();
        tryUnlinkKakao(user);

        if (user.getKakaoAccessToken() != null) {
            user.clearKakaoAccessToken();
            userRepository.save(user);
        }
    }

    /**
     * 회원 탈퇴 처리 - 카카오 연결 해제 + 상태 INACTIVE
     */
    @Transactional
    public void withdrawUser() {
        User user = authService.getCurrentUser();
        tryUnlinkKakao(user);
        user.withdraw();
        userRepository.save(user);
        log.info("회원 탈퇴: userId={}", user.getUserId());
    }

    /**
     * 마이페이지 정보 조회
     */
    @Transactional(readOnly = true)
    public MyPageResponse getMyPage() {
        User user = authService.getCurrentUser();
        List<String> interestsList = resolveUserInterestNames(user.getUserId());

        Long balance = 0L;  // 임시: API 모듈에서 처리

        return new MyPageResponse(
                user.getNickname(),
                user.getProfileImage(),
                user.getCity(),
                user.getDistrict(),
                user.getBirth(),
                user.getGender(),
                interestsList,
                balance
        );
    }

    /**
     * 사용자 프로필 정보 조회
     */
    @Transactional(readOnly = true)
    public ProfileResponseDto getUserProfile() {
        User user = authService.getCurrentUser();
        List<String> interestsList = resolveUserInterestNames(user.getUserId());

        return new ProfileResponseDto(
                user.getUserId(),
                user.getNickname(),
                user.getBirth(),
                user.getProfileImage(),
                user.getGender(),
                user.getCity(),
                user.getDistrict(),
                interestsList
        );
    }

    /**
     * 사용자 프로필 정보 업데이트
     */
    @Transactional
    public void updateUserProfile(ProfileUpdateRequestDto request) {
        User user = authService.getCurrentUser();

        user.updateProfile(new ProfileUpdateCommand(
                request.city(),
                request.district(),
                request.profileImage(),
                request.nickname(),
                request.gender(),
                request.birth()
        ));

        userInterestRepository.deleteByUserId(user.getUserId());
        saveUserInterests(user, request.interestsList());
        log.info("프로필 수정: userId={}", user.getUserId());
    }

    // ========== PRIVATE HELPERS ==========

    private void tryUnlinkKakao(User user) {
        if (user.getKakaoAccessToken() == null) return;
        try {
            kakaoService.unlink(user.getKakaoAccessToken());
        } catch (Exception e) {
            log.warn("카카오 연결 해제 실패: userId={}, error={}", user.getUserId(), e.getMessage());
        }
    }

    private List<String> resolveUserInterestNames(Long userId) {
        return userInterestRepository.findCategoriesByUserId(userId).stream()
                .map(Category::name)
                .map(String::toLowerCase)
                .collect(Collectors.toList());
    }

    private void saveUserInterests(User user, List<String> categoryNames) {
        for (String categoryName : categoryNames) {
            Interest interest = interestRepository.findByCategory(Category.from(categoryName))
                    .orElseThrow(() -> new CustomException(ErrorCode.INTEREST_NOT_FOUND));

            UserInterest userInterest = UserInterest.builder()
                    .user(user)
                    .interest(interest)
                    .build();

            userInterestRepository.save(userInterest);
        }
    }
}
