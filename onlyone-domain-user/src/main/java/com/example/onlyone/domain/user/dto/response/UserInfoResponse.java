package com.example.onlyone.domain.user.dto.response;

import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;

public record UserInfoResponse(
        Long userId,
        Long kakaoId,
        String nickname,
        Status status,
        String profileImage
) {
    public static UserInfoResponse from(User user) {
        return new UserInfoResponse(
                user.getUserId(),
                user.getKakaoId(),
                user.getNickname(),
                user.getStatus(),
                user.getProfileImage() != null ? user.getProfileImage() : ""
        );
    }
}
