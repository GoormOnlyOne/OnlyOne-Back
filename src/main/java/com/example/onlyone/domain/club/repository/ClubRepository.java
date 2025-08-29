package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClubRepository extends JpaRepository<Club, Long>, ClubRepositoryCustom {
    Club findByClubId(long l);
}