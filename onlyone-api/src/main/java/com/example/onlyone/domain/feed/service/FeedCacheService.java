package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.feed.dto.response.FeedCommentResponseDto;
import com.example.onlyone.domain.feed.dto.response.FeedOverviewDto;
import com.example.onlyone.domain.feed.port.FeedStoragePort.FeedDetailItem;
import com.example.onlyone.domain.feed.repository.FeedRepositoryCustom.FeedIdWithCounts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedCacheService {

    private final StringRedisTemplate redis;

    // Key prefixes (FeedQueryService에서도 참조)
    static final String PERSONAL_FEED_KEY_PREFIX = "pf:";
    static final String POPULAR_FEED_KEY_PREFIX = "ppf:";
    private static final int MAX_CACHEABLE_PAGE = 5;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private static final Duration PASS1_CACHE_TTL = Duration.ofSeconds(30);
    private static final long RESULT_CACHE_TTL_MS = 10_000;
    private static final long DETAIL_CACHE_TTL_MS = 30_000;
    private static final int MAX_CACHE_SIZE = 2000;

    // ── Pass1 (Redis) ──

    public List<FeedIdWithCounts> getPass1(String key) {
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isEmpty()) return null;
            return Arrays.stream(raw.split(","))
                    .map(entry -> {
                        String[] parts = entry.split(":");
                        return new FeedIdWithCounts(
                                Long.parseLong(parts[0]),
                                Long.parseLong(parts[1]),
                                Long.parseLong(parts[2]));
                    })
                    .toList();
        } catch (Exception e) {
            log.debug("pass1 캐시 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    public void putPass1(String key, List<FeedIdWithCounts> pass1) {
        try {
            if (pass1.isEmpty()) return;
            String value = pass1.stream()
                    .map(r -> r.feedId() + ":" + r.likeCount() + ":" + r.commentCount())
                    .collect(Collectors.joining(","));
            redis.opsForValue().set(key, value, PASS1_CACHE_TTL);
        } catch (Exception e) {
            log.debug("pass1 캐시 저장 실패: {}", e.getMessage());
        }
    }

    // ── Overview Result (in-memory) ──

    private record CachedResult(List<FeedOverviewDto> data, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }
    private static final ConcurrentHashMap<String, CachedResult> resultCache = new ConcurrentHashMap<>();

    public List<FeedOverviewDto> getResult(String key) {
        if (key == null) return null;
        CachedResult cr = resultCache.get(key);
        if (cr == null || cr.isExpired()) return null;
        return cr.data();
    }

    public void putResult(String key, List<FeedOverviewDto> result) {
        if (key == null) return;
        evictIfFull(resultCache);
        resultCache.put(key, new CachedResult(result, System.currentTimeMillis() + RESULT_CACHE_TTL_MS));
    }

    // ── Detail (in-memory) ──

    record DetailCacheEntry(
            FeedDetailItem detail, List<String> imageUrls,
            List<FeedCommentResponseDto> comments, long repostCount,
            long expiresAt
    ) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }
    private static final ConcurrentHashMap<Long, DetailCacheEntry> detailCache = new ConcurrentHashMap<>();

    public DetailCacheEntry getDetail(Long feedId) {
        DetailCacheEntry entry = detailCache.get(feedId);
        return (entry != null && !entry.isExpired()) ? entry : null;
    }

    public void putDetail(Long feedId, FeedDetailItem detail, List<String> imageUrls,
                          List<FeedCommentResponseDto> comments, long repostCount) {
        evictIfFull(detailCache);
        detailCache.put(feedId, new DetailCacheEntry(detail, imageUrls, comments, repostCount,
                System.currentTimeMillis() + DETAIL_CACHE_TTL_MS));
    }

    // ── Invalidation ──

    public void invalidateDetail(Long feedId) {
        detailCache.remove(feedId);
    }

    public void invalidatePersonalFeedForUser(Long userId) {
        invalidateListCaches(PERSONAL_FEED_KEY_PREFIX, userId);
    }

    public void invalidatePopularFeedForUser(Long userId) {
        invalidateListCaches(POPULAR_FEED_KEY_PREFIX, userId);
    }

    public void invalidateAllFeedCachesForUser(Long userId) {
        invalidatePersonalFeedForUser(userId);
        invalidatePopularFeedForUser(userId);
    }

    private void invalidateListCaches(String prefix, Long userId) {
        String resultPrefix = prefix + userId + ":";
        resultCache.keySet().removeIf(k -> k.startsWith(resultPrefix));

        String pass1Prefix = prefix + "p1:" + userId + ":";
        List<String> keysToDelete = new ArrayList<>();
        for (int page = 0; page <= MAX_CACHEABLE_PAGE; page++) {
            keysToDelete.add(pass1Prefix + page + ":" + DEFAULT_PAGE_SIZE);
        }
        try {
            redis.delete(keysToDelete);
        } catch (Exception e) {
            log.debug("pass1 캐시 삭제 실패: {}", e.getMessage());
        }
    }

    // ── Eviction ──

    private void evictIfFull(ConcurrentHashMap<?, ?> cache) {
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.entrySet().removeIf(e -> {
                Object v = e.getValue();
                if (v instanceof CachedResult cr) return cr.isExpired();
                if (v instanceof DetailCacheEntry dc) return dc.isExpired();
                return false;
            });
        }
    }
}
