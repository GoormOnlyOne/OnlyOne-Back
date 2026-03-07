package com.example.onlyone.domain.notification.comparison;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL vs PostgreSQL 알림 일괄 읽음 처리 락 비교 테스트.
 *
 * <p>핵심 차이: MySQL은 {@code UPDATE ... LIMIT} 시 gap lock이 발생하지만
 * PostgreSQL은 {@code FOR UPDATE SKIP LOCKED}로 락 경합을 회피한다.</p>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MySQL vs PostgreSQL — 알림 일괄 읽음 락")
class NotificationLockBenchmark {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("notification_lock_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--innodb_lock_wait_timeout=5", "--innodb_deadlock_detect=ON", "--max_connections=200");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notification_lock_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("postgres", "-c", "max_connections=200", "-c", "deadlock_timeout=1s");

    private static final int NOTIFICATION_USERS = 200;
    private static final int NOTIFICATION_PER_USER = 100;

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
                    CREATE TABLE IF NOT EXISTS notification (
                        notification_id BIGINT %s PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        content VARCHAR(500) NOT NULL,
                        type VARCHAR(50) NOT NULL DEFAULT 'FEED',
                        is_read BOOLEAN NOT NULL DEFAULT FALSE,
                        delivered BOOLEAN NOT NULL DEFAULT FALSE,
                        created_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        modified_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""".formatted(autoInc, ts, ts));

            if (isMysql) {
                safeExecute(stmt, "CREATE INDEX idx_notification_user_read ON notification(user_id, is_read)");
            } else {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_notification_user_read ON notification(user_id, is_read)");
            }
        }
    }

    private static void safeExecute(Statement stmt, String sql) {
        try { stmt.execute(sql); } catch (SQLException ignored) {}
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
                stmt.execute("DELETE FROM notification");
                for (int userId = 1; userId <= NOTIFICATION_USERS; userId++) {
                    for (int n = 0; n < NOTIFICATION_PER_USER; n++) {
                        stmt.execute(("INSERT INTO notification(user_id, content, type, is_read, delivered) " +
                                "VALUES (%d, '알림 %d-%d', 'FEED', FALSE, TRUE)").formatted(userId, userId, n));
                    }
                    if (userId % 50 == 0) conn.commit();
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 시나리오: 알림 일괄 읽음 처리
    // MySQL:  UPDATE ... WHERE user_id=? AND is_read=FALSE LIMIT 100
    // PG:     UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)
    // 50 VT × 20 ops, 같은 user
    // ════════════════════════════════════════════════════════════════
    @Test
    @Order(1)
    @DisplayName("[비교] 알림 일괄 읽음 — MySQL gap lock vs PG SKIP LOCKED (50 VT × 20 ops)")
    void batchMarkRead() throws Exception {
        int threads = 50;
        int opsPerThread = 20;
        long userId = 1L;

        var mysqlResult = runBatchMarkRead(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                userId, threads, opsPerThread, true, "MySQL");

        var pgResult = runBatchMarkRead(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                userId, threads, opsPerThread, false, "PostgreSQL");

        printResult("알림 일괄 읽음 (50 VT × 20 ops, 같은 user)", mysqlResult, pgResult);
        assertThat(mysqlResult.totalOps + pgResult.totalOps).isGreaterThan(0);
    }

    @Test
    @Order(2)
    @DisplayName("[비교] 알림 일괄 읽음 — 다수 유저 분산 (50 VT × 20 ops)")
    void batchMarkReadDistributed() throws Exception {
        int threads = 50;
        int opsPerThread = 20;

        var mysqlResult = runDistributedBatchMarkRead(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                threads, opsPerThread, true, "MySQL");

        var pgResult = runDistributedBatchMarkRead(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                threads, opsPerThread, false, "PostgreSQL");

        printResult("알림 일괄 읽음 — 다수 유저 분산 (50 VT × 20 ops)", mysqlResult, pgResult);
    }

    private ScenarioResult runBatchMarkRead(
            String url, String user, String pass,
            long userId, int threads, int opsPerThread, boolean isMysql, String vendor) throws Exception {

        AtomicInteger successOps = new AtomicInteger();
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger totalOps = new AtomicInteger();
        AtomicLong totalRows = new AtomicLong();
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
                                int rows = executeBatchMarkRead(conn, userId, isMysql);
                                conn.commit();
                                totalRows.addAndGet(rows);
                                if (rows > 0) successOps.incrementAndGet();
                            } catch (SQLException e) {
                                if (isDeadlock(e)) deadlocks.incrementAndGet();
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

    private ScenarioResult runDistributedBatchMarkRead(
            String url, String user, String pass,
            int threads, int opsPerThread, boolean isMysql, String vendor) throws Exception {

        AtomicInteger successOps = new AtomicInteger();
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger totalOps = new AtomicInteger();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final long userId = (t % NOTIFICATION_USERS) + 1;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int op = 0; op < opsPerThread; op++) {
                            long start = System.nanoTime();
                            try (Connection conn = DriverManager.getConnection(url, user, pass)) {
                                conn.setAutoCommit(false);
                                int rows = executeBatchMarkRead(conn, userId, isMysql);
                                conn.commit();
                                if (rows > 0) successOps.incrementAndGet();
                            } catch (SQLException e) {
                                if (isDeadlock(e)) deadlocks.incrementAndGet();
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

    private int executeBatchMarkRead(Connection conn, long userId, boolean isMysql) throws SQLException {
        if (isMysql) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE notification SET is_read = TRUE, modified_at = CURRENT_TIMESTAMP " +
                            "WHERE user_id = ? AND is_read = FALSE LIMIT 100")) {
                ps.setLong(1, userId);
                return ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE notification SET is_read = TRUE, modified_at = CURRENT_TIMESTAMP " +
                            "WHERE notification_id IN (" +
                            "  SELECT notification_id FROM notification " +
                            "  WHERE user_id = ? AND is_read = FALSE " +
                            "  LIMIT 100 FOR UPDATE SKIP LOCKED" +
                            ")")) {
                ps.setLong(1, userId);
                return ps.executeUpdate();
            }
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
