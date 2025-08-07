package com.example.onlyone.domain.interest.entity;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;

public enum Category {
    CULTURE("문화"),
    EXERCISE("운동"),
    TRAVEL("여행"),
    MUSIC("음악"),
    CRAFT("공예"),
    SOCIAL("사교"),
    LANGUAGE("외국어"),
    FINANCE("재테크");

    private final String koreanName;

    Category(String koreanName) {
        this.koreanName = koreanName;
    }

    public String getKoreanName() {
        return koreanName;
    }

    public static Category from(String value) {
        try {
            return Category.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_CATEGORY);
        }
    }
}

