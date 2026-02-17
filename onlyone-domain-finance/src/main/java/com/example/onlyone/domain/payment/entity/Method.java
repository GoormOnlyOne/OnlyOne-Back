package com.example.onlyone.domain.payment.entity;

import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Arrays;

public enum Method {
    CARD("카드"),
    VIRTUAL_ACCOUNT("가상계좌"),
    SIMPLE_PAYMENT("간편결제"),
    MOBILE_PAYMENT("휴대폰 결제"),
    ACCOUNT_TRANSFER("계좌이체"),
    CULTURE_GIFT_CERT("문화상품권"),
    BOOK_GIFT_CERT("도서문화상품권"),
    GAME_GIFT_CERT("게임문화상품권");

    private final String korean;

    private static final Map<String, Method> BY_KOREAN =
            Arrays.stream(values()).collect(Collectors.toMap(Method::getKorean, Function.identity()));
    private static final Map<String, Method> BY_NAME =
            Arrays.stream(values()).collect(Collectors.toMap(m -> m.name().toUpperCase(), Function.identity()));

    Method(String korean) {
        this.korean = korean;
    }

    public String getKorean() {
        return korean;
    }

    public static Method from(String value) {
        if (value == null || value.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
        }
        Method m = BY_NAME.get(value.toUpperCase());
        if (m != null) return m;
        m = BY_KOREAN.get(value);
        if (m != null) return m;
        throw new CustomException(ErrorCode.INVALID_PAYMENT_INFO);
    }
}
