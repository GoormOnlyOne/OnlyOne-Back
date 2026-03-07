package com.example.onlyone.domain.user.controller;

import com.example.onlyone.domain.user.entity.Role;
import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;
import com.example.onlyone.domain.user.service.JwtTokenProvider;
import com.example.onlyone.global.common.CommonResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 테스트용 JWT 발급 컨트롤러 (로컬/테스트 환경 전용)
 */
@Profile({"local", "test"})
@RestController
@RequestMapping("/test/auth")
@RequiredArgsConstructor
public class TestAuthController {

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * k6 부하 테스트용 JWT 토큰 생성
     * GET /test/auth/token?userId=1
     */
    @GetMapping("/token")
    public CommonResponse<Map<String, String>> generateTestToken(@RequestParam Long userId) {
        User testUser = User.builder()
                .userId(userId)
                .kakaoId(10000000L + userId)
                .nickname("testuser" + userId)
                .status(Status.ACTIVE)
                .role(Role.ROLE_USER)
                .build();

        String accessToken = jwtTokenProvider.generateAccessToken(testUser);

        Map<String, String> response = new HashMap<>();
        response.put("accessToken", accessToken);
        response.put("userId", userId.toString());
        response.put("kakaoId", testUser.getKakaoId().toString());

        return CommonResponse.success(response);
    }
}
