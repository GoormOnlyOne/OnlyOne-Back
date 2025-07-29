package com.example.onlyone.domain.notification.entity;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum Type {
    CHAT("chat"),
    SETTLEMENT("settlement"),
    LIKE("like"),
    COMMENT("comment");

    private final String code;

    Type(String code) {
        this.code = code;
    }

    /**
     * JSON 직렬화 시 enum → String 으로 내려줄 때 사용
     */
    @JsonValue
    public String getCode() {
        return code;
    }

    /**
     * 입력받은 문자열(code) 을 enum 으로 변환할 때 사용
     * 대소문자 구분 없이 매칭하며, 실패 시 CustomException 발생
     */
    @JsonCreator
    public static Type from(String code) {
        if (code == null) {
            throw new CustomException(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);
        }
        for (Type t : values()) {
            if (t.code.equalsIgnoreCase(code.trim())) {
                return t;
            }
        }
        throw new CustomException(ErrorCode.NOTIFICATION_TYPE_NOT_FOUND);
    }
}