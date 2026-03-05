package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.feed.repository.FeedLikeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedLikeWarmupService {

    private final FeedLikeRepository feedLikeRepository;
    private final StringRedisTemplate redis;

    private static final int WARMUP_THREAD_COUNT = 2;
    private static final int WARMUP_SHUTDOWN_TIMEOUT_SECONDS = 5;

    private final ExecutorService warmupExecutor = Executors.newFixedThreadPool(WARMUP_THREAD_COUNT);
    private final Set<Long> warmingUp = ConcurrentHashMap.newKeySet();

    @PreDestroy
    void shutdown() {
        warmupExecutor.shutdown();
        try {
            if (!warmupExecutor.awaitTermination(WARMUP_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                warmupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            warmupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 비동기 캐시 워밍업 — DB 쿼리를 별도 스레드에서 실행하여 요청 지연 방지.
     * Lua 스크립트는 SET이 없어도 정상 동작 (SADD/SREM이 key 자동 생성).
     * 워밍업이 완료되면 이후 요청부터 정확한 SISMEMBER 결과 반영.
     */
    public void triggerAsync(long feedId) {
        String countKey = "feed:" + feedId + ":like_count";

        if (Boolean.TRUE.equals(redis.hasKey(countKey))) {
            return;
        }
        if (!warmingUp.add(feedId)) {
            return;
        }

        warmupExecutor.submit(() -> {
            try {
                String likersKey = "feed:" + feedId + ":likers";
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
