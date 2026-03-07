package com.example.onlyone.domain.settlement.comparison;

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
 * MySQL vs PostgreSQL 정산 도메인 락/동시성 비교 테스트.
 *
 * <p>정산 도메인에서 가장 심각한 락 경합 2가지 시나리오를 비교한다.</p>
 * <ol>
 *   <li>배치 캡처 — 10개 wallet 동시 잔액 차감 (multi-row UPDATE)</li>
 *   <li>상태 전이 경합 — CAS 패턴 HOLDING → PROCESSING 동시 시도</li>
 * </ol>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MySQL vs PostgreSQL — 정산 락/동시성")
class SettlementLockBenchmark {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("settlement_lock_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--innodb_lock_wait_timeout=5", "--innodb_deadlock_detect=ON", "--max_connections=300");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("settlement_lock_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("postgres", "-c", "max_connections=300", "-c", "deadlock_timeout=1s");

    private static final int WALLET_COUNT = 200;
    private static final long INITIAL_BALANCE = 1_000_000L;
    private static final int SETTLEMENT_COUNT = 100;
    private static final int USER_SETTLEMENT_PER = 10;

    @BeforeAll
    static void initSchemas() throws Exception {
        createSchema(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), "mysql");
        createSchema(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), "postgresql");
    }

    private static void createSchema(String url, String user, String pass, String vendor) throws Exception {
        boolean isMysql = vendor.equals("mysql");
        String autoInc = isMysql ? "AUTO_INCREMENT" : "GENERATED ALWAYS AS IDENTITY";
        String ts = isMysql ? "DATETIME(6)" : "TIMESTAMP(6)";
        String statusType = isMysql ? "ENUM('HOLDING','PROCESSING','COMPLETED','FAILED')" : "VARCHAR(20)";

        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS wallet (
                        wallet_id BIGINT %s PRIMARY KEY,
                        user_id BIGINT NOT NULL UNIQUE,
                        posted_balance BIGINT NOT NULL DEFAULT 0,
                        pending_out BIGINT NOT NULL DEFAULT 0
                    )""".formatted(autoInc));

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS settlement (
                        settlement_id BIGINT %s PRIMARY KEY,
                        schedule_id BIGINT NOT NULL,
                        total_status %s NOT NULL DEFAULT 'HOLDING',
                        sum BIGINT NOT NULL DEFAULT 0,
                        user_id BIGINT NOT NULL,
                        completed_time %s,
                        created_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        modified_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""".formatted(autoInc, statusType, ts, ts, ts));

            if (!isMysql) {
                safeExecute(stmt, """
                        ALTER TABLE settlement ADD CONSTRAINT chk_settlement_status
                        CHECK (total_status IN ('HOLDING','PROCESSING','COMPLETED','FAILED'))""");
            }

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS user_settlement (
                        user_settlement_id BIGINT %s PRIMARY KEY,
                        settlement_id BIGINT NOT NULL,
                        user_id BIGINT NOT NULL,
                        status VARCHAR(20) NOT NULL DEFAULT 'HOLD_ACTIVE',
                        completed_time %s,
                        created_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        modified_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""".formatted(autoInc, ts, ts, ts));

            if (isMysql) {
                safeExecute(stmt, "CREATE INDEX idx_settlement_status ON settlement(total_status)");
                safeExecute(stmt, "CREATE INDEX idx_user_settlement_sid ON user_settlement(settlement_id)");
            } else {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_settlement_status ON settlement(total_status)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_user_settlement_sid ON user_settlement(settlement_id)");
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
                stmt.execute("DELETE FROM user_settlement");
                stmt.execute("DELETE FROM settlement");
                stmt.execute("DELETE FROM wallet");

                for (int i = 1; i <= WALLET_COUNT; i++) {
                    stmt.execute("INSERT INTO wallet(user_id, posted_balance, pending_out) VALUES (%d, %d, 0)"
                            .formatted(i, INITIAL_BALANCE));
                }

                for (int i = 1; i <= SETTLEMENT_COUNT; i++) {
                    stmt.execute("INSERT INTO settlement(schedule_id, total_status, sum, user_id) VALUES (%d, 'HOLDING', 0, 1)"
                            .formatted(i));
                }

                ResultSet rs = stmt.executeQuery("SELECT settlement_id FROM settlement ORDER BY settlement_id");
                List<Long> sids = new ArrayList<>();
                while (rs.next()) sids.add(rs.getLong(1));
                rs.close();

                for (long sid : sids) {
                    for (int u = 1; u <= USER_SETTLEMENT_PER; u++) {
                        stmt.execute("INSERT INTO user_settlement(settlement_id, user_id, status) VALUES (%d, %d, 'HOLD_ACTIVE')"
                                .formatted(sid, u));
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 시나리오 1: 정산 배치 캡처 (가장 심각 — multi-row UPDATE)
    // 100 VT × 50 ops, 10개 wallet 동시 잔액 차감
    // ════════════════════════════════════════════════════════════════
    @Test
    @Order(1)
    @DisplayName("[비교] 정산 배치 캡처 — multi-row UPDATE (100 VT × 50 ops)")
    void batchCaptureHold() throws Exception {
        int threads = 100;
        int opsPerThread = 50;
        long amount = 100;
        List<Long> targetUserIds = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

        var mysqlResult = runMultiRowBatchUpdate(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                targetUserIds, amount, threads, opsPerThread, "MySQL");

        var pgResult = runMultiRowBatchUpdate(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                targetUserIds, amount, threads, opsPerThread, "PostgreSQL");

        printResult("정산 배치 캡처 (100 VT × 50 ops, 10 wallets)", mysqlResult, pgResult);
        assertThat(mysqlResult.totalOps + pgResult.totalOps).isGreaterThan(0);
    }

    private ScenarioResult runMultiRowBatchUpdate(
            String url, String user, String pass,
            List<Long> userIds, long amount, int threads, int opsPerThread, String vendor) throws Exception {

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
                                String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
                                String sql = "UPDATE wallet SET posted_balance = posted_balance - ? WHERE user_id IN (%s) AND posted_balance >= ?"
                                        .formatted(placeholders);
                                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                                    ps.setLong(1, amount);
                                    for (int i = 0; i < userIds.size(); i++) ps.setLong(i + 2, userIds.get(i));
                                    ps.setLong(userIds.size() + 2, amount);
                                    int rows = ps.executeUpdate();
                                    conn.commit();
                                    if (rows > 0) successOps.incrementAndGet();
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

    // ════════════════════════════════════════════════════════════════
    // 시나리오 2: 정산 상태 전이 경합 (CAS — HOLDING → PROCESSING)
    // 50 VT가 100개 settlement 동시 전이 시도
    // ════════════════════════════════════════════════════════════════
    @Test
    @Order(2)
    @DisplayName("[비교] 정산 상태 전이 CAS 경합 (50 VT × 100 settlements)")
    void settlementStatusTransition() throws Exception {
        int threads = 50;

        var mysqlResult = runCasStatusTransition(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), threads, "MySQL");

        var pgResult = runCasStatusTransition(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), threads, "PostgreSQL");

        printResult("정산 상태 전이 CAS 경합 (50 VT × 100 settlements)", mysqlResult, pgResult);
    }

    private ScenarioResult runCasStatusTransition(
            String url, String user, String pass, int threads, String vendor) throws Exception {

        List<Long> sids = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT settlement_id FROM settlement WHERE total_status = 'HOLDING' ORDER BY settlement_id LIMIT 100")) {
            while (rs.next()) sids.add(rs.getLong(1));
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
                        for (long sid : sids) {
                            long start = System.nanoTime();
                            try (Connection conn = DriverManager.getConnection(url, user, pass)) {
                                conn.setAutoCommit(false);
                                try (PreparedStatement ps = conn.prepareStatement(
                                        "UPDATE settlement SET total_status = 'PROCESSING', modified_at = CURRENT_TIMESTAMP " +
                                                "WHERE settlement_id = ? AND total_status = 'HOLDING'")) {
                                    ps.setLong(1, sid);
                                    int rows = ps.executeUpdate();
                                    conn.commit();
                                    if (rows > 0) successOps.incrementAndGet();
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
