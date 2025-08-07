package com.example.onlyone.domain.club.dto.response;

import com.example.onlyone.domain.chat.entity.ChatRoom;
import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.interest.entity.Category;
import com.example.onlyone.domain.interest.entity.Interest;
import com.example.onlyone.domain.schedule.dto.response.ScheduleResponseDto;
import com.example.onlyone.domain.schedule.entity.Schedule;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
@AllArgsConstructor
public class ClubDetailResponseDto {
    private Long clubId;

    private String name;

    private int userCount;

    private String description;

    private String clubImage;

    private String district;

    private String category;

    private ClubRole clubRole;

    public static ClubDetailResponseDto from(Club club, int userCount, ClubRole clubRole) {
        return ClubDetailResponseDto.builder()
                .clubId(club.getClubId())
                .name(club.getName())
                .userCount(userCount)
                .description(club.getDescription())
                .clubImage(club.getClubImage())
                .district(club.getDistrict())
                .category(club.getInterest().getCategory().getKoreanName())
                .clubRole(clubRole)
                .build();
    }
}
