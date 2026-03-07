package com.example.onlyone.global.exception;

/**
 * 모든 도메인 ErrorCode enum이 구현하는 공통 인터페이스.
 * <p>
 * 각 도메인 모듈은 이 인터페이스를 구현한 자체 enum을 정의한다.
 * 예: UserErrorCode, ClubErrorCode, ChatErrorCode 등
 * <p>
 * GlobalExceptionHandler와 CustomException은 이 인터페이스 타입으로 동작한다.
 */
public interface ErrorCode {

    int getStatus();

    String getCode();

    String getMessage();

    /**
     * enum의 name()을 반환. enum이 구현하므로 별도 구현 불필요.
     */
    String name();
}
