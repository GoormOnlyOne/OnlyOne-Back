package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserClubRepository extends JpaRepository<UserClub,Long> {
    Optional<UserClub> findByUserAndClub(User user, Club club);

    int countByClub_ClubId(long clubId);
}
