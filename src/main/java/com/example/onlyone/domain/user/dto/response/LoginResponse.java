package com.example.onlyone.domain.user.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginResponse { ;
    private String accessToken;
    private String refreshToken;
    private boolean isNewUser;
}