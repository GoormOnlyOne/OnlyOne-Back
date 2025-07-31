package com.example.onlyone.domain.payment.entity;

public enum Method {
    CARD,                  // 카드
    VIRTUAL_ACCOUNT,       // 가상계좌
    SIMPLE_PAYMENT,        // 간편결제 (예: 카카오페이, 네이버페이 등)
    MOBILE_PAYMENT,        // 휴대폰 결제
    ACCOUNT_TRANSFER,      // 계좌이체
    CULTURE_GIFT_CERT,     // 문화상품권
    BOOK_GIFT_CERT,        // 도서문화상품권
    GAME_GIFT_CERT         // 게임문화상품권
}
