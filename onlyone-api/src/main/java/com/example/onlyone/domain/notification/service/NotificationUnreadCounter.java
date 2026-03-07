package com.example.onlyone.domain.notification.service;

import com.example.onlyone.domain.notification.port.NotificationStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 읽지 않은 알림 개수 카운터.
 * Redis 캐시 우선 조회, 미스 시 DB fallback + 캐싱.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationUnreadCounter {

    private static final String KEY_PREFIX = "notification:unread:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final NotificationStoragePort storagePort;

    /** Redis 캐시 우선 조회, 미스 시 DB fallback + 캐싱 */
    public Long getCount(Long userId) {
        String key = key(userId);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return Math.max(0L, Long.parseLong(cached));
            }
        } catch (Exception e) {
            log.warn("Redis 읽기 실패, DB fallback: userId={}", userId, e);
        }

        Long count = storagePort.countUnreadByUserId(userId);
        setQuietly(key(userId), String.valueOf(count));
        return count;
    }

    public void increment(Long userId) {
        try {
            redis.opsForValue().increment(key(userId));
        } catch (Exception e) {
            log.warn("Redis 카운터 증가 실패: userId={}", userId, e);
        }
    }

    public void decrement(Long userId) {
        try {
            String key = key(userId);
            Long result = redis.opsForValue().decrement(key);
            if (result != null && result < 0) {
                redis.delete(key);
            }
        } catch (Exception e) {
            log.warn("Redis 카운터 감소 실패: userId={}", userId, e);
        }
    }

    /** 전체 읽음 시 카운터를 0으로 리셋 */
    public void reset(Long userId) {
        setQuietly(key(userId), "0");
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    private void setQuietly(String key, String value) {
        try {
            redis.opsForValue().set(key, value, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Redis 캐시 저장 실패: key={}", key, e);
        }
    }
}
