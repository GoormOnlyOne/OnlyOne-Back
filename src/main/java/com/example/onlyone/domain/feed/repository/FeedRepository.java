package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.club.entity.Club;
import com.example.onlyone.domain.feed.entity.Feed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FeedRepository extends JpaRepository<Feed,Long> {
    List<Feed> findAllByClub(Club club);

    Optional<Feed> findByFeedIdAndClub(Long feedId, Club club);

    Page<Feed> findByClub(Club club, Pageable pageable);
}