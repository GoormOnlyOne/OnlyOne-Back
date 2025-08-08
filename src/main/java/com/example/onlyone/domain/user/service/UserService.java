package com.example.onlyone.domain.user.service;

import com.example.onlyone.domain.user.entity.Gender;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.repository.UserRepository;
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
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Log4j2
@Service
@RequiredArgsConstructor
@Transactional
public class UserService {
    private final UserRepository userRepository;
    
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
        return userRepository.findByKakaoId(kakaoId)
                .orElseThrow(() -> new CustomException(ErrorCode.KAKAO_API_ERROR));
    }

    public User getMemberById(Long memberId){
        return userRepository.findById(memberId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    /**
     * 카카오 로그인 처리: 기존 사용자 조회 또는 신규 사용자 생성
     * @return Map containing user and isNewUser flag
     */
    public Map<String, Object> processKakaoLogin(Map<String, Object> kakaoUserInfo) {
        Long kakaoId = Long.valueOf(kakaoUserInfo.get("id").toString());

        // 기존 사용자 조회
        Optional<User> existingUser = userRepository.findByKakaoId(kakaoId);

        Map<String, Object> result = new HashMap<>();

        if (existingUser.isPresent()) {
            // 기존 사용자
            result.put("user", existingUser.get());
            result.put("isNewUser", false);
        } else {
            // 신규 사용자 생성
            User newUser = User.builder()
                    .kakaoId(kakaoId)
                    .nickname("guest")
                    .birth(LocalDate.now())
                    .status(Status.ACTIVE)
                    .gender(Gender.MALE)
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
     * FCM 토큰 상태 확인
     */
    public boolean hasFcmToken(Long userId) {
        User user = getMemberById(userId);
        return user.hasFcmToken();
    }

    /**
     * FCM 토큰 업데이트 (중복 등록 방지)
     */
    public void updateFcmToken(Long userId, String fcmToken) {
        User user = getMemberById(userId);
        
        // 동일한 토큰 중복 등록 방지
        if (fcmToken.equals(user.getFcmToken())) {
            log.debug("FCM token already registered for user: {}", userId);
            return;
        }
        
        try {
            user.updateFcmToken(fcmToken);
            log.info("FCM token updated for user: {}", userId);
        } catch (IllegalArgumentException e) {
            log.error("FCM token validation failed for user: {}, error: {}", userId, e.getMessage());
            throw new CustomException(ErrorCode.FCM_TOKEN_INVALID);
        }
    }

    /**
     * FCM 토큰 삭제 (로그아웃 시)
     */
    public void clearFcmToken(Long userId) {
        User user = getMemberById(userId);
        user.clearFcmToken();
        
        log.info("FCM token cleared for user: {}", userId);
    }
}
