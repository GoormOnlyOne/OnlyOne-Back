package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserClubRepository extends JpaRepository<UserClub,Long> {
    Optional<UserClub> findByUserAndClub(User user, Club club);

    int countByClub_ClubId(long clubId);

    List<UserClub> findByUserUserId(Long userId);

    @Query("SELECT DISTINCT uc.user.userId FROM UserClub uc WHERE uc.club.clubId IN :clubIds")
    List<Long> findUserIdByClubIds(@Param("clubIds") List<Long> clubIds);

    List<UserClub> findByUserUserIdIn(Collection<Long> userIds);
}
