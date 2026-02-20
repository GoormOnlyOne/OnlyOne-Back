package com.example.onlyone.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;

public record RefreshTokenRequest(
        @NotBlank(message = "Refresh token은 필수입니다.")
        String refreshToken
) {
}
