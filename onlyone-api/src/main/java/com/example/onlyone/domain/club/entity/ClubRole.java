package com.example.onlyone.domain.club.entity;

import com.example.onlyone.domain.club.exception.ClubErrorCode;
import com.example.onlyone.global.exception.CustomException;

public enum ClubRole {
    LEADER,
    MEMBER,
    GUEST;

    public static ClubRole from(String value) {
        try {
            return ClubRole.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ClubErrorCode.INVALID_ROLE);
        }
    }
}


