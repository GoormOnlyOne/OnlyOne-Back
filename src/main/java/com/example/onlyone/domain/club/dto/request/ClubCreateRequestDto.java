package com.example.onlyone.domain.club.dto.request;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.interest.entity.Interest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class ClubCreateRequestDto {
    @NotBlank
    @Size(max = 20)
    private String name;
    @NotNull
    private int userLimit;
    @Size(max = 50)
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
