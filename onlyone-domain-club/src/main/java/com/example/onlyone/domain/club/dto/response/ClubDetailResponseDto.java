package com.example.onlyone.domain.club.dto.response;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.ClubRole;
import com.example.onlyone.domain.interest.entity.Category;
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

    private String city;

    private String district;

    private Category category;

    private ClubRole clubRole;

    private int userLimit;

    public static ClubDetailResponseDto from(Club club, int userCount, ClubRole clubRole) {
        return ClubDetailResponseDto.builder()
                .clubId(club.getClubId())
                .name(club.getName())
                .userCount(userCount)
                .description(club.getDescription())
                .clubImage(club.getClubImage())
                .city(club.getCity())
                .district(club.getDistrict())
                .category(club.getInterest().getCategory())
                .clubRole(clubRole)
                .userLimit(club.getUserLimit())
                .build();
    }
}
