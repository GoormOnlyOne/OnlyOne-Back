package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.club.entity.UserClub;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.cache.annotation.Cacheable;
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

    @Query("SELECT uc.club.clubId FROM UserClub uc WHERE uc.user.userId = :userId")
    List<Long> findByClubIdsByUserId(Long userId);

    @Query("SELECT DISTINCT uc.user.userId FROM UserClub uc WHERE uc.club.clubId IN :clubIds")
    List<Long> findUserIdByClubIds(@Param("clubIds") List<Long> clubIds);

    List<UserClub> findByUserUserIdIn(Collection<Long> userIds);

    @Query("""
    select new com.example.onlyone.domain.club.repository.ClubWithMemberCount(c, c.memberCount)
    from UserClub uc
      join uc.club c
    where uc.user.userId = :userId
    order by c.modifiedAt desc
    """)
    List<ClubWithMemberCount> findMyClubsWithMemberCount(@Param("userId") Long userId);

    boolean existsByUser_UserIdAndClub_ClubId(Long userId, Long clubId);

    /** 사용자 소속 클럽 ID 목록 (인덱스 스캔, O(1), Redis 캐시 5분) */
    @Cacheable(value = "accessibleClubIds", key = "#userId")
    @Query(value = "SELECT uc.club_id FROM user_club uc WHERE uc.user_id = :userId", nativeQuery = true)
    List<Long> findAccessibleClubIds(@Param("userId") Long userId);
}
