package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;
import org.hibernate.annotations.Imported;

@Imported
public record ClubWithMemberCount(Club club, Long memberCount) {}
