package com.example.onlyone.global.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    // Member
    MEMBER_NOT_FOUND(404, "MEMBER_404_1", "멤버를 찾을 수 없습니다.");

    private final int status;
    private final String code;
    private final String message;
}
