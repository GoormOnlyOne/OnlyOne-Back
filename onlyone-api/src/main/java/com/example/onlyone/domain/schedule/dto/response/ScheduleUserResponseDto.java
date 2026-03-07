package com.example.onlyone.domain.schedule.dto.response;

import com.example.onlyone.domain.user.entity.User;

public record ScheduleUserResponseDto(
    Long userId,
    String nickname,
    String profileImage
) {
    public static ScheduleUserResponseDto from(User user) {
        return new ScheduleUserResponseDto(
                user.getUserId(),
                user.getNickname(),
                user.getProfileImage()
        );
    }
}
