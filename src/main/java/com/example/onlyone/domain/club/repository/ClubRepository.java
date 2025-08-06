package com.example.onlyone.domain.club.repository;

import com.example.onlyone.domain.club.entity.Club;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ClubRepository extends JpaRepository<Club, Long> {
    // 모임 검색 (관심사)
    @Query("SELECT c, COUNT(uc) FROM Club c " +
            "LEFT JOIN UserClub uc ON c.clubId = uc.club.clubId " +
            "WHERE c.interest.interestId = :interestId " +
            "GROUP BY c.clubId")
    Page<Object[]> searchByInterest(@Param("interestId") Long interestId, Pageable pageable);

    // 모임 검색 (지역)
    @Query("SELECT c, COUNT(uc) FROM Club c " +
            "LEFT JOIN UserClub uc ON c.clubId = uc.club.clubId " +
            "WHERE c.city = :city and c.district = :district " +
            "GROUP BY c.clubId")
    Page<Object[]> searchByLocation(@Param("city") String city,
                                    @Param("district") String district,
                                    Pageable pageable);

    /**
     * 사용자 맞춤 모임 추천
     * 1. 관심사 + 지역 일치
     * 2. 관심사만 일치
     * 3. 전체 인기 클럽
     */

    // 1단계: 관심사 + 지역 일치 (인기순)
    @Query("SELECT c, COUNT(uc) FROM Club c " +
            "LEFT JOIN UserClub uc ON c.clubId = uc.club.clubId " +
            "WHERE c.interest.interestId IN :interestIds AND c.city = :city AND c.district = :district " +
            "GROUP BY c.clubId " +
            "ORDER BY COUNT(uc) DESC, c.createdAt DESC")
    Page<Object[]> searchByUserInterestAndLocation(@Param("interestIds") List<Long> interestIds,
                                                   @Param("city") String city,
                                                   @Param("district") String district,
                                                   Pageable pageable);

    // 2단계: 관심사만 일치 (인기순)
    @Query("SELECT c, COUNT(uc) FROM Club c " +
            "LEFT JOIN UserClub uc ON c.clubId = uc.club.clubId " +
            "WHERE c.interest.interestId IN :interestIds " +
            "GROUP BY c.clubId " +
            "ORDER BY COUNT(uc) DESC, c.createdAt DESC")
    Page<Object[]> searchByUserInterests(@Param("interestIds") List<Long> interestIds, Pageable pageable);

    // 키워드 검색
    @Query(value = "SELECT c.club_id, c.name, c.description, " +
            "c.district, c.club_image, i.category, " +
            "COUNT(uc.user_club_id) as member_count " +
            "FROM club c LEFT JOIN user_club uc ON c.club_id = uc.club_id " +
            "LEFT JOIN interest i ON c.interest_id = i.interest_id " +
            "WHERE MATCH(c.name, c.description) AGAINST(:keyword IN NATURAL LANGUAGE MODE) " +
            "GROUP BY c.club_id " +
            "ORDER BY MATCH(c.name, c.description) AGAINST(:keyword IN NATURAL LANGUAGE MODE) DESC, " +
            "COUNT(uc.user_club_id) DESC", nativeQuery = true)
    Page<Object[]> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}