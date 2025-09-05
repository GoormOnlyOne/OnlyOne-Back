package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedLike;
import com.example.onlyone.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FeedLikeRepository extends JpaRepository<FeedLike, Long> {
    int countByFeed(Feed feed);

    long countByFeed_FeedId(Long feedId);

    @Modifying
    @Query(value = """
        INSERT IGNORE INTO feed_like(feed_id, user_id)
        VALUES (:feedId, :userId)
        """, nativeQuery = true)
    int tryInsertIgnore(@Param("feedId") long feedId, @Param("userId") long userId);

    @Modifying
    @Query(value = """
        DELETE FROM feed_like
        WHERE feed_id = :feedId AND user_id = :userId
        """, nativeQuery = true)
    int tryDelete(@Param("feedId") long feedId, @Param("userId") long userId);

    @Modifying
    @Query(value = """
        UPDATE feed
           SET like_count = GREATEST(like_count + :delta, 0)
         WHERE feed_id = :feedId
        """, nativeQuery = true)
    int bumpLike(@Param("feedId") long feedId, @Param("delta") int delta);
}
