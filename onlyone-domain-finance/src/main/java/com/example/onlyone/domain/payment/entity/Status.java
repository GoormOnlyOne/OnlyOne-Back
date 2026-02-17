package com.example.onlyone.domain.payment.entity;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Arrays;

public enum Status {
    READY,
    IN_PROGRESS,
    WAITING_FOR_DEPOSIT,
    DONE,
    CANCELED,
    PARTIAL_CANCELED,
    ABORTED,
    EXPIRED;

    private static final Map<String, Status> BY_NAME =
            Arrays.stream(values()).collect(Collectors.toMap(s -> s.name().toUpperCase(), Function.identity()));

    public static Status from(String value) {
        if (value == null || value.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
        }
        Status s = BY_NAME.get(value.toUpperCase());
        if (s != null) return s;
        throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
    }
}
