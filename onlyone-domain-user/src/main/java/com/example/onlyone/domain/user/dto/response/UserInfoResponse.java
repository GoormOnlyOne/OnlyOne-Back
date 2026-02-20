package com.example.onlyone.domain.user.dto.response;

import com.example.onlyone.domain.user.entity.Status;
import com.example.onlyone.domain.user.entity.User;

public record UserInfoResponse(
        Long userId,
        String nickname,
        Status status,
        String profileImage
) {
    public static UserInfoResponse from(User user) {
        return new UserInfoResponse(
                user.getUserId(),
                user.getNickname(),
                user.getStatus(),
                user.getProfileImage() != null ? user.getProfileImage() : ""
        );
    }
}
