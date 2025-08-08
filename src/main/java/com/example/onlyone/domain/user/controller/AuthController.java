package com.example.onlyone.domain.user.controller;

import com.example.onlyone.domain.user.dto.request.SignupRequestDto;
import com.example.onlyone.domain.user.dto.response.LoginResponse;
import com.example.onlyone.domain.user.dto.response.MyPageResponse;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.KakaoService;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.common.CommonResponse;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Log4j2
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final KakaoService kakaoService;
    private final UserService userService;
    private final RedisTemplate<String, Object> redisTemplate;
    private static final long REFRESH_TOKEN_EXPIRE_TIME = 1000 * 60 * 60 * 25 * 14L; // 2주

    @PostMapping("/kakao/callback")
    public ResponseEntity<?> kakaoLogin(@RequestParam String code) {
        log.info("▶ kakaoLogin POST 진입, code={}", code);
        try {
            // 1. 인증 코드로 카카오 액세스 토큰 받기
            String kakaoAccessToken = kakaoService.getAccessToken(code);

            // 2. 카카오 액세스 토큰으로 사용자 정보 받기
            Map<String, Object> kakaoUserInfo = kakaoService.getUserInfo(kakaoAccessToken);

            // 3. 사용자 정보 저장 또는 업데이트
            Map<String, Object> loginResult = userService.processKakaoLogin(kakaoUserInfo);
            User user = (User) loginResult.get("user");
            boolean isNewUser = (boolean) loginResult.get("isNewUser");

            // 4. JWT 토큰 생성 (Access + Refresh)
            Map<String, String> tokens = userService.generateTokenPair(user);

            // 5. refreshToken Redis에 저장 (VITE_API_BASE_URL local 시, 주석)
            // redisTemplate.opsForValue()
            //         .set(user.getUserId().toString(), tokens.get("refreshToken"), Duration.ofMillis(REFRESH_TOKEN_EXPIRE_TIME));

            // 6. 응답 데이터
            LoginResponse response = new LoginResponse(
                    tokens.get("accessToken"),
                    tokens.get("refreshToken"),
                    isNewUser
            );

            log.info("카카오 로그인 성공 - userId: {}, isNewUser: {}", user.getUserId(), isNewUser);

            return ResponseEntity.ok(CommonResponse.success(response));
        } catch (Exception e) {
            log.info("카카오 로그인 오류 발생: {}", e);
            throw new CustomException(ErrorCode.KAKAO_LOGIN_FAILED);
        }
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequestDto signupRequest) {
        try {
            User updatedUser = userService.signup(signupRequest);
            log.info("회원가입 완료 - userId: {}, nickname: {}", updatedUser.getUserId(), updatedUser.getNickname());
            
            Map<String, Object> response = new HashMap<>();
            response.put("userId", updatedUser.getUserId());
            response.put("nickname", updatedUser.getNickname());
            response.put("message", "회원가입이 완료되었습니다.");
            
            return ResponseEntity.ok(CommonResponse.success(response));
        } catch (Exception e) {
            log.error("회원가입 실패: {}", e.getMessage(), e);
            throw new CustomException(ErrorCode.SIGNUP_FAILED);
        }
    }
}
