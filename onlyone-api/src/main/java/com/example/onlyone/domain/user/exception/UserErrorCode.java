package com.example.onlyone.domain.user.exception;

import com.example.onlyone.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserErrorCode implements ErrorCode {

    USER_NOT_FOUND(404, "USER_404_1", "유저를 찾을 수 없습니다."),
    USER_WITHDRAWN(403, "USER_403_1", "탈퇴한 사용자입니다."),
    ALREADY_SIGNED_UP(409, "USER_409_1", "이미 가입이 완료된 사용자입니다."),
    INVALID_REFRESH_TOKEN(401, "USER_401_2", "유효하지 않거나 만료된 Refresh Token입니다."),
    KAKAO_AUTH_FAILED(401, "USER_401_1", "카카오 인가 코드가 유효하지 않습니다."),
    KAKAO_LOGIN_FAILED(502, "USER_502_1", "카카오 로그인 처리 중 오류가 발생했습니다."),
    KAKAO_API_ERROR(502, "USER_502_2", "카카오 API 응답에 실패했습니다.");

    private final int status;
    private final String code;
    private final String message;
}
