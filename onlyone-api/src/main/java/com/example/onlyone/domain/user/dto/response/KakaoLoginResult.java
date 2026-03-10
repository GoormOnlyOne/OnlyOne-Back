package com.example.onlyone.domain.user.dto.response;

import com.example.onlyone.domain.user.entity.User;

/**
 * 카카오 로그인 처리 결과.
 * UserService.processKakaoLogin의 반환 타입으로 사용되며,
 * Map<String, Object> 대신 타입 안전한 결과를 제공한다.
 */
public record KakaoLoginResult(
        User user,
        boolean isNewUser
) {
}
