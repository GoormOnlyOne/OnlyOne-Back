package com.example.onlyone.domain.user.controller;

import com.example.onlyone.domain.user.dto.request.SignupRequestDto;
import com.example.onlyone.domain.user.dto.response.LoginResponse;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.KakaoService;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.common.CommonResponse;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

import java.util.Map;

@Log4j2
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final KakaoService kakaoService;
    private final UserService userService;
    private final RedisTemplate<String, Object> redisTemplate;

    @PostMapping("/kakao/callback")
    public ResponseEntity<?> kakaoLogin(@RequestParam String code) {
        try {
            // 1. 인증 코드로 카카오 액세스 토큰 받기
            String kakaoAccessToken = kakaoService.getAccessToken(code);

            // 2. 카카오 액세스 토큰으로 사용자 정보 받기
            Map<String, Object> kakaoUserInfo = kakaoService.getUserInfo(kakaoAccessToken);

            // 3. 사용자 정보 저장 또는 업데이트
            Map<String, Object> loginResult = userService.processKakaoLogin(kakaoUserInfo, kakaoAccessToken);
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

            return ResponseEntity.ok(CommonResponse.success(response));
        } catch (CustomException e) {
            // CustomException은 그대로 재던지기 (탈퇴한 사용자 403 에러 포함)
            throw e;
        } catch (Exception e) {
            // 기타 예외는 502 에러로 처리
            throw new CustomException(ErrorCode.KAKAO_LOGIN_FAILED);
        }
    }

    @PostMapping("/signup")
    public ResponseEntity<?> signup(@Valid @RequestBody SignupRequestDto signupRequest) {
        userService.signup(signupRequest);
        return ResponseEntity.ok(CommonResponse.success(null));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout() {
        try {
            User currentUser = userService.getCurrentUser();
            
            // 저장된 카카오 액세스 토큰으로 카카오 연결끊기 호출 (완전한 로그아웃)
            if (currentUser.getKakaoAccessToken() != null) {
                kakaoService.unlink(currentUser.getKakaoAccessToken());
            }
            
            // 사용자의 카카오 토큰 제거
            userService.logoutUser();
            
            return ResponseEntity.ok(CommonResponse.success(null));
        } catch (Exception e) {
            // 로그아웃 실패해도 200 반환 (클라이언트 토큰은 삭제되어야 함)
            log.warn("로그아웃 처리 중 오류: {}", e.getMessage());
            return ResponseEntity.ok(CommonResponse.success(null));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        User currentUser = userService.getCurrentUser();

        Map<String, Object> userInfo = Map.of(
            "userId", currentUser.getUserId(),
            "kakaoId", currentUser.getKakaoId(),
            "nickname", currentUser.getNickname(),
            "status", currentUser.getStatus(),
            "profileImage", currentUser.getProfileImage() != null ? currentUser.getProfileImage() : ""
        );
        
        return ResponseEntity.ok(CommonResponse.success(userInfo));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<?> withdrawUser() {
        try {
            User currentUser = userService.getCurrentUser();
            
            // 저장된 카카오 액세스 토큰으로 카카오 연결끊기 호출 (완전한 세션 정리)
            if (currentUser.getKakaoAccessToken() != null) {
                kakaoService.unlink(currentUser.getKakaoAccessToken());
            }

            userService.withdrawUser();
            
            return ResponseEntity.ok(CommonResponse.success(null));
        } catch (Exception e) {
            // 카카오 연결 해제 실패해도 회원 탈퇴는 진행
            userService.withdrawUser();
            return ResponseEntity.ok(CommonResponse.success(null));
        }
    }
}
