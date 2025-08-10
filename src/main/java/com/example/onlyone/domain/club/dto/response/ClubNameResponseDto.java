package com.example.onlyone.domain.club.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
@AllArgsConstructor
public class ClubNameResponseDto {
    private Long clubId;

    private String name;
}
