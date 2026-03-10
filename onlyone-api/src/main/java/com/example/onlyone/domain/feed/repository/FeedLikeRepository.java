package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.feed.entity.FeedLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

public interface FeedLikeRepository extends JpaRepository<FeedLike, Long> {

    boolean existsByFeed_FeedIdAndUser_UserId(Long feedId, Long userId);

    /** 배치: 현재 유저가 좋아요한 피드 ID 목록 */
    @Query("SELECT fl.feed.feedId FROM FeedLike fl WHERE fl.feed.feedId IN :feedIds AND fl.user.userId = :userId")
    Set<Long> findLikedFeedIdsByUser(@Param("feedIds") List<Long> feedIds, @Param("userId") Long userId);

    /** 특정 피드에 좋아요한 사용자 ID 목록 (Redis 캐시 워밍업용) */
    @Query("SELECT fl.user.userId FROM FeedLike fl WHERE fl.feed.feedId = :feedId")
    List<Long> findUserIdsByFeedId(@Param("feedId") Long feedId);
}
