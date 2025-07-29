package com.example.onlyone.domain.club.entity;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;

public enum ClubRole {
    LEADER,
    MEMBER;

    public static ClubRole from(String value) {
        try {
            return ClubRole.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_ROLE);
        }
    }
}


