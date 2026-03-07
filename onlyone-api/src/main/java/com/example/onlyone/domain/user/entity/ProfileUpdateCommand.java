package com.example.onlyone.domain.user.entity;

import java.time.LocalDate;

public record ProfileUpdateCommand(
        String city,
        String district,
        String profileImage,
        String nickname,
        Gender gender,
        LocalDate birth
) {}
