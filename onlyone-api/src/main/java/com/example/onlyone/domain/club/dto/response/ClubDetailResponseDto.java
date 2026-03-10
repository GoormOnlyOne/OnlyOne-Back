package com.example.onlyone.domain.club.dto.response;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.interest.entity.Category;

public record ClubDetailResponseDto(
        Long clubId,
        String name,
        int userCount,
        String description,
        String clubImage,
        String city,
        String district,
        Category category,
        ClubRole clubRole,
        int userLimit
) {
    public static ClubDetailResponseDto from(Club club, int userCount, ClubRole clubRole) {
        return new ClubDetailResponseDto(
                club.getClubId(),
                club.getName(),
                userCount,
                club.getDescription(),
                club.getClubImage(),
                club.getCity(),
                club.getDistrict(),
                club.getInterest().getCategory(),
                clubRole,
                club.getUserLimit()
        );
    }
}
