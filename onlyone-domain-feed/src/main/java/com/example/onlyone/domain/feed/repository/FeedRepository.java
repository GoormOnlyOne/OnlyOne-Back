package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.feed.entity.Feed;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FeedRepository extends JpaRepository<Feed, Long>, FeedRepositoryCustom {

    long countByParentFeedId(Long feedId);

    Optional<Feed> findByFeedIdAndClub(Long feedId, Club club);

    /** 피드 상세: Feed + User + Images + Club 한번에 로딩 */
    @Query("SELECT f FROM Feed f " +
           "LEFT JOIN FETCH f.user " +
           "LEFT JOIN FETCH f.club " +
           "LEFT JOIN FETCH f.feedImages " +
           "WHERE f.feedId = :feedId AND f.club.clubId = :clubId")
    Optional<Feed> findByIdAndClubIdWithRelations(@Param("feedId") Long feedId, @Param("clubId") Long clubId);

    /** Pass 2: ID 목록으로 Feed + User + Club + FeedImages 한번에 로딩 (N+1 제거) */
    @Query("SELECT DISTINCT f FROM Feed f " +
           "LEFT JOIN FETCH f.user " +
           "LEFT JOIN FETCH f.club " +
           "LEFT JOIN FETCH f.feedImages " +
           "WHERE f.feedId IN :ids")
    List<Feed> findByIdsWithRelations(@Param("ids") List<Long> ids);

    /** comment_count 원자적 증가 */
    @Modifying
    @Query("UPDATE Feed f SET f.commentCount = f.commentCount + 1 WHERE f.feedId = :feedId")
    void incrementCommentCount(@Param("feedId") Long feedId);

    /** comment_count 원자적 감소 (최소 0) */
    @Modifying
    @Query("UPDATE Feed f SET f.commentCount = CASE WHEN f.commentCount > 0 THEN f.commentCount - 1 ELSE 0 END WHERE f.feedId = :feedId")
    void decrementCommentCount(@Param("feedId") Long feedId);

    // 직계 자식의 parent/root NULL 처리
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Feed f SET f.parentFeedId = NULL, f.rootFeedId = NULL WHERE f.parentFeedId = :parentId AND f.deleted = FALSE")
    int clearParentAndRootForChildren(@Param("parentId") Long parentId);

    // 후손의 root NULL 처리
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Feed f SET f.rootFeedId = NULL WHERE f.rootFeedId = :rootId AND f.deleted = FALSE")
    int clearRootForDescendants(@Param("rootId") Long rootId);

    // 소프트 삭제
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Feed f SET f.deleted = TRUE, f.deletedAt = CURRENT_TIMESTAMP WHERE f.feedId = :feedId AND f.deleted = FALSE")
    int softDeleteById(@Param("feedId") Long feedId);
}
