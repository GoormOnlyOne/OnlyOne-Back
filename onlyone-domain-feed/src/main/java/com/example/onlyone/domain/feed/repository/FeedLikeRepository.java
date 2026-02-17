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

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface FeedLikeRepository extends JpaRepository<FeedLike, Long> {
    int countByFeed(Feed feed);

    long countByFeed_FeedId(Long feedId);

    boolean existsByFeed_FeedIdAndUser_UserId(Long feedId, Long userId);

    /** 배치: 현재 유저가 좋아요한 피드 ID 목록 */
    @Query("SELECT fl.feed.feedId FROM FeedLike fl WHERE fl.feed.feedId IN :feedIds AND fl.user.userId = :userId")
    Set<Long> findLikedFeedIdsByUser(@Param("feedIds") List<Long> feedIds, @Param("userId") Long userId);
}
