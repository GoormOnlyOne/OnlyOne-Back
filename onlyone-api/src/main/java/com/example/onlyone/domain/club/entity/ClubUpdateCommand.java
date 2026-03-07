package com.example.onlyone.domain.club.entity;

import com.example.onlyone.domain.interest.entity.Interest;

public record ClubUpdateCommand(
        String name,
        int userLimit,
        String description,
        String clubImage,
        String city,
        String district,
        Interest interest
) {}
