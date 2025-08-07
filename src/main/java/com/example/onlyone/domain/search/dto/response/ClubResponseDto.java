package com.example.onlyone.domain.search.dto.response;

import com.example.onlyone.domain.club.entity.Club;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
@AllArgsConstructor
public class ClubResponseDto {
    private Long clubId;
    private String name;
    private String description;
    private String interest;
    private String district;
    private Long memberCount;
    private String image;

    public static ClubResponseDto from(Club club, Long memberCount) {
        return ClubResponseDto.builder()
                .clubId(club.getClubId())
                .name(club.getName())
                .description(club.getDescription())
                .interest(club.getInterest().getCategory().toString())
                .district(club.getDistrict())
                .memberCount(memberCount)
                .image(club.getClubImage())
                .build();
    }

}
