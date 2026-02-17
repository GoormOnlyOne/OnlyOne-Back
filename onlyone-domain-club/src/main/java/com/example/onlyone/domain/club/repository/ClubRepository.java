package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ClubRepository extends JpaRepository<Club, Long>, ClubRepositoryCustom {
    Club findByClubId(long l);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Club c WHERE c.clubId = :clubId")
    Optional<Club> findByIdWithLock(@Param("clubId") Long clubId);

    @Modifying
    @Query("UPDATE Club c SET c.memberCount = c.memberCount + 1 WHERE c.clubId = :clubId")
    int incrementMemberCount(@Param("clubId") Long clubId);

    @Modifying
    @Query("UPDATE Club c SET c.memberCount = CASE WHEN c.memberCount > 0 THEN c.memberCount - 1 ELSE 0 END WHERE c.clubId = :clubId")
    int decrementMemberCount(@Param("clubId") Long clubId);
}