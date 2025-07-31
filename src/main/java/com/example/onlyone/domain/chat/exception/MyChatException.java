package com.example.onlyone.domain.chat.exception;

/**
 * 채팅 도메인 전용 예외 클래스
 */
public class MyChatException extends RuntimeException {

    public MyChatException(String message) {
        super(message);
    }

    public MyChatException(String message, Throwable cause) {
        super(message, cause);
    }
}
