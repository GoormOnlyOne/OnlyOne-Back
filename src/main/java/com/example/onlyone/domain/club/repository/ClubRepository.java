package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.feed.entity.Feed;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClubRepository extends JpaRepository<Club,Long> {
}