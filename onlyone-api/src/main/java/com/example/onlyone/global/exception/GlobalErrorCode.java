package com.example.onlyone.global.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 공통/글로벌 에러코드 + 여러 도메인에서 공유되는 범용 에러코드.
 */
@Getter
@AllArgsConstructor
public enum GlobalErrorCode implements ErrorCode {

    // Global
    INVALID_INPUT_VALUE(400, "GLOBAL_400_1", "입력값이 유효하지 않습니다."),
    METHOD_NOT_ALLOWED(405, "GLOBAL_405_1", "지원하지 않는 HTTP 메서드입니다."),
    BAD_REQUEST(400, "GLOBAL_400_3", "필수 파라미터가 누락되었습니다."),
    INTERNAL_SERVER_ERROR(500, "GLOBAL_500_1", "서버 내부 오류가 발생했습니다."),
    EXTERNAL_API_ERROR(503, "GLOBAL_503_1", "외부 API 서버 호출 중 오류가 발생했습니다."),
    UNAUTHORIZED(401, "GLOBAL_401_1", "인증되지 않은 사용자입니다."),
    NO_PERMISSION(403, "GLOBAL_403_1", "권한이 없습니다."),
    RESOURCE_NOT_FOUND(404, "GLOBAL_404_1", "요청한 리소스를 찾을 수 없습니다."),
    ALREADY_JOINED(409, "GLOBAL_409_1", "이미 참여 중입니다."),

    // Database
    DATABASE_CONNECTION_ERROR(503, "DB_503_1", "데이터베이스 연결 중 오류가 발생했습니다."),
    DATABASE_OPERATION_FAILED(500, "NOTIFY_500_3", "데이터베이스 작업 중 오류가 발생했습니다.");

    private final int status;
    private final String code;
    private final String message;
}
