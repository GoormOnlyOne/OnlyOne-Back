package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.feed.dto.response.FeedSummaryResponseDto;
import com.example.onlyone.domain.feed.entity.Feed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeedRepository extends JpaRepository<Feed,Long> {
    long countByParentFeedId(Long feedId);

    @Query(
            value = """
                select parent_feed_id as parentId, count(*) as cnt
                from feed
                where parent_feed_id in (:feedIds)
                    and deleted = false
                group by parent_feed_id
            """,
            nativeQuery = true
    )
    List<ParentRepostCount> countDirectRepostsIn(@Param("feedIds") List<Long> feedIds);

    interface ParentRepostCount {
        Long getParentId();
        Long getCnt();
    }

    Optional<Feed> findByFeedIdAndClub(Long feedId, Club club);

    /** 피드 상세: Feed + User + Images + Club 한번에 로딩 */
    @Query("SELECT f FROM Feed f " +
           "LEFT JOIN FETCH f.user " +
           "LEFT JOIN FETCH f.club " +
           "LEFT JOIN FETCH f.feedImages " +
           "WHERE f.feedId = :feedId AND f.club.clubId = :clubId")
    Optional<Feed> findByIdAndClubIdWithRelations(@Param("feedId") Long feedId, @Param("clubId") Long clubId);

    /** getFeedList 최적화: LEFT JOIN으로 첫 이미지 가져오기 (상관 서브쿼리 제거) */
    @Query(value = """
        SELECT f.feed_id as feedId,
               fi_first.feed_image as thumbnailUrl,
               f.like_count as likeCount,
               f.comment_count as commentCount
        FROM feed f
        LEFT JOIN feed_image fi_first ON fi_first.feed_id = f.feed_id
            AND fi_first.feed_image_id = (SELECT MIN(fi2.feed_image_id) FROM feed_image fi2 WHERE fi2.feed_id = f.feed_id)
        WHERE f.club_id = :clubId
          AND f.parent_feed_id IS NULL
          AND f.deleted = false
        ORDER BY f.created_at DESC
        """,
        countQuery = "SELECT COUNT(*) FROM feed f WHERE f.club_id = :clubId AND f.parent_feed_id IS NULL AND f.deleted = false",
        nativeQuery = true)
    Page<FeedSummaryProjection> findFeedSummaryProjectionsByClubId(@Param("clubId") Long clubId, Pageable pageable);

    interface FeedSummaryProjection {
        Long getFeedId();
        String getThumbnailUrl();
        int getLikeCount();
        int getCommentCount();
    }

    default Page<FeedSummaryResponseDto> findFeedSummariesByClubId(Long clubId, Pageable pageable) {
        return findFeedSummaryProjectionsByClubId(clubId, pageable)
                .map(p -> new FeedSummaryResponseDto(p.getFeedId(), p.getThumbnailUrl(), p.getLikeCount(), p.getCommentCount()));
    }

    /** Pass 1: 개인 피드 ID + 비정규화 count (커버링 인덱스 idx_feed_personal_cover 활용) */
    @Query(value = """
        SELECT f.feed_id as feedId,
               f.like_count as likeCount,
               f.comment_count as commentCount
        FROM feed f
        WHERE f.club_id IN (:clubIds)
          AND f.deleted = false
        ORDER BY f.created_at DESC
        LIMIT :#{#pageable.offset}, :#{#pageable.pageSize}
        """, nativeQuery = true)
    List<FeedIdWithCounts> findFeedIdsWithCountsByClubIds(@Param("clubIds") List<Long> clubIds, Pageable pageable);

    /** Pass 1: 인기 피드 — 비정규화 컬럼으로 score 계산 (correlated subquery 완전 제거) */
    @Query(value = """
    SELECT f.feed_id as feedId,
           f.like_count as likeCount,
           f.comment_count as commentCount
    FROM feed f
    WHERE f.club_id IN (:clubIds)
        AND f.deleted = false
        AND f.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
    ORDER BY (
      LOG(GREATEST(
          f.like_count
        + f.comment_count * 2
        + CASE WHEN f.parent_feed_id IS NOT NULL THEN 2 ELSE 0 END
      , 1))
      - (TIMESTAMPDIFF(HOUR, f.created_at, NOW()) / 12.0)
    ) DESC,
    f.created_at DESC
    LIMIT :#{#pageable.offset}, :#{#pageable.pageSize}
    """, nativeQuery = true)
    List<FeedIdWithCounts> findPopularFeedIdsWithCountsByClubIds(@Param("clubIds") List<Long> clubIds, Pageable pageable);

    interface FeedIdWithCounts {
        Long getFeedId();
        Long getLikeCount();
        Long getCommentCount();
    }

    /** Pass 2: ID 목록으로 Feed + User + Club + FeedImages 한번에 로딩 (N+1 제거) */
    @Query("SELECT DISTINCT f FROM Feed f " +
           "LEFT JOIN FETCH f.user " +
           "LEFT JOIN FETCH f.club " +
           "LEFT JOIN FETCH f.feedImages " +
           "WHERE f.feedId IN :ids")
    List<Feed> findByIdsWithRelations(@Param("ids") List<Long> ids);

    /** comment_count 원자적 증가 */
    @Modifying
    @Query(value = "UPDATE feed SET comment_count = comment_count + 1 WHERE feed_id = :feedId", nativeQuery = true)
    void incrementCommentCount(@Param("feedId") Long feedId);

    /** comment_count 원자적 감소 (최소 0) */
    @Modifying
    @Query(value = "UPDATE feed SET comment_count = GREATEST(comment_count - 1, 0) WHERE feed_id = :feedId", nativeQuery = true)
    void decrementCommentCount(@Param("feedId") Long feedId);

    // 나(= parentId)를 인용하던 '직계 자식'들의 parent/root를 모두 NULL
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    UPDATE Feed f
       SET f.parentFeedId = NULL,
           f.rootFeedId   = NULL
     WHERE f.parentFeedId = :parentId
       AND f.deleted = FALSE
""")
    int clearParentAndRootForChildren(@Param("parentId") Long parentId);

    // 나(= rootId)를 루트로 바라보던 모든 후손들의 root를 NULL
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    UPDATE Feed f
       SET f.rootFeedId = NULL
     WHERE f.rootFeedId = :rootId
       AND f.deleted = FALSE
""")
    int clearRootForDescendants(@Param("rootId") Long rootId);

    // 소프트 삭제 (엔티티 @SQLDelete 호출 대신 직접 UPDATE)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
    UPDATE Feed f
       SET f.deleted  = TRUE,
           f.deletedAt = CURRENT_TIMESTAMP
     WHERE f.feedId = :feedId
       AND f.deleted = FALSE
""")
    int softDeleteById(@Param("feedId") Long feedId);
}
