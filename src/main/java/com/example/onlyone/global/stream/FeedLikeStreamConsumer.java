package com.example.onlyone.global.stream;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

import static org.springframework.data.redis.connection.RedisStringCommands.SetOption.SET_IF_ABSENT;


/**
 * Redis Streams 컨슈머:
 *  - like:events 를 읽어서
 *    (1) feed.like_count 를 배치로 합산 반영
 *    (2) feed_like(feed_id,user_id) 엣지를 ON/INSERT, OFF/DELETE 배치 반영
 *  - DB 모든 배치가 "성공"한 뒤에만 ACK 수행
 *  - 실패 시 ACK 하지 않아 PEL에 남겨 재시도됨
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedLikeStreamConsumer implements SmartLifecycle {

    private final StringRedisTemplate redis;  // StringRedisTemplate 권장
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public static final String STREAM = "like:events";
    public static final String GROUP  = "likes-v1";
    private static final String CONSUMER_NAME = "c-" + UUID.randomUUID().toString().substring(0, 8);

    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(5);
    private static final int      BATCH_COUNT   = 8;

    // Redis 멱등 마킹 TTL (재전달/재시도 가능 창에 맞춰 조정)
    private static final Duration APPLIED_TTL = Duration.ofHours(48);
    private static final String   APPLIED_KEY_PREFIX = "idemp:applied:";

    private volatile boolean running = false;
    private Thread worker;

    @Override
    public void start() {
        if (running) return;
        running = true;

        // 스트림/그룹 보강 생성
        try {
            try { redis.opsForStream().add(STREAM, Map.of("init", "1")); } catch (Exception ignore) {}
            redis.opsForStream().createGroup(STREAM, ReadOffset.from("0-0"), GROUP);
            log.info("[likes] group ready: stream={}, group={}", STREAM, GROUP);
        } catch (Exception e) {
            log.info("[likes] group may already exist: {}", e.toString());
        }

        worker = new Thread(this::consumeLoop, "like-stream-consumer");
        worker.setDaemon(true);
        worker.start();
        log.info("[likes] consumer started: group={}, consumer={}", GROUP, CONSUMER_NAME);
    }

    private void consumeLoop() {
        final Consumer consumer = Consumer.from(GROUP, CONSUMER_NAME);
        final StreamReadOptions opts = StreamReadOptions.empty().count(BATCH_COUNT).block(BLOCK_TIMEOUT);

        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records =
                        redis.opsForStream().read(consumer, opts, StreamOffset.create(STREAM, ReadOffset.lastConsumed()));

                if (records == null || records.isEmpty()) {
                    continue;
                }

                // 1) 레코드 파싱
                record Event(String reqId, long feedId, long userId, int delta, String op, RecordId rid) {}
                List<Event> events = new ArrayList<>(records.size());
                List<RecordId> invalidIds = new ArrayList<>();
                for (var r : records) {
                    var v = r.getValue();
                    Object feedIdRaw = v.get("feedId");
                    Object userIdRaw = v.get("userId");
                    Object deltaRaw  = v.get("delta");
                    Object opRaw     = v.get("op");
                    Object reqIdRaw  = v.get("reqId");

                    if (feedIdRaw == null || userIdRaw == null || deltaRaw == null || opRaw == null || reqIdRaw == null) {
                        log.warn("[likes] invalid event (missing fields) id={}, value={}", r.getId(), v);
                        invalidIds.add(r.getId());
                        continue;
                    }
                    events.add(new Event(
                            reqIdRaw.toString(),
                            Long.parseLong(feedIdRaw.toString()),
                            Long.parseLong(userIdRaw.toString()),
                            Integer.parseInt(deltaRaw.toString()),
                            opRaw.toString(),  // "ON" or "OFF"
                            r.getId()
                    ));
                }

                // invalid는 즉시 ACK
                if (!invalidIds.isEmpty()) {
                    try {
                        redis.opsForStream().acknowledge(STREAM, GROUP, invalidIds.toArray(RecordId[]::new));
                    } catch (Exception ackEx) {
                        log.warn("[likes] invalid ack failed: {}", ackEx.toString());
                    }
                }
                if (events.isEmpty()) continue;

                // 2) Redis로 멱등(applied) 사전 필터 (MGET)
                List<String> appliedKeys = new ArrayList<>(events.size());
                for (Event e : events) appliedKeys.add(APPLIED_KEY_PREFIX + e.reqId());
                List<String> existed = redis.opsForValue().multiGet(appliedKeys);

                List<Event> firsts = new ArrayList<>();
                for (int i = 0; i < events.size(); i++) {
                    if (existed == null || existed.get(i) == null) firsts.add(events.get(i));
                }
                if (log.isDebugEnabled()) {
                    log.debug("[likes] idempotency(applied) filter: total={}, first={}, dup={}",
                            events.size(), firsts.size(), events.size() - firsts.size());
                }

                // 3) 트랜잭션: '처음 보는 것'만 DB 반영 (엣지 멱등 + 카운트 정확화)
                List<RecordId> ackList = tx.execute(status -> {

                    if (!firsts.isEmpty()) {
                        // 3-1) 엣지 ON/OFF 준비
                        List<long[]> onPairs  = new ArrayList<>();
                        List<long[]> offPairs = new ArrayList<>();
                        Set<Long> touchedFeeds = new HashSet<>();

                        for (Event e : firsts) {
                            touchedFeeds.add(e.feedId());
                            if ("ON".equals(e.op())) onPairs.add(new long[]{e.feedId(), e.userId()});
                            else                     offPairs.add(new long[]{e.feedId(), e.userId()});
                        }

                        // 3-2) 엣지 적용 (멱등)
                        if (!onPairs.isEmpty()) {
                            jdbc.batchUpdate(
                                    "INSERT IGNORE INTO feed_like(feed_id, user_id) VALUES (?, ?)",
                                    new BatchPreparedStatementSetter() {
                                        @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                                            long[] p = onPairs.get(i);
                                            ps.setLong(1, p[0]); ps.setLong(2, p[1]);
                                        }
                                        @Override public int getBatchSize() { return onPairs.size(); }
                                    }
                            );
                        }
                        if (!offPairs.isEmpty()) {
                            jdbc.batchUpdate(
                                    "DELETE FROM feed_like WHERE feed_id = ? AND user_id = ?",
                                    new BatchPreparedStatementSetter() {
                                        @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                                            long[] p = offPairs.get(i);
                                            ps.setLong(1, p[0]); ps.setLong(2, p[1]);
                                        }
                                        @Override public int getBatchSize() { return offPairs.size(); }
                                    }
                            );
                        }

                        // 3-3) like_count 정확 재계산(해당 feed만) → 중복/재전달에도 일치
                        if (!touchedFeeds.isEmpty()) {
                            String in = String.join(",", Collections.nCopies(touchedFeeds.size(), "?"));
                            List<Long> feedIds = new ArrayList<>(touchedFeeds);

                            List<Map<String, Object>> rows = jdbc.queryForList(
                                    "SELECT feed_id, COUNT(*) AS cnt FROM feed_like WHERE feed_id IN (" + in + ") GROUP BY feed_id",
                                    feedIds.toArray()
                            );
                            Map<Long, Long> counts = new HashMap<>();
                            for (var r : rows) {
                                counts.put(((Number) r.get("feed_id")).longValue(),
                                        ((Number) r.get("cnt")).longValue());
                            }
                            for (Long fid : feedIds) counts.putIfAbsent(fid, 0L);

                            jdbc.batchUpdate(
                                    "UPDATE feed SET like_count = ? WHERE feed_id = ?",
                                    new BatchPreparedStatementSetter() {
                                        final List<Map.Entry<Long, Long>> list = new ArrayList<>(counts.entrySet());
                                        @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                                            var e = list.get(i);
                                            ps.setLong(1, e.getValue());
                                            ps.setLong(2, e.getKey());
                                        }
                                        @Override public int getBatchSize() { return list.size(); }
                                    }
                            );
                        }
                    }

                    // 이 배치 레코드 모두 ACK 대상으로 반환 (invalid는 이미 ACK됨)
                    List<RecordId> ack = new ArrayList<>(events.size());
                    for (Event e : events) ack.add(e.rid());
                    return ack;
                });

                // 4) 커밋 성공 후 '처리 완료' 멱등 마킹 (NX+TTL, 파이프라인) — try-catch 한 겹
                try {
                    if (!firsts.isEmpty()) {
                        final long ttlMs = APPLIED_TTL.toMillis();
                        final RedisSerializer<String> s = redis.getStringSerializer();

                        redis.executePipelined((RedisCallback<Object>) connection -> {
                            for (Event e : firsts) {
                                String key = APPLIED_KEY_PREFIX + e.reqId();
                                connection.stringCommands().set(
                                        s.serialize(key),
                                        s.serialize("1"),
                                        Expiration.milliseconds(ttlMs),
                                        SET_IF_ABSENT // NX
                                );
                            }
                            return null;
                        });
                    }
                } catch (Exception markEx) {
                    // 마킹 실패해도 DB 상태는 정확; 재전달 시에도 엣지 멱등 + 재계산으로 일치
                    log.warn("[likes] idempotency mark failed: {}", markEx.toString());
                }

                // 5) 커밋/마킹 후 ACK
                if (ackList != null && !ackList.isEmpty()) {
                    redis.opsForStream().acknowledge(STREAM, GROUP, ackList.toArray(RecordId[]::new));
                }

            } catch (DataAccessException dae) {
                String msg = String.valueOf(dae.getMessage());
                if (msg.contains("NOGROUP") || msg.contains("no such key")) {
                    try {
                        try { redis.opsForStream().add(STREAM, Map.of("init", "1")); } catch (Exception ignore) {}
                        redis.opsForStream().createGroup(STREAM, ReadOffset.from("0-0"), GROUP);
                        log.info("[likes] (re)created group after error: {}", msg);
                    } catch (Exception createEx) {
                        log.warn("[likes] failed to (re)create group: {}", createEx.toString());
                    }
                } else {
                    log.warn("[likes] processing failed; will NOT ack. err={}", dae.toString());
                }
                sleepQuiet(50);
            } catch (Exception ex) {
                log.warn("[likes] unexpected; will NOT ack. err={}", ex.toString());
                sleepQuiet(50);
            }
        }
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    @Override public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        log.info("[likes] consumer stopped: group={}, consumer={}", GROUP, CONSUMER_NAME);
    }

    @Override public boolean isRunning()     { return running; }
    @Override public boolean isAutoStartup() { return true; }
    @Override public int getPhase()          { return Integer.MIN_VALUE; }
}
