package com.example.onlyone.domain.user.service;

import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.interest.repository.InterestRepository;
import com.example.onlyone.domain.user.dto.request.ProfileUpdateRequestDto;
import com.example.onlyone.domain.user.dto.request.SignupRequestDto;
import com.example.onlyone.domain.user.dto.response.MyPageResponse;
import com.example.onlyone.domain.user.dto.response.ProfileResponseDto;
import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.entity.UserInterest;
import com.example.onlyone.domain.user.repository.UserInterestRepository;
import com.example.onlyone.domain.user.repository.UserRepository;
import com.example.onlyone.domain.wallet.entity.Wallet;
import com.example.onlyone.domain.wallet.repository.WalletRepository;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;

import javax.crypto.SecretKey;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@Service
@RequiredArgsConstructor
@Transactional
public class UserService {
    private final UserRepository userRepository;
    private final UserInterestRepository userInterestRepository;
    private final InterestRepository interestRepository;
    private final WalletRepository walletRepository;
    
    @Value("${jwt.secret}")
    private String jwtSecret;
    
    @Value("${jwt.access-expiration}")
    private long accessTokenExpiration;
    
    @Value("${jwt.refresh-expiration}")
    private long refreshTokenExpiration;


    @Transactional(readOnly = true)
    public User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        Long kakaoId = 0L;
        try {
            kakaoId = Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            throw new  CustomException(ErrorCode.UNAUTHORIZED);
        }
        
        Optional<User> userOpt = userRepository.findByKakaoId(kakaoId);
        if (userOpt.isEmpty()) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }
        
        User user = userOpt.get();
        return user;
    }

    public User getMemberById(Long memberId){
        return userRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * 카카오 로그인 처리: 기존 사용자 조회 또는 신규 사용자 생성
     * @param kakaoUserInfo 카카오 사용자 정보
     * @param kakaoAccessToken 카카오 액세스 토큰
     * @return Map containing user and isNewUser flag
     */
    public Map<String, Object> processKakaoLogin(Map<String, Object> kakaoUserInfo, String kakaoAccessToken) {
        Long kakaoId = Long.valueOf(kakaoUserInfo.get("id").toString());

        // 기존 사용자 조회
        Optional<User> existingUser = userRepository.findByKakaoId(kakaoId);

        Map<String, Object> result = new HashMap<>();

        if (existingUser.isPresent()) {
            User user = existingUser.get();
            
            // 탈퇴한 사용자(INACTIVE)는 재로그인 금지
            if (Status.INACTIVE.name().equals(user.getStatus())) {
                throw new CustomException(ErrorCode.USER_WITHDRAWN);
            }
            
            // 카카오 액세스 토큰 업데이트
            user.updateKakaoAccessToken(kakaoAccessToken);
            userRepository.save(user);
            
            // 기존 사용자 - GUEST 상태면 회원가입 필요, ACTIVE면 회원가입 완료
            result.put("user", user);
            result.put("isNewUser", Status.GUEST.name().equals(user.getStatus()));
        } else {
            // 신규 사용자 생성 (회원가입 미완료 상태)
            User newUser = User.builder()
                    .kakaoId(kakaoId)
                    .nickname("guest")
                    .birth(LocalDate.now())
                    .status(Status.GUEST.name())
                    .gender(Gender.MALE)
                    .kakaoAccessToken(kakaoAccessToken)
                    .build();

            User savedUser = userRepository.save(newUser);
            result.put("user", savedUser);
            result.put("isNewUser", true);
        }

        return result;
    }

    /**
     * JWT Access Token 생성
     */
    public String generateAccessToken(User user) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + accessTokenExpiration);
        
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        
        return Jwts.builder()
                .subject(user.getKakaoId().toString())
                .claim("kakaoId", user.getKakaoId())
                .claim("nickname", user.getNickname())
                .claim("type", "access")
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }

    /**
     * JWT Refresh Token 생성
     */
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

    /**
     * 토큰 쌍 생성 (Access + Refresh)
     */
    public Map<String, String> generateTokenPair(User user) {
        Map<String, String> tokens = new HashMap<>();
        tokens.put("accessToken", generateAccessToken(user));
        tokens.put("refreshToken", generateRefreshToken(user));
        return tokens;
    }

    /**
     * 회원가입 처리 - 기존 사용자의 추가 정보 업데이트
     */
    public void signup(SignupRequestDto signupRequest) {
        // 현재 인증된 사용자 조회
        User user = getCurrentUser();

        // 사용자 추가 정보(지역, 프로필, 닉네임, 성별, 생년월일) 업데이트
        user.update(
                signupRequest.getCity(),
                signupRequest.getDistrict(),
                signupRequest.getProfileImage(),
                signupRequest.getNickname(),
                signupRequest.getGender(),
                signupRequest.getBirth()
        );

        // 회원가입 완료 - GUEST → ACTIVE 상태로 변경
        user.completeSignup();

        // 사용자 관심사 저장
        List<String> categories = signupRequest.getCategories();
        for (String categoryName : categories) {
            Interest interest = interestRepository.findByCategory(Category.from(categoryName))
                    .orElseThrow(() -> new CustomException(ErrorCode.INTEREST_NOT_FOUND));
            
            UserInterest userInterest = UserInterest.builder()
                    .user(user)
                    .interest(interest)
                    .build();
            
            userInterestRepository.save(userInterest);
        }

        // 사용자 지갑 생성 및 웰컴 포인트 100000원 지급
        Wallet wallet = Wallet.builder()
                .user(user)
                .balance(100000)
                .build();
        
        walletRepository.save(wallet);
    }

    /**
     * 로그아웃 처리 - 카카오 액세스 토큰 제거
     */
    public void logoutUser() {
        User user = getCurrentUser();
        if (user.getKakaoAccessToken() != null) {
            user.clearKakaoAccessToken();
            userRepository.save(user);
        }
    }

    /**
     * 회원 탈퇴 처리 - 사용자 상태를 INACTIVE로 변경하고 카카오 연결 해제
     */
    public void withdrawUser() {
        User user = getCurrentUser();
        user.withdraw();
        userRepository.save(user);
    }

    /**
     * 마이페이지 정보 조회
     */
    @Transactional
    public MyPageResponse getMyPage() {
        User user = getCurrentUser();
        
        // 사용자 관심사 카테고리 조회
        List<Category> categories = userInterestRepository.findCategoriesByUserId(user.getUserId());
        List<String> interestsList = categories.stream()
                .map(Category::name)
                .map(String::toLowerCase)
                .collect(Collectors.toList());
        
        // 사용자 지갑 정보 조회
        Optional<Wallet> walletOpt = walletRepository.findByUser(user);
        Integer balance = walletOpt.map(Wallet::getBalance).orElse(0);
        
        return MyPageResponse.builder()
                .nickname(user.getNickname())
                .profileImage(user.getProfileImage())
                .city(user.getCity())
                .district(user.getDistrict())
                .birth(user.getBirth())
                .gender(user.getGender())
                .interestsList(interestsList)
                .balance(balance)
                .build();
    }

    /**
     * 사용자 프로필 정보 조회
     */
    @Transactional(readOnly = true)
    public ProfileResponseDto getUserProfile() {
        User user = getCurrentUser();
        
        // 사용자 관심사 카테고리 조회
        List<Category> categories = userInterestRepository.findCategoriesByUserId(user.getUserId());
        List<String> interestsList = categories.stream()
                .map(Category::name)
                .map(String::toLowerCase)
                .collect(Collectors.toList());
        
        return ProfileResponseDto.builder()
                .userId(user.getUserId())
                .nickname(user.getNickname())
                .birth(user.getBirth())
                .profileImage(user.getProfileImage())
                .gender(user.getGender())
                .city(user.getCity())
                .district(user.getDistrict())
                .interestsList(interestsList)
                .build();
    }

    /**
     * 사용자 프로필 정보 업데이트
     */
    @Transactional
    public void updateUserProfile(ProfileUpdateRequestDto request) {
        User user = getCurrentUser();
        
        // 사용자 기본 정보 업데이트
        user.update(
                request.getCity(),
                request.getDistrict(),
                request.getProfileImage(),
                request.getNickname(),
                request.getGender(),
                request.getBirth()
        );
        
        // 기존 관심사 삭제
        userInterestRepository.deleteByUserId(user.getUserId());
        
        // 새로운 관심사 저장
        for (String categoryName : request.getInterestsList()) {
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
