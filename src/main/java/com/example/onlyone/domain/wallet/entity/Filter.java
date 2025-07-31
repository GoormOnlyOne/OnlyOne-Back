package com.example.onlyone.domain.wallet.entity;

import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonCreator;

public enum Filter {
    CHARGE,
    TRANSACTION,
    ALL;

    public static Filter from(String value) {
        try {
            return Filter.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_FILTER);
        }
    }
}
