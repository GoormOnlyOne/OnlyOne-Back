package com.example.onlyone.domain.interest.exception;

import com.example.onlyone.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum InterestErrorCode implements ErrorCode {

    INVALID_CATEGORY(400, "INTEREST_400_1", "유효하지 않은 카데고리입니다."),
    INTEREST_NOT_FOUND(404, "INTEREST_404_1", "관심사를 찾을 수 없습니다.");

    private final int status;
    private final String code;
    private final String message;
}
