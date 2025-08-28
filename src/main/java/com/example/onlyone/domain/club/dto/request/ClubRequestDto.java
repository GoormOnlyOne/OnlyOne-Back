package com.example.onlyone.domain.club.dto.request;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.interest.entity.Interest;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ClubRequestDto {
    @NotBlank
    @Size(max = 20, message = "모임명은 20자 이내여야 합니다.")
    private String name;
    @Min(value = 1, message = "정원은 1명 이상이어야 합니다.")
    @Max(value = 100, message = "정원은 100명 이하여야 합니다.")
    private int userLimit;
    @Size(max = 50, message = "모임 설명은 50자 이내여야 합니다.")
    @NotBlank
    private String description;
    private String clubImage;
    @NotBlank
    private String city;
    @NotBlank
    private String district;
    @NotBlank
    private String category;

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
