package com.example.onlyone.domain.feed.service;

import com.example.onlyone.domain.feed.service.FeedCommentService.CommentCountEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * comment_count 배치 버퍼링.
 * - TX 커밋 후 Redis HINCRBY로 델타 축적 (X-lock 없음)
 * - 3초 주기로 배치 flush: 단일 UPDATE ... CASE WHEN으로 여러 feed 한 번에 갱신
 * - feed row X-lock 경합 대폭 감소 (개별 UPDATE → 배치 1회)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommentCountEventListener {

    private static final String BUFFER_KEY = "feed:comment_count_buffer";

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;

    /**
     * TX 커밋 후 Redis에 델타 축적 — DB 접근 없음, X-lock 없음
     */
    @TransactionalEventListener
    public void handleCommentCountEvent(CommentCountEvent event) {
        try {
            stringRedisTemplate.opsForHash()
                    .increment(BUFFER_KEY, event.feedId().toString(), event.delta());
        } catch (Exception e) {
            log.warn("comment_count Redis 버퍼링 실패: feedId={}, delta={}", event.feedId(), event.delta(), e);
        }
    }

    /**
     * 3초 주기 배치 flush — 축적된 델타를 단일 배치 UPDATE로 DB 반영
     */
    @Scheduled(fixedDelay = 3000)
    public void flushCommentCounts() {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(BUFFER_KEY);
        if (entries.isEmpty()) return;

        // 스냅샷 후 즉시 삭제 (다음 이벤트는 새 키에 축적)
        Set<Object> keys = entries.keySet();
        stringRedisTemplate.opsForHash().delete(BUFFER_KEY, keys.toArray());

        // feedId → delta 변환
        Map<Long, Integer> deltas = new HashMap<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            try {
                Long feedId = Long.parseLong(entry.getKey().toString());
                int delta = Integer.parseInt(entry.getValue().toString());
                if (delta != 0) deltas.put(feedId, delta);
            } catch (NumberFormatException e) {
                log.warn("comment_count 버퍼 파싱 실패: key={}, value={}", entry.getKey(), entry.getValue());
            }
        }

        if (deltas.isEmpty()) return;

        // 단일 배치 UPDATE — CASE WHEN으로 여러 feed를 1회 X-lock으로 갱신
        StringBuilder sql = new StringBuilder("UPDATE feed SET comment_count = GREATEST(comment_count + CASE feed_id ");
        for (Map.Entry<Long, Integer> e : deltas.entrySet()) {
            sql.append("WHEN ").append(e.getKey()).append(" THEN ").append(e.getValue()).append(' ');
        }
        sql.append("ELSE 0 END, 0) WHERE feed_id IN (");
        boolean first = true;
        for (Long feedId : deltas.keySet()) {
            if (!first) sql.append(',');
            sql.append(feedId);
            first = false;
        }
        sql.append(')');

        try {
            int updated = jdbcTemplate.update(sql.toString());
            log.debug("comment_count 배치 flush: feeds={}, updated={}", deltas.size(), updated);
        } catch (Exception e) {
            log.error("comment_count 배치 flush 실패: feeds={}", deltas.size(), e);
        }
    }
}
