package com.example.onlyone.domain.wallet.comparison;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL vs PostgreSQL 지갑 락/경합 동시성 비교 테스트.
 *
 * <p>Testcontainers로 MySQL 8.0 + PostgreSQL 16을 동시 기동하고
 * raw JDBC로 동일한 captureHold / batchCaptureHold 시나리오를 실행하여
 * 데드락 빈도, 실행 시간, 잔액 정합성을 비교한다.</p>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MySQL vs PostgreSQL — 지갑 잔액 락")
class WalletLockBenchmark {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("wallet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("wallet_test")
            .withUsername("test")
            .withPassword("test");

    private static final int USER_COUNT = 10;
    private static final long INITIAL_BALANCE = 10_000L;

    @BeforeAll
    static void initSchemas() throws Exception {
        initSchema(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), "mysql");
        initSchema(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), "postgresql");
    }

    private static void initSchema(String url, String user, String pass, String vendor) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {

            String autoIncrement = vendor.equals("mysql") ? "AUTO_INCREMENT" : "GENERATED ALWAYS AS IDENTITY";
            String bigintType = "BIGINT";

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS wallet (
                    wallet_id BIGINT %s PRIMARY KEY,
                    user_id BIGINT NOT NULL UNIQUE,
                    posted_balance BIGINT NOT NULL DEFAULT 0,
                    pending_out BIGINT NOT NULL DEFAULT 0
                )
                """.formatted(autoIncrement));

            // 인덱스
            if (vendor.equals("mysql")) {
                stmt.execute("CREATE INDEX idx_wallet_user ON wallet(user_id)");
            } else {
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_wallet_user ON wallet(user_id)");
            }
        }
    }

    @BeforeEach
    void seedData() throws Exception {
        seedWallets(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        seedWallets(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private void seedWallets(String url, String user, String pass) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM wallet");
            for (int i = 1; i <= USER_COUNT; i++) {
                stmt.execute("INSERT INTO wallet(user_id, posted_balance, pending_out) VALUES (%d, %d, 0)"
                        .formatted(i, INITIAL_BALANCE));
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 1: 단일 유저 동시 차감
    // 50 VirtualThread가 동시에 captureHold(userId=1, amount=100)
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    @DisplayName("[비교] 단일 유저 동시 차감 — MySQL vs PostgreSQL")
    void singleUserConcurrentDebit() throws Exception {
        int threads = 50;
        long amount = 100;
        long userId = 1;

        var mysqlResult = runConcurrentCaptureHold(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                userId, amount, threads, "MySQL");

        var pgResult = runConcurrentCaptureHold(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                userId, amount, threads, "PostgreSQL");

        // 결과 출력
        printComparisonHeader("단일 유저 동시 차감 (threads=%d, amount=%d)".formatted(threads, amount));
        printResultRow("MySQL", mysqlResult);
        printResultRow("PostgreSQL", pgResult);
        printComparisonFooter();

        // 정합성 검증: 성공 횟수 × amount 만큼 잔액 차감
        verifyBalanceIntegrity(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                userId, mysqlResult.successCount, amount, "MySQL");
        verifyBalanceIntegrity(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                userId, pgResult.successCount, amount, "PostgreSQL");
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 2: 다수 유저 배치 차감
    // batchCaptureHold(userIds=[1..10], amount=100) 20회 동시 실행
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    @DisplayName("[비교] 다수 유저 배치 차감 — MySQL vs PostgreSQL")
    void batchConcurrentDebit() throws Exception {
        int concurrency = 20;
        long amount = 100;
        List<Long> userIds = IntStream.rangeClosed(1, USER_COUNT).mapToObj(i -> (long) i).toList();

        var mysqlResult = runConcurrentBatchCaptureHold(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                userIds, amount, concurrency, "MySQL");

        var pgResult = runConcurrentBatchCaptureHold(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                userIds, amount, concurrency, "PostgreSQL");

        printComparisonHeader("다수 유저 배치 차감 (concurrency=%d, users=%d)".formatted(concurrency, USER_COUNT));
        printResultRow("MySQL", mysqlResult);
        printResultRow("PostgreSQL", pgResult);
        printComparisonFooter();

        // 배치 정합성: 전체 유저 잔액 합 검증
        verifyTotalBalance(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                mysqlResult.totalRowsAffected, amount, "MySQL");
        verifyTotalBalance(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                pgResult.totalRowsAffected, amount, "PostgreSQL");
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 3: 읽기-쓰기 혼합
    // 읽기 50 thread + 쓰기 20 thread 동시
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    @DisplayName("[비교] 읽기-쓰기 혼합 — MySQL vs PostgreSQL")
    void readWriteMix() throws Exception {
        int readers = 50;
        int writers = 20;
        long amount = 50;

        var mysqlResult = runReadWriteMix(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                readers, writers, amount, "MySQL");

        var pgResult = runReadWriteMix(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                readers, writers, amount, "PostgreSQL");

        System.out.println("\n" + "=".repeat(80));
        System.out.println("  읽기-쓰기 혼합 (readers=%d, writers=%d)".formatted(readers, writers));
        System.out.println("=".repeat(80));
        System.out.printf("  %-12s | 읽기 avg: %6.1fms | 읽기 p95: %6.1fms | 쓰기 성공: %3d | 데드락: %3d | 총 시간: %6dms%n",
                "MySQL", mysqlResult.readAvgMs, mysqlResult.readP95Ms,
                mysqlResult.writeSuccess, mysqlResult.deadlocks, mysqlResult.totalTimeMs);
        System.out.printf("  %-12s | 읽기 avg: %6.1fms | 읽기 p95: %6.1fms | 쓰기 성공: %3d | 데드락: %3d | 총 시간: %6dms%n",
                "PostgreSQL", pgResult.readAvgMs, pgResult.readP95Ms,
                pgResult.writeSuccess, pgResult.deadlocks, pgResult.totalTimeMs);
        System.out.println("=".repeat(80) + "\n");
    }

    // ── captureHold 로직 (raw JDBC) ────────────────────────────
    private int captureHold(String url, String user, String pass, long userId, long amount) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE wallet
                    SET posted_balance = posted_balance - ?,
                        pending_out = pending_out - ?
                    WHERE user_id = ?
                      AND posted_balance >= ?
                      AND pending_out >= ?
                    """)) {
                // captureHold: posted_balance와 pending_out 모두 차감
                // 여기서는 단순화: holdBalanceIfEnough + captureHold 를 하나로 합침
                ps.setLong(1, amount);
                ps.setLong(2, 0); // pending_out 차감 없이 직접 차감
                ps.setLong(3, userId);
                ps.setLong(4, amount);
                ps.setLong(5, 0);
                int rows = ps.executeUpdate();
                conn.commit();
                return rows;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private int batchCaptureHold(String url, String user, String pass,
                                  List<Long> userIds, long amount) throws SQLException {
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            conn.setAutoCommit(false);
            String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
            String sql = """
                    UPDATE wallet
                    SET posted_balance = posted_balance - ?
                    WHERE user_id IN (%s)
                      AND posted_balance >= ?
                    """.formatted(placeholders);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, amount);
                for (int i = 0; i < userIds.size(); i++) {
                    ps.setLong(i + 2, userIds.get(i));
                }
                ps.setLong(userIds.size() + 2, amount);
                int rows = ps.executeUpdate();
                conn.commit();
                return rows;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ── 동시 실행 헬퍼 ─────────────────────────────────────────
    private ConcurrencyResult runConcurrentCaptureHold(
            String url, String user, String pass,
            long userId, long amount, int threads, String vendor) throws Exception {

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger deadlockCount = new AtomicInteger();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        long start = System.nanoTime();
                        int rows = captureHold(url, user, pass, userId, amount);
                        long elapsed = (System.nanoTime() - start) / 1_000_000;
                        latencies.add(elapsed);
                        if (rows > 0) successCount.incrementAndGet();
                    } catch (SQLException e) {
                        if (isDeadlock(e)) {
                            deadlockCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            return new ConcurrencyResult(
                    vendor, successCount.get(), deadlockCount.get(),
                    0, totalTime, computeStats(latencies));
        }
    }

    private ConcurrencyResult runConcurrentBatchCaptureHold(
            String url, String user, String pass,
            List<Long> userIds, long amount, int concurrency, String vendor) throws Exception {

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger deadlockCount = new AtomicInteger();
        AtomicInteger totalRows = new AtomicInteger();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        long start = System.nanoTime();
                        int rows = batchCaptureHold(url, user, pass, userIds, amount);
                        long elapsed = (System.nanoTime() - start) / 1_000_000;
                        latencies.add(elapsed);
                        totalRows.addAndGet(rows);
                        if (rows > 0) successCount.incrementAndGet();
                    } catch (SQLException e) {
                        if (isDeadlock(e)) deadlockCount.incrementAndGet();
                    } catch (Exception e) {
                        // ignore
                    }
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            return new ConcurrencyResult(
                    vendor, successCount.get(), deadlockCount.get(),
                    totalRows.get(), totalTime, computeStats(latencies));
        }
    }

    private ReadWriteResult runReadWriteMix(
            String url, String user, String pass,
            int readers, int writers, long amount, String vendor) throws Exception {

        List<Long> readLatencies = new CopyOnWriteArrayList<>();
        AtomicInteger writeSuccess = new AtomicInteger();
        AtomicInteger deadlockCount = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            // 읽기 스레드
            for (int i = 0; i < readers; i++) {
                final int userId = (i % USER_COUNT) + 1;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        long start = System.nanoTime();
                        try (Connection conn = DriverManager.getConnection(url, user, pass);
                             PreparedStatement ps = conn.prepareStatement(
                                     "SELECT posted_balance, pending_out FROM wallet WHERE user_id = ?")) {
                            ps.setLong(1, userId);
                            ps.executeQuery();
                        }
                        readLatencies.add((System.nanoTime() - start) / 1_000_000);
                    } catch (Exception e) {
                        // ignore
                    }
                }));
            }

            // 쓰기 스레드
            for (int i = 0; i < writers; i++) {
                final int userId = (i % USER_COUNT) + 1;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        int rows = captureHold(url, user, pass, userId, amount);
                        if (rows > 0) writeSuccess.incrementAndGet();
                    } catch (SQLException e) {
                        if (isDeadlock(e)) deadlockCount.incrementAndGet();
                    } catch (Exception e) {
                        // ignore
                    }
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            var stats = computeStats(readLatencies);
            return new ReadWriteResult(vendor, stats.avg, stats.p95,
                    writeSuccess.get(), deadlockCount.get(), totalTime);
        }
    }

    // ── 유틸리티 ───────────────────────────────────────────────
    private boolean isDeadlock(SQLException e) {
        // MySQL: 1213 (ER_LOCK_DEADLOCK), PostgreSQL: 40P01
        return e.getErrorCode() == 1213
                || "40P01".equals(e.getSQLState())
                || (e.getMessage() != null && e.getMessage().toLowerCase().contains("deadlock"));
    }

    private LatencyStats computeStats(List<Long> latencies) {
        if (latencies.isEmpty()) return new LatencyStats(0, 0, 0);
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        long p95 = sorted.get((int) (sorted.size() * 0.95));
        long max = sorted.getLast();
        return new LatencyStats(avg, p95, max);
    }

    private void verifyBalanceIntegrity(String url, String user, String pass,
                                         long userId, int successOps, long amount,
                                         String vendor) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT posted_balance FROM wallet WHERE user_id = ?")) {
            ps.setLong(1, userId);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            long actualBalance = rs.getLong(1);
            long expectedBalance = INITIAL_BALANCE - (successOps * amount);
            assertThat(actualBalance)
                    .as("[%s] 잔액 정합성: 초기 %d - (성공 %d × %d) = %d",
                            vendor, INITIAL_BALANCE, successOps, amount, expectedBalance)
                    .isEqualTo(expectedBalance);
        }
    }

    private void verifyTotalBalance(String url, String user, String pass,
                                     int totalRowsAffected, long amount, String vendor) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT SUM(posted_balance) FROM wallet");
            assertThat(rs.next()).isTrue();
            long totalBalance = rs.getLong(1);
            long expectedTotal = (USER_COUNT * INITIAL_BALANCE) - (totalRowsAffected * amount);
            assertThat(totalBalance)
                    .as("[%s] 전체 잔액 정합성: 총 초기 %d - (영향 행 %d × %d) = %d",
                            vendor, USER_COUNT * INITIAL_BALANCE, totalRowsAffected, amount, expectedTotal)
                    .isEqualTo(expectedTotal);
        }
    }

    private void printComparisonHeader(String title) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("  " + title);
        System.out.println("=".repeat(80));
        System.out.printf("  %-12s | 성공 | 데드락 | avg(ms) | p95(ms) | max(ms) | 총 시간(ms)%n", "벤더");
        System.out.println("  " + "-".repeat(74));
    }

    private void printResultRow(String vendor, ConcurrencyResult r) {
        System.out.printf("  %-12s | %4d | %6d | %7.1f | %7d | %7d | %11d%n",
                vendor, r.successCount, r.deadlockCount,
                r.stats.avg, r.stats.p95, r.stats.max, r.totalTimeMs);
    }

    private void printComparisonFooter() {
        System.out.println("=".repeat(80) + "\n");
    }

    // ── 결과 레코드 ───────────────────────────────────────────
    record LatencyStats(double avg, long p95, long max) {}

    record ConcurrencyResult(
            String vendor,
            int successCount,
            int deadlockCount,
            int totalRowsAffected,
            long totalTimeMs,
            LatencyStats stats) {}

    record ReadWriteResult(
            String vendor,
            double readAvgMs,
            double readP95Ms,
            int writeSuccess,
            int deadlocks,
            long totalTimeMs) {}
}
