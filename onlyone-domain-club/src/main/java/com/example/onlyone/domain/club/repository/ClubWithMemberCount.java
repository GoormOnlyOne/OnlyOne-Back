package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;

public record ClubWithMemberCount(Club club, Long memberCount) {}
