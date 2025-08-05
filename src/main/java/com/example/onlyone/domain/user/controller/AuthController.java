package com.example.onlyone.domain.user.controller;

import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.KakaoService;
import com.example.onlyone.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "http://localhost:5173")
@RequiredArgsConstructor
public class AuthController {
    
    private final KakaoService kakaoService;
    private final UserService userService;

    @GetMapping("/kakao/callback")
    public Map<String, Object> kakaoLogin(@RequestParam String code) {
        try {
            // 1. 인증 코드로 카카오 액세스 토큰 받기
            String kakaoAccessToken = kakaoService.getAccessToken(code);

            // 2. 카카오 액세스 토큰으로 사용자 정보 받기
            Map<String, Object> kakaoUserInfo = kakaoService.getUserInfo(kakaoAccessToken);

            // 3. 사용자 정보 저장 또는 업데이트
            User user = userService.processKakaoLogin(kakaoUserInfo);

            // 4. JWT 토큰 생성 (Access + Refresh)
            Map<String, String> tokens = userService.generateTokenPair(user);

            // 5. 응답 데이터 구성
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("accessToken", tokens.get("accessToken"));
            response.put("refreshToken", tokens.get("refreshToken"));

            return response;
        } catch (Exception e) {
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "카카오 로그인 처리 중 오류가 발생했습니다: " + e.getMessage());
            return errorResponse;
        }
    }
}
