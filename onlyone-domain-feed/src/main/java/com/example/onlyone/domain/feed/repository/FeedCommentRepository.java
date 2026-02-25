package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedComment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FeedCommentRepository extends JpaRepository<FeedComment, Long> {
    List<FeedComment> findByFeedOrderByCreatedAt(Feed feed, Pageable pageable);

    /** 댓글 + User JOIN FETCH (N+1 제거) */
    @Query("SELECT c FROM FeedComment c JOIN FETCH c.user WHERE c.feed.feedId = :feedId ORDER BY c.createdAt ASC")
    List<FeedComment> findByFeedIdWithUser(@Param("feedId") Long feedId);
}
