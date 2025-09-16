package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.feed.entity.Feed;
import com.example.onlyone.domain.feed.entity.FeedLike;
import com.example.onlyone.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FeedLikeRepository extends JpaRepository<FeedLike, Long> {
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from FeedLike fl where fl.feed = :feed and fl.user = :user")
    int deleteByFeedAndUser(@Param("feed") Feed feed, @Param("user") User user);

    int countByFeed(Feed feed);
}
