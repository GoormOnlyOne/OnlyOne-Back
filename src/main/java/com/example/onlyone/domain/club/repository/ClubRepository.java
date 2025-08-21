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
    List<Object[]> searchByInterest(@Param("interestId") Long interestId, Pageable pageable);

    // 모임 검색 (지역)
    @Query("SELECT c, COUNT(uc) FROM Club c " +
            "LEFT JOIN UserClub uc ON c.clubId = uc.club.clubId " +
            "WHERE c.city = :city and c.district = :district " +
            "GROUP BY c.clubId")
    List<Object[]> searchByLocation(@Param("city") String city,
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
            "AND c.clubId NOT IN (SELECT uc2.club.clubId FROM UserClub uc2 WHERE uc2.user.userId = :userId) " +
            "GROUP BY c.clubId " +
            "ORDER BY COUNT(uc) DESC, c.createdAt DESC")
    List<Object[]> searchByUserInterestAndLocation(@Param("interestIds") List<Long> interestIds,
                                                   @Param("city") String city,
                                                   @Param("district") String district,
                                                   @Param("userId") Long userId,
                                                   Pageable pageable);

    // 2단계: 관심사만 일치 (인기순)
    @Query("SELECT c, COUNT(uc) FROM Club c " +
            "LEFT JOIN UserClub uc ON c.clubId = uc.club.clubId " +
            "WHERE c.interest.interestId IN :interestIds " +
            "AND c.clubId NOT IN (SELECT uc2.club.clubId FROM UserClub uc2 WHERE uc2.user.userId = :userId) " +
            "GROUP BY c.clubId " +
            "ORDER BY COUNT(uc) DESC, c.createdAt DESC")
    List<Object[]> searchByUserInterests(@Param("interestIds") List<Long> interestIds, 
                                        @Param("userId") Long userId, 
                                        Pageable pageable);

    // 통합 검색 (키워드 + 필터)
    @Query(value = "SELECT c.club_id, c.name, c.description, " +
            "c.district, c.club_image, i.category, " +
            "COUNT(uc.user_club_id) as member_count " +
            "FROM club c LEFT JOIN user_club uc ON c.club_id = uc.club_id " +
            "LEFT JOIN interest i ON c.interest_id = i.interest_id " +
            "WHERE (:keyword IS NULL OR :keyword = '' OR MATCH(c.name, c.description) AGAINST(:keyword IN NATURAL LANGUAGE MODE)) " +
            "AND (:city IS NULL OR c.city = :city) " +
            "AND (:district IS NULL OR c.district = :district) " +
            "AND (:interestId IS NULL OR c.interest_id = :interestId) " +
            "GROUP BY c.club_id " +
            "ORDER BY " +
            "CASE " +
            "  WHEN :keyword IS NOT NULL AND :keyword != '' THEN MATCH(c.name, c.description) AGAINST(:keyword IN NATURAL LANGUAGE MODE) " +
            "  WHEN :sortBy = 'LATEST' THEN c.created_at " +
            "  WHEN :sortBy = 'MEMBER_COUNT' THEN COUNT(uc.user_club_id) " +
            "  ELSE COUNT(uc.user_club_id) " +
            "END DESC, " +
            "c.created_at DESC", // 동일한 값일 때 최신순으로 2차 정렬
            nativeQuery = true)
    List<Object[]> searchByKeywordWithFilter(@Param("keyword") String keyword,
                                             @Param("city") String city,
                                             @Param("district") String district,
                                             @Param("interestId") Long interestId,
                                             @Param("sortBy") String sortBy,
                                             Pageable pageable);

    // 함께하는 멤버들의 다른 모임 조회
    @Query("""
        SELECT c, COUNT(uc)
        FROM Club c
        LEFT JOIN UserClub uc ON c.clubId = uc.club.clubId
        WHERE EXISTS (
            SELECT 1
            FROM UserClub uc1
            JOIN UserClub teammate ON uc1.club.clubId = teammate.club.clubId
            JOIN UserClub uc2 ON teammate.user.userId = uc2.user.userId
            WHERE uc2.club.clubId = c.clubId
              AND uc1.user.userId = :userId
              AND teammate.user.userId != :userId
        )
        AND NOT EXISTS (
            SELECT 1
            FROM UserClub uc3
            WHERE uc3.club.clubId = c.clubId
              AND uc3.user.userId = :userId
        )
        GROUP BY c.clubId
        ORDER BY COUNT(uc) DESC, c.createdAt DESC
    """)
    List<Object[]> findClubsByTeammates(@Param("userId") Long userId, Pageable pageable);
}