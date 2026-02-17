package com.example.onlyone.domain.user.dto.response;

public record LoginResponse(
    String accessToken,
    String refreshToken,
    boolean isNewUser
) {
}
