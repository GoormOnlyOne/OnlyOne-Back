package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.feed.entity.Feed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

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

    Page<Feed> findByClubAndParentFeedIdIsNull(Club club, Pageable pageable);

    Feed findByFeedId(Long feedId);

    @Query("SELECT f FROM Feed f WHERE f.club.clubId IN :clubIds")
    List<Feed> findByClubIds(List<Long> clubIds, Pageable pageable);

    @Query(value = """
    SELECT f.*
    FROM feed f
    LEFT JOIN (
      SELECT fl.feed_id, COUNT(*) AS cnt
      FROM feed_like fl
      GROUP BY fl.feed_id
    ) l ON l.feed_id = f.feed_id
    LEFT JOIN (
      SELECT fc.feed_id, COUNT(*) AS cnt
      FROM feed_comment fc
      GROUP BY fc.feed_id
    ) c ON c.feed_id = f.feed_id
    WHERE f.club_id IN (:clubIds)
    ORDER BY (
      LOG(GREATEST(
          COALESCE(l.cnt, 0)                 
        + COALESCE(c.cnt, 0) * 2             
        + CASE WHEN f.parent_feed_id IS NOT NULL THEN 2 ELSE 0 END  
      , 1))
      - (TIMESTAMPDIFF(HOUR, f.created_at, NOW()) / 12.0)             
    ) DESC,
    f.created_at DESC
    LIMIT :#{#pageable.offset}, :#{#pageable.pageSize}
    """, nativeQuery = true)
    List<Feed> findPopularByClubIds(@Param("clubIds") List<Long> clubIds, Pageable pageable);

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