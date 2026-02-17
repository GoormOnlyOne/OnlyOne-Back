package com.example.onlyone.domain.user.dto.response;

import com.example.onlyone.domain.user.entity.Gender;

import java.time.LocalDate;
import java.util.List;

public record ProfileResponseDto(
    Long userId,
    String nickname,
    LocalDate birth,
    String profileImage,
    Gender gender,
    String city,
    String district,
    List<String> interestsList
) {
}
