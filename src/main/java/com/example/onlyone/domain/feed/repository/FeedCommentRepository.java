package com.example.onlyone.domain.feed.repository;

import com.example.onlyone.domain.feed.entity.FeedComment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeedCommentRepository extends JpaRepository<FeedComment, Long> {
}
