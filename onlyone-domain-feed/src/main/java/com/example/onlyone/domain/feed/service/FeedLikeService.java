package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.domain.club.exception.ClubErrorCode;
import com.example.onlyone.domain.feed.exception.FeedErrorCode;
import com.example.onlyone.global.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedLikeService {

    private final ClubRepository clubRepository;
    private final FeedRepository feedRepository;
    private final UserService userService;
    private final FeedLikeWarmupService warmupService;
    private final DefaultRedisScript<List> likeToggleScript;
    private final StringRedisTemplate redis;
    private final Clock clock;

    private static final Duration EXISTS_CACHE_TTL = Duration.ofMinutes(10);

    public boolean toggleLike(long clubId, long feedId) {
        validateClubExists(clubId);
        validateFeedExists(feedId);
        long userId = userService.getCurrentUserId();

        warmupService.triggerAsync(feedId);

        String reqId = UUID.randomUUID().toString();

        List<String> keys = List.of(
                "feed:" + feedId + ":likers",
                "feed:" + feedId + ":like_count",
                "like:events",
                "idemp:" + reqId
        );
        Object[] args = {
                String.valueOf(userId),
                String.valueOf(feedId),
                reqId,
                String.valueOf(clock.millis())
        };

        List<?> raw = redis.execute(likeToggleScript, keys, args);
        if (raw == null || raw.size() < 3) throw new IllegalStateException("toggle script failed");

        List<Long> toggleResult = new ArrayList<>(3);
        for (Object o : raw) toggleResult.add(((Number) o).longValue());

        boolean liked = toggleResult.get(0) == 1L;
        log.debug("좋아요 토글: feedId={}, userId={}, liked={}", feedId, userId, liked);
        return liked;
    }

    private void validateClubExists(long clubId) {
        String key = "club:exists:" + clubId;
        if (Boolean.TRUE.equals(redis.hasKey(key))) return;
        if (!clubRepository.existsById(clubId)) {
            throw new CustomException(ClubErrorCode.CLUB_NOT_FOUND);
        }
        redis.opsForValue().set(key, "1", EXISTS_CACHE_TTL);
    }

    private void validateFeedExists(long feedId) {
        String key = "feed:exists:" + feedId;
        if (Boolean.TRUE.equals(redis.hasKey(key))) return;
        if (!feedRepository.existsById(feedId)) {
            throw new CustomException(FeedErrorCode.FEED_NOT_FOUND);
        }
        redis.opsForValue().set(key, "1", EXISTS_CACHE_TTL);
    }
}
