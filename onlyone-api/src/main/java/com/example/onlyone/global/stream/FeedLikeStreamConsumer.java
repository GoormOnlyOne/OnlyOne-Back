package com.example.onlyone.global.stream;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
public class FeedLikeStreamConsumer implements SmartLifecycle {

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public FeedLikeStreamConsumer(StringRedisTemplate redis,
                                  JdbcTemplate jdbc,
                                  @Qualifier("transactionTemplate") TransactionTemplate tx) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public static final String STREAM = "like:events";
    public static final String GROUP  = "likes-v1";
    private static final String CONSUMER_NAME = "c-" + UUID.randomUUID().toString().substring(0, 8);

    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(2);
    private static final int      BATCH_COUNT   = 1;
    private static final long     MAX_BACKOFF_MS = 5000;

    private volatile boolean running = false;
    private Thread worker;

    private record Event(String reqId, long feedId, long userId, int delta, String op, RecordId rid) {}
    private record ParseResult(List<Event> events, List<RecordId> invalidIds) {}

    @Override
    public void start() {
        if (running) return;
        running = true;
        ensureStreamGroup();

        worker = new Thread(this::consumeLoop, "like-stream-consumer");
        worker.setDaemon(true);
        worker.start();
        log.info("[likes] consumer started: group={}, consumer={}", GROUP, CONSUMER_NAME);
    }

    private void consumeLoop() {
        final Consumer consumer = Consumer.from(GROUP, CONSUMER_NAME);
        final StreamReadOptions opts = StreamReadOptions.empty().count(BATCH_COUNT).block(BLOCK_TIMEOUT);
        long backoffMs = 50;

        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records =
                        redis.opsForStream().read(consumer, opts, StreamOffset.create(STREAM, ReadOffset.lastConsumed()));

                backoffMs = 50; // 성공 시 backoff 리셋

                if (records == null || records.isEmpty()) continue;

                ParseResult parsed = parseRecords(records);
                ackRecords(parsed.invalidIds());
                if (parsed.events().isEmpty()) continue;

                List<RecordId> ackList = processEventsInTransaction(parsed.events());
                ackRecords(ackList);

            } catch (DataAccessException dae) {
                handleDataAccessError(dae);
                sleepQuiet(backoffMs);
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            } catch (Exception ex) {
                log.warn("[likes] unexpected; will NOT ack. err={}", ex.toString());
                sleepQuiet(backoffMs);
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        }
    }

    // ========== RECORD PARSING ==========

    private ParseResult parseRecords(List<MapRecord<String, Object, Object>> records) {
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
                    opRaw.toString(),
                    r.getId()
            ));
        }

        return new ParseResult(events, invalidIds);
    }

    // ========== TRANSACTION PROCESSING ==========

    private List<RecordId> processEventsInTransaction(List<Event> events) {
        List<RecordId> ackList = tx.execute(status -> {
            List<Event> firsts = filterFirstTimeEvents(events);

            if (!firsts.isEmpty()) {
                Map<Long, Long> countDelta = aggregateCountDeltas(firsts);
                Map<Long, Map<Long, String>> edgeOps = aggregateEdgeOperations(firsts);
                applyLikeCountUpdates(countDelta);
                applyEdgeChanges(edgeOps);
            }

            return events.stream().map(Event::rid).toList();
        });

        return ackList != null ? ackList : List.of();
    }

    private List<Event> filterFirstTimeEvents(List<Event> events) {
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

        List<Event> firsts = new ArrayList<>();
        for (int i = 0; i < upCounts.length; i++) {
            if (upCounts[i] == 1) firsts.add(events.get(i));
        }

        if (log.isDebugEnabled()) {
            long ins = Arrays.stream(upCounts).filter(x -> x == 1).count();
            long dup = upCounts.length - ins;
            log.debug("[likes] idempotency filtered: total={}, first={}, dup={}", upCounts.length, ins, dup);
        }

        return firsts;
    }

    // ========== AGGREGATION ==========

    private Map<Long, Long> aggregateCountDeltas(List<Event> firsts) {
        Map<Long, Long> countDelta = new HashMap<>();
        for (Event e : firsts) {
            countDelta.merge(e.feedId(), (long) e.delta(), Long::sum);
        }
        return countDelta;
    }

    private Map<Long, Map<Long, String>> aggregateEdgeOperations(List<Event> firsts) {
        Map<Long, Map<Long, String>> edgeOps = new HashMap<>();
        for (Event e : firsts) {
            edgeOps.computeIfAbsent(e.feedId(), k -> new HashMap<>()).put(e.userId(), e.op());
        }
        return edgeOps;
    }

    // ========== DB BATCH OPERATIONS ==========

    private void applyLikeCountUpdates(Map<Long, Long> countDelta) {
        if (countDelta.isEmpty()) return;

        final var entries = new ArrayList<>(countDelta.entrySet());
        int[] result = jdbc.batchUpdate(
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
            log.debug("[likes] like_count updated rows={}", Arrays.stream(result).sum());
        }
    }

    private void applyEdgeChanges(Map<Long, Map<Long, String>> edgeOps) {
        List<long[]> onPairs  = new ArrayList<>();
        List<long[]> offPairs = new ArrayList<>();
        edgeOps.forEach((fid, byUser) ->
                byUser.forEach((uid, op) -> {
                    if ("ON".equals(op)) onPairs.add(new long[]{fid, uid});
                    else                 offPairs.add(new long[]{fid, uid});
                })
        );

        if (!onPairs.isEmpty()) {
            int[] r = jdbc.batchUpdate(
                    "INSERT IGNORE INTO feed_like(feed_id, user_id) VALUES (?, ?)",
                    pairBatchSetter(onPairs));
            if (log.isDebugEnabled()) log.debug("[likes] edge ON inserted rows={}", Arrays.stream(r).sum());
        }
        if (!offPairs.isEmpty()) {
            int[] r = jdbc.batchUpdate(
                    "DELETE FROM feed_like WHERE feed_id = ? AND user_id = ?",
                    pairBatchSetter(offPairs));
            if (log.isDebugEnabled()) log.debug("[likes] edge OFF deleted rows={}", Arrays.stream(r).sum());
        }
    }

    private BatchPreparedStatementSetter pairBatchSetter(List<long[]> pairs) {
        return new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                long[] p = pairs.get(i);
                ps.setLong(1, p[0]);
                ps.setLong(2, p[1]);
            }
            @Override public int getBatchSize() { return pairs.size(); }
        };
    }

    // ========== STREAM INFRASTRUCTURE ==========

    private void ackRecords(List<RecordId> ids) {
        if (ids == null || ids.isEmpty()) return;
        try {
            redis.opsForStream().acknowledge(STREAM, GROUP, ids.toArray(RecordId[]::new));
        } catch (Exception e) {
            log.warn("[likes] ack failed: {}", e.toString());
        }
    }

    private void ensureStreamGroup() {
        try {
            try { redis.opsForStream().add(STREAM, Map.of("init", "1")); } catch (Exception ignore) {}
            redis.opsForStream().createGroup(STREAM, ReadOffset.from("0-0"), GROUP);
            log.info("[likes] group ready: stream={}, group={}", STREAM, GROUP);
        } catch (Exception e) {
            log.info("[likes] group may already exist: {}", e.toString());
        }
    }

    private void handleDataAccessError(DataAccessException dae) {
        String msg = String.valueOf(dae.getMessage());
        if (msg.contains("NOGROUP") || msg.contains("no such key")) {
            ensureStreamGroup();
        } else {
            log.warn("[likes] processing failed; will NOT ack. err={}", dae.toString());
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
