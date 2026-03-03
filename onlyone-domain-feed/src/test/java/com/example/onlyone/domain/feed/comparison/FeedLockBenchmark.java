package com.example.onlyone.domain.feed.comparison;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL vs PostgreSQL 피드 댓글수 증가 락 비교 테스트.
 *
 * <p>단일 row에 대한 {@code UPDATE feed SET comment_count = comment_count + 1}
 * 경합을 비교한다. 양쪽 모두 row lock이지만 MVCC 읽기 비차단 특성 차이를 측정.</p>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MySQL vs PostgreSQL — 피드 댓글수 락")
class FeedLockBenchmark {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("feed_lock_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--innodb_lock_wait_timeout=5", "--innodb_deadlock_detect=ON", "--max_connections=200");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("feed_lock_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("postgres", "-c", "max_connections=200", "-c", "deadlock_timeout=1s");

    private static final int FEED_COUNT = 1000;

    @BeforeAll
    static void initSchemas() throws Exception {
        createSchema(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), "mysql");
        createSchema(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), "postgresql");
    }

    private static void createSchema(String url, String user, String pass, String vendor) throws Exception {
        boolean isMysql = vendor.equals("mysql");
        String autoInc = isMysql ? "AUTO_INCREMENT" : "GENERATED ALWAYS AS IDENTITY";
        String ts = isMysql ? "DATETIME(6)" : "TIMESTAMP(6)";

        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS feed (
                        feed_id BIGINT %s PRIMARY KEY,
                        club_id BIGINT NOT NULL,
                        user_id BIGINT NOT NULL,
                        content VARCHAR(1000),
                        comment_count INT NOT NULL DEFAULT 0,
                        like_count INT NOT NULL DEFAULT 0,
                        created_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        modified_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""".formatted(autoInc, ts, ts));
        }
    }

    @BeforeEach
    void seedData() throws Exception {
        seed(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        seed(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void seed(String url, String user, String pass) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM feed");
                for (int i = 1; i <= FEED_COUNT; i++) {
                    stmt.execute(("INSERT INTO feed(club_id, user_id, content, comment_count, like_count) " +
                            "VALUES (%d, %d, '피드 %d', 0, 0)").formatted((i % 100) + 1, (i % 200) + 1, i));
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 시나리오 1: 단일 피드 댓글수 증가 (100 VT × 100 ops)
    // ════════════════════════════════════════════════════════════════
    @Test
    @Order(1)
    @DisplayName("[비교] 피드 댓글수 증가 — 단일 피드 (100 VT × 100 ops)")
    void singleFeedCommentCountIncrement() throws Exception {
        int threads = 100;
        int opsPerThread = 100;

        var mysqlResult = runCommentCountIncrement(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                threads, opsPerThread, true, "MySQL");

        var pgResult = runCommentCountIncrement(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                threads, opsPerThread, false, "PostgreSQL");

        printResult("피드 댓글수 증가 — 단일 피드 (100 VT × 100 ops)", mysqlResult, pgResult);

        verifyCommentCount(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                mysqlResult.successOps, "MySQL");
        verifyCommentCount(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                pgResult.successOps, "PostgreSQL");
    }

    @Test
    @Order(2)
    @DisplayName("[비교] 피드 댓글수 증가 — 다수 피드 분산 (100 VT × 100 ops)")
    void distributedFeedCommentCountIncrement() throws Exception {
        int threads = 100;
        int opsPerThread = 100;

        var mysqlResult = runDistributedCommentCount(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                threads, opsPerThread, "MySQL");

        var pgResult = runDistributedCommentCount(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                threads, opsPerThread, "PostgreSQL");

        printResult("피드 댓글수 증가 — 다수 피드 분산 (100 VT × 100 ops)", mysqlResult, pgResult);
    }

    private ScenarioResult runCommentCountIncrement(
            String url, String user, String pass,
            int threads, int opsPerThread, boolean isMysql, String vendor) throws Exception {

        long feedId;
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT feed_id FROM feed ORDER BY feed_id LIMIT 1")) {
            rs.next();
            feedId = rs.getLong(1);
        }

        AtomicInteger successOps = new AtomicInteger();
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger totalOps = new AtomicInteger();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int op = 0; op < opsPerThread; op++) {
                            long start = System.nanoTime();
                            try (Connection conn = DriverManager.getConnection(url, user, pass)) {
                                conn.setAutoCommit(false);
                                try (PreparedStatement ps = conn.prepareStatement(
                                        "UPDATE feed SET comment_count = comment_count + 1 WHERE feed_id = ?")) {
                                    ps.setLong(1, feedId);
                                    ps.executeUpdate();
                                    conn.commit();
                                    successOps.incrementAndGet();
                                } catch (SQLException e) {
                                    conn.rollback();
                                    if (isDeadlock(e)) deadlocks.incrementAndGet();
                                }
                            }
                            latencies.add((System.nanoTime() - start) / 1_000_000);
                            totalOps.incrementAndGet();
                        }
                    } catch (Exception ignored) {}
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(120, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - startTime;
            return buildResult(vendor, totalOps.get(), successOps.get(), deadlocks.get(), elapsed, latencies);
        }
    }

    private ScenarioResult runDistributedCommentCount(
            String url, String user, String pass,
            int threads, int opsPerThread, String vendor) throws Exception {

        List<Long> feedIds = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT feed_id FROM feed ORDER BY feed_id LIMIT 100")) {
            while (rs.next()) feedIds.add(rs.getLong(1));
        }

        AtomicInteger successOps = new AtomicInteger();
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger totalOps = new AtomicInteger();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadIdx = t;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int op = 0; op < opsPerThread; op++) {
                            long feedId = feedIds.get((threadIdx + op) % feedIds.size());
                            long start = System.nanoTime();
                            try (Connection conn = DriverManager.getConnection(url, user, pass)) {
                                conn.setAutoCommit(false);
                                try (PreparedStatement ps = conn.prepareStatement(
                                        "UPDATE feed SET comment_count = comment_count + 1 WHERE feed_id = ?")) {
                                    ps.setLong(1, feedId);
                                    ps.executeUpdate();
                                    conn.commit();
                                    successOps.incrementAndGet();
                                } catch (SQLException e) {
                                    conn.rollback();
                                    if (isDeadlock(e)) deadlocks.incrementAndGet();
                                }
                            }
                            latencies.add((System.nanoTime() - start) / 1_000_000);
                            totalOps.incrementAndGet();
                        }
                    } catch (Exception ignored) {}
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(120, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - startTime;
            return buildResult(vendor, totalOps.get(), successOps.get(), deadlocks.get(), elapsed, latencies);
        }
    }

    private void verifyCommentCount(String url, String user, String pass,
                                     int expectedCount, String vendor) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT comment_count FROM feed ORDER BY feed_id LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            int actual = rs.getInt(1);
            assertThat(actual)
                    .as("[%s] comment_count lost update 검증: 기대 %d, 실제 %d", vendor, expectedCount, actual)
                    .isEqualTo(expectedCount);
        }
    }

    // ── 유틸리티 ──

    private boolean isDeadlock(SQLException e) {
        return e.getErrorCode() == 1213
                || "40P01".equals(e.getSQLState())
                || (e.getMessage() != null && e.getMessage().toLowerCase().contains("deadlock"));
    }

    private LatencyStats computeStats(List<Long> latencies) {
        if (latencies.isEmpty()) return new LatencyStats(0, 0, 0);
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long p95 = sorted.get(Math.min((int) (sorted.size() * 0.95), sorted.size() - 1));
        long max = sorted.getLast();
        return new LatencyStats(avg, p95, max);
    }

    private ScenarioResult buildResult(String vendor, int totalOps, int successOps, int deadlocks,
                                        long elapsedMs, List<Long> latencies) {
        LatencyStats stats = computeStats(latencies);
        double opsSec = elapsedMs > 0 ? (totalOps * 1000.0 / elapsedMs) : 0;
        return new ScenarioResult(vendor, totalOps, successOps, deadlocks, opsSec, elapsedMs, stats);
    }

    private void printResult(String title, ScenarioResult mysql, ScenarioResult pg) {
        System.out.println();
        System.out.println("=".repeat(90));
        System.out.println("  " + title);
        System.out.println("=".repeat(90));
        System.out.printf("  %-12s | %9s | %10s | %8s | %8s | %8s | %6s%n",
                "Vendor", "total ops", "ops/sec", "avg(ms)", "p95(ms)", "max(ms)", "데드락");
        System.out.println("  " + "-".repeat(84));
        printRow(mysql);
        printRow(pg);
        System.out.println("=".repeat(90));
    }

    private void printRow(ScenarioResult r) {
        System.out.printf("  %-12s | %9d | %10.1f | %8.1f | %8d | %8d | %6d%n",
                r.vendor, r.totalOps, r.opsSec, r.stats.avg, r.stats.p95, r.stats.max, r.deadlocks);
    }

    record LatencyStats(double avg, long p95, long max) {}

    record ScenarioResult(String vendor, int totalOps, int successOps, int deadlocks,
                          double opsSec, long elapsedMs, LatencyStats stats) {}
}
