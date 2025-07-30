package com.example.onlyone.domain.interest.entity;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;

public enum Category {
    CULTURE,
    EXERCISE,
    TRAVEL,
    MUSIC,
    CRAFT,
    SOCIAL,
    LANGUAGE,
    FINANCE;

    public static Category from(String value) {
        try {
            return Category.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_CATEGORY);
        }
    }
}

