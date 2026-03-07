package com.example.onlyone.domain.club.dto.request;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.interest.entity.Interest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClubRequestDto(
        @NotBlank
        @Size(max = 20, message = "모임명은 20자 이내여야 합니다.")
        String name,
        @Min(value = 1, message = "정원은 1명 이상이어야 합니다.")
        @Max(value = 100, message = "정원은 100명 이하여야 합니다.")
        int userLimit,
        @Size(max = 50, message = "모임 설명은 50자 이내여야 합니다.")
        @NotBlank
        String description,
        String clubImage,
        @NotBlank
        String city,
        @NotBlank
        String district,
        @NotBlank
        String category
) {
    public Club toEntity(Interest interest) {
        return Club.builder()
                .name(name)
                .userLimit(userLimit)
                .description(description)
                .clubImage(clubImage)
                .city(city)
                .district(district)
                .interest(interest)
                .build();
    }
}
