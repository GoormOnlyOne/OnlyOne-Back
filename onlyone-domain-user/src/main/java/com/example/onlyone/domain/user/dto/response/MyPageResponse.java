package com.example.onlyone.domain.user.dto.response;

import com.example.onlyone.domain.user.entity.Gender;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

public record MyPageResponse(
    String nickname,
    @JsonProperty("profile_image") String profileImage,
    String city,
    String district,
    LocalDate birth,
    Gender gender,
    @JsonProperty("interests_list") List<String> interestsList,
    Long balance
) {
}
