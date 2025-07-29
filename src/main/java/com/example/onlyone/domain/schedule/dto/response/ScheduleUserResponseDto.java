package com.example.onlyone.domain.schedule.dto.response;

import com.example.onlyone.domain.schedule.entity.ScheduleRole;
import com.example.onlyone.domain.schedule.entity.UserSchedule;
import com.example.onlyone.domain.user.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
@AllArgsConstructor
public class ScheduleUserResponseDto {
    private Long userId;
    private String nickname;
    private String profileImage;

    public static ScheduleUserResponseDto from(User user) {
        return ScheduleUserResponseDto.builder()
                .userId(user.getUserId())
                .nickname(user.getNickname())
                .profileImage(user.getProfileImage())
                .build();
    }
}
