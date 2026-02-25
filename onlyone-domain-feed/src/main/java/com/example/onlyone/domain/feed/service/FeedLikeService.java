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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** 비동기 워밍업용 스레드풀 (최대 2스레드, DB 부하 제한) */
    private static final ExecutorService warmupExecutor = Executors.newFixedThreadPool(2);
    /** 현재 워밍업 진행중인 feedId 추적 (중복 제출 방지) */
    private static final Set<Long> warmingUp = ConcurrentHashMap.newKeySet();

    public boolean toggleLike(long clubId, long feedId) {
        if (!clubRepository.existsById(clubId)) {
            throw new CustomException(ErrorCode.CLUB_NOT_FOUND);
        }
        if (!feedRepository.existsById(feedId)) {
            throw new CustomException(ErrorCode.FEED_NOT_FOUND);
        }
        long userId = userService.getCurrentUser().getUserId();

        // 비동기 워밍업 — 현재 요청을 블로킹하지 않음
        triggerAsyncWarmup(feedId);

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
     * 비동기 캐시 워밍업 — DB 쿼리를 별도 스레드에서 실행하여 요청 지연 방지.
     * Lua 스크립트는 SET이 없어도 정상 동작 (SADD/SREM이 key 자동 생성).
     * 워밍업이 완료되면 이후 요청부터 정확한 SISMEMBER 결과 반영.
     */
    private void triggerAsyncWarmup(long feedId) {
        String countKey = "feed:" + feedId + ":like_count";

        // 이미 캐시가 있으면 스킵
        if (Boolean.TRUE.equals(redis.hasKey(countKey))) {
            return;
        }
        // 이미 워밍업 중이면 스킵
        if (!warmingUp.add(feedId)) {
            return;
        }

        warmupExecutor.submit(() -> {
            try {
                String likersKey = "feed:" + feedId + ":likers";
                // 제출~실행 사이 다른 스레드가 완료했을 수 있음
                if (Boolean.TRUE.equals(redis.hasKey(countKey))) {
                    return;
                }

                List<Long> userIds = feedLikeRepository.findUserIdsByFeedId(feedId);
                if (!userIds.isEmpty()) {
                    String[] members = userIds.stream().map(String::valueOf).toArray(String[]::new);
                    redis.opsForSet().add(likersKey, members);
                }
                redis.opsForValue().set(countKey, String.valueOf(userIds.size()));
                log.debug("좋아요 캐시 워밍업 완료: feedId={}, count={}", feedId, userIds.size());
            } catch (Exception e) {
                log.warn("좋아요 캐시 워밍업 실패: feedId={}", feedId, e);
            } finally {
                warmingUp.remove(feedId);
            }
        });
    }
}
