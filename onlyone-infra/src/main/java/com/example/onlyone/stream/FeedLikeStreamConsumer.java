package com.example.onlyone.global.stream;

import com.example.onlyone.global.common.util.UuidUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;


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
@ConditionalOnProperty(name = "app.feed-like-stream.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class FeedLikeStreamConsumer implements SmartLifecycle {

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx; // <-- PlatformTransactionManager 주입 필요

    public static final String STREAM = "like:events";
    public static final String GROUP  = "likes-v1";
    private static final String CONSUMER_NAME = "c-" + UUID.randomUUID().toString().substring(0, 8);

    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(5);
    private static final int      BATCH_COUNT   = 8;

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
                        continue; // 잘못된 이벤트는 이번 배치에서 제외 (ACK는 아래 트랜잭션 결과에 따라)
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

                // invalid는 즉시 ACK해서 PEL 비우기
                if (!invalidIds.isEmpty()) {
                    try {
                        redis.opsForStream().acknowledge(STREAM, GROUP, invalidIds.toArray(RecordId[]::new));
                    } catch (Exception ackEx) {
                        log.warn("[likes] invalid ack failed: {}", ackEx.toString());
                    }
                }

                if (events.isEmpty()) {
                    continue; // 처리할 유효 이벤트가 없으면 다음 루프
                }

                // 2) 트랜잭션으로 멱등+배치 반영
                List<RecordId> ackList = tx.execute(status -> {
                    // 2-1) like_applied: 배치 INSERT IGNORE 로 "이번에 처음"인 것만 선별
                    int[] upCounts = jdbc.batchUpdate(
                            "INSERT IGNORE INTO like_applied(req_id, feed_id, user_id, delta) VALUES (?, ?, ?, ?)",
                            new BatchPreparedStatementSetter() {
                                @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                                    Event e = events.get(i);
                                    ps.setString(1, e.reqId());
                                    ps.setLong  (2, e.feedId());
                                    ps.setLong  (3, e.userId());
                                    ps.setInt   (4, e.delta());
                                }
                                @Override public int getBatchSize() { return events.size(); }
                            }
                    );

                    // upCounts[i] == 1 -> 신규 / ==0 -> 중복
                    List<Event> firsts = new ArrayList<>();
                    for (int i = 0; i < upCounts.length; i++) {
                        if (upCounts[i] == 1) firsts.add(events.get(i));
                    }

                    // 얼마나 중복됐는지 남기는 로그
                    if (log.isDebugEnabled()) {
                        long ins = Arrays.stream(upCounts).filter(x -> x == 1).count();
                        long dup = upCounts.length - ins;
                        log.debug("[likes] idempotency filtered: total={}, first={}, dup={}", upCounts.length, ins, dup);
                    }

                    // 2-2) 이번 배치 '최초'들만 집계(coalesce) + 엣지 last-op
                    Map<Long, Long> countDelta = new HashMap<>();                  // feedId -> sum(delta)
                    Map<Long, Map<Long, String>> edgeOps = new HashMap<>();        // feedId -> (userId -> "ON"/"OFF")
                    for (Event e : firsts) {
                        countDelta.merge(e.feedId(), (long) e.delta(), Long::sum);
                        edgeOps.computeIfAbsent(e.feedId(), k -> new HashMap<>()).put(e.userId(), e.op());
                    }

                    // 2-3) like_count 배치 반영
                    if (!countDelta.isEmpty()) {
                        final var entries = new ArrayList<>(countDelta.entrySet());
                        int[] r1 = jdbc.batchUpdate(
                                "UPDATE feed SET like_count = GREATEST(like_count + ?, 0) WHERE feed_id = ?",
                                new BatchPreparedStatementSetter() {
                                    @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                                        var e = entries.get(i);
                                        ps.setLong(1, e.getValue());
                                        ps.setLong(2, e.getKey());
                                    }
                                    @Override public int getBatchSize() { return entries.size(); }
                                }
                        );
                        if (log.isDebugEnabled()) {
                            log.debug("[likes] like_count updated rows={}", Arrays.stream(r1).sum());
                        }
                    }

                    // 2-4) 엣지 배치 (ON: INSERT IGNORE, OFF: DELETE)
                    List<long[]> onPairs  = new ArrayList<>();
                    List<long[]> offPairs = new ArrayList<>();
                    edgeOps.forEach((fid, byUser) ->
                            byUser.forEach((uid, op) -> {
                                if ("ON".equals(op)) onPairs.add(new long[]{fid, uid});
                                else                 offPairs.add(new long[]{fid, uid});
                            })
                    );

                    if (!onPairs.isEmpty()) {
                        int[] r2 = jdbc.batchUpdate(
                                "INSERT IGNORE INTO feed_like(feed_id, user_id) VALUES (?, ?)",
                                new BatchPreparedStatementSetter() {
                                    @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                                        long[] p = onPairs.get(i);
                                        ps.setLong(1, p[0]); ps.setLong(2, p[1]);
                                    }
                                    @Override public int getBatchSize() { return onPairs.size(); }
                                }
                        );
                        if (log.isDebugEnabled()) log.debug("[likes] edge ON inserted rows={}", Arrays.stream(r2).sum());
                    }
                    if (!offPairs.isEmpty()) {
                        int[] r3 = jdbc.batchUpdate(
                                "DELETE FROM feed_like WHERE feed_id = ? AND user_id = ?",
                                new BatchPreparedStatementSetter() {
                                    @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                                        long[] p = offPairs.get(i);
                                        ps.setLong(1, p[0]); ps.setLong(2, p[1]);
                                    }
                                    @Override public int getBatchSize() { return offPairs.size(); }
                                }
                        );
                        if (log.isDebugEnabled()) log.debug("[likes] edge OFF deleted rows={}", Arrays.stream(r3).sum());
                    }

                    // 트랜잭션 성공 → 이 배치의 **모든** 레코드 ACK (invalid는 제외하고 싶으면 분기)
                    List<RecordId> ack = new ArrayList<>(events.size());
                    for (Event e : events) ack.add(e.rid());
                    return ack;
                });

                // 3) 커밋 성공 후에만 ACK
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

