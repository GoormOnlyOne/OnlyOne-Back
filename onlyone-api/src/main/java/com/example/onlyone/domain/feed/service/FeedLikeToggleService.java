package com.example.onlyone.domain.feed.service;

/**
 * 좋아요 토글 추상화. Redis Lua 기반 FeedLikeService가 구현한다.
 */
public interface FeedLikeToggleService {

    boolean toggleLike(long clubId, long feedId);
}
