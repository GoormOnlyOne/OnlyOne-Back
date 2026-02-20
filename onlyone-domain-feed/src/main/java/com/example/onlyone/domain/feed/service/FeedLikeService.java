package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.club.repository.ClubRepository;
import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import com.example.onlyone.domain.feed.repository.FeedRepository;
import com.example.onlyone.domain.user.service.UserService;
import com.example.onlyone.global.exception.CustomException;
import com.example.onlyone.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedLikeService {

    private final ClubRepository clubRepository;
    private final FeedRepository feedRepository;
    private final FeedLikeRepository feedLikeRepository;
    private final UserService userService;
    private final DefaultRedisScript<List> likeToggleScript;
    private final StringRedisTemplate redis;
    private final Clock clock;

    public boolean toggleLike(long clubId, long feedId) {
        if (!clubRepository.existsById(clubId)) {
            throw new CustomException(ErrorCode.CLUB_NOT_FOUND);
        }
        if (!feedRepository.existsById(feedId)) {
            throw new CustomException(ErrorCode.FEED_NOT_FOUND);
        }
        long userId = userService.getCurrentUser().getUserId();

        ensureLikeCacheWarmed(feedId);

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

    /**
     * Redis에 좋아요 캐시가 없으면 DB에서 복구한다.
     * Redis 재시작 후 첫 호출 시에만 실행되며, 이후에는 키가 존재하므로 스킵.
     */
    private void ensureLikeCacheWarmed(long feedId) {
        String likersKey = "feed:" + feedId + ":likers";
        String countKey = "feed:" + feedId + ":like_count";
        String warmupLock = "feed:" + feedId + ":warmup_lock";

        // 키가 이미 존재하면 워밍업 불필요
        if (Boolean.TRUE.equals(redis.hasKey(likersKey)) || Boolean.TRUE.equals(redis.hasKey(countKey))) {
            return;
        }

        // SETNX 기반 락으로 동시 워밍업 방지
        Boolean acquired = redis.opsForValue().setIfAbsent(warmupLock, "1",
                java.time.Duration.ofSeconds(10));
        if (!Boolean.TRUE.equals(acquired)) {
            return;
        }

        try {
            // 락 획득 후 다시 확인 (다른 스레드가 이미 완료했을 수 있음)
            if (Boolean.TRUE.equals(redis.hasKey(likersKey))) {
                return;
            }

            List<Long> userIds = feedLikeRepository.findUserIdsByFeedId(feedId);
            if (!userIds.isEmpty()) {
                String[] members = userIds.stream().map(String::valueOf).toArray(String[]::new);
                redis.opsForSet().add(likersKey, members);
            }
            redis.opsForValue().set(countKey, String.valueOf(userIds.size()));
            log.info("좋아요 캐시 워밍업 완료: feedId={}, count={}", feedId, userIds.size());
        } catch (Exception e) {
            log.warn("좋아요 캐시 워밍업 실패: feedId={}", feedId, e);
        } finally {
            redis.delete(warmupLock);
        }
    }
}
