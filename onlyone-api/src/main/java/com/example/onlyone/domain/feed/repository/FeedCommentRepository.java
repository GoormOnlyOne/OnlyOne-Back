package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FeedCommentRepository extends JpaRepository<FeedComment, Long> {

    /** 댓글 + User JOIN FETCH (N+1 제거) — 페이지네이션 지원 */
    @Query(value = "SELECT c FROM FeedComment c JOIN FETCH c.user WHERE c.feed.feedId = :feedId ORDER BY c.createdAt ASC",
           countQuery = "SELECT COUNT(c) FROM FeedComment c WHERE c.feed.feedId = :feedId")
    List<FeedComment> findByFeedIdWithUser(@Param("feedId") Long feedId, Pageable pageable);

    /** 댓글 + User JOIN FETCH (N+1 제거) — 전체 조회 */
    @Query("SELECT c FROM FeedComment c JOIN FETCH c.user WHERE c.feed.feedId = :feedId ORDER BY c.createdAt ASC")
    List<FeedComment> findByFeedIdWithUser(@Param("feedId") Long feedId);
}
