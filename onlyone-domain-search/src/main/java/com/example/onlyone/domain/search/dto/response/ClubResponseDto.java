package com.example.onlyone.domain.search.dto.response;

import com.example.onlyone.domain.club.entity.Club;

public record ClubResponseDto(
    Long clubId,
    String name,
    String description,
    String interest,
    String district,
    Long memberCount,
    String image,
    boolean isJoined
) {
    public static ClubResponseDto from(Club club, Long memberCount, boolean isJoined) {
        return new ClubResponseDto(
                club.getClubId(),
                club.getName(),
                club.getDescription(),
                club.getInterest().getCategory().getKoreanName(),
                club.getDistrict(),
                memberCount,
                club.getClubImage(),
                isJoined
        );
    }
}
