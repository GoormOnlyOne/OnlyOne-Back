package com.example.onlyone.domain.schedule.comparison;

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
 * MySQL vs PostgreSQL 일정 참가 동시성 락 비교 테스트.
 *
 * <p>user_limit 체크 + INSERT 유니크 제약 경합 시
 * MySQL의 gap lock 직렬화 vs PostgreSQL의 MVCC 병렬 처리 차이를 비교한다.</p>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MySQL vs PostgreSQL — 일정 참가 동시성")
class ScheduleLockBenchmark {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("schedule_lock_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("--innodb_lock_wait_timeout=5", "--innodb_deadlock_detect=ON", "--max_connections=200");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("schedule_lock_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("postgres", "-c", "max_connections=200", "-c", "deadlock_timeout=1s");

    private static final int SCHEDULE_COUNT = 100;
    private static final int SCHEDULE_USER_LIMIT = 30;

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
                    CREATE TABLE IF NOT EXISTS schedule (
                        schedule_id BIGINT %s PRIMARY KEY,
                        club_id BIGINT NOT NULL,
                        name VARCHAR(200) NOT NULL,
                        user_limit INT NOT NULL DEFAULT 30,
                        current_count INT NOT NULL DEFAULT 0,
                        status VARCHAR(20) NOT NULL DEFAULT 'READY',
                        created_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        modified_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""".formatted(autoInc, ts, ts));

            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS user_schedule (
                        user_schedule_id BIGINT %s PRIMARY KEY,
                        user_id BIGINT NOT NULL,
                        schedule_id BIGINT NOT NULL,
                        created_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        modified_at %s NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE(user_id, schedule_id)
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
                stmt.execute("DELETE FROM user_schedule");
                stmt.execute("DELETE FROM schedule");
                for (int i = 1; i <= SCHEDULE_COUNT; i++) {
                    stmt.execute(("INSERT INTO schedule(club_id, name, user_limit, current_count, status) " +
                            "VALUES (%d, '일정 %d', %d, 0, 'READY')").formatted((i % 100) + 1, i, SCHEDULE_USER_LIMIT));
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // 시나리오 1: 일정 참가 동시성 (단일 일정, 80 VT)
    // user_limit=30이므로 최대 30명만 참가 성공
    // ════════════════════════════════════════════════════════════════
    @Test
    @Order(1)
    @DisplayName("[비교] 일정 참가 동시성 — 단일 일정 (80 VT, user_limit=30)")
    void singleScheduleJoin() throws Exception {
        int threads = 80;

        var mysqlResult = runScheduleJoin(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), threads, "MySQL");

        var pgResult = runScheduleJoin(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), threads, "PostgreSQL");

        printResult("일정 참가 동시성 — 단일 일정 (80 VT, user_limit=%d)".formatted(SCHEDULE_USER_LIMIT),
                mysqlResult, pgResult);

        verifyScheduleLimit(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), "MySQL");
        verifyScheduleLimit(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(), "PostgreSQL");
    }

    @Test
    @Order(2)
    @DisplayName("[비교] 일정 참가 동시성 — 다수 일정 분산 (80 VT)")
    void distributedScheduleJoin() throws Exception {
        int threads = 80;
        int opsPerThread = 5;

        var mysqlResult = runDistributedScheduleJoin(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                threads, opsPerThread, "MySQL");

        var pgResult = runDistributedScheduleJoin(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                threads, opsPerThread, "PostgreSQL");

        printResult("일정 참가 동시성 — 다수 일정 분산 (80 VT × 5 ops)", mysqlResult, pgResult);
    }

    private ScenarioResult runScheduleJoin(
            String url, String user, String pass, int threads, String vendor) throws Exception {

        long scheduleId;
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT schedule_id FROM schedule ORDER BY schedule_id LIMIT 1")) {
            rs.next();
            scheduleId = rs.getLong(1);
        }

        AtomicInteger successOps = new AtomicInteger();
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger totalOps = new AtomicInteger();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final long userId = t + 1;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        long start = System.nanoTime();
                        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
                            conn.setAutoCommit(false);
                            boolean joined = tryJoinSchedule(conn, scheduleId, userId);
                            if (joined) successOps.incrementAndGet();
                        } catch (SQLException e) {
                            if (isDeadlock(e)) deadlocks.incrementAndGet();
                        }
                        latencies.add((System.nanoTime() - start) / 1_000_000);
                        totalOps.incrementAndGet();
                    } catch (Exception ignored) {}
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(60, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - startTime;
            return buildResult(vendor, totalOps.get(), successOps.get(), deadlocks.get(), elapsed, latencies);
        }
    }

    private ScenarioResult runDistributedScheduleJoin(
            String url, String user, String pass,
            int threads, int opsPerThread, String vendor) throws Exception {

        List<Long> scheduleIds = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT schedule_id FROM schedule ORDER BY schedule_id")) {
            while (rs.next()) scheduleIds.add(rs.getLong(1));
        }

        AtomicInteger successOps = new AtomicInteger();
        AtomicInteger deadlocks = new AtomicInteger();
        AtomicInteger totalOps = new AtomicInteger();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final long userId = t + 1;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int op = 0; op < opsPerThread; op++) {
                            long schId = scheduleIds.get((int) ((userId + op) % scheduleIds.size()));
                            long start = System.nanoTime();
                            try (Connection conn = DriverManager.getConnection(url, user, pass)) {
                                conn.setAutoCommit(false);
                                boolean joined = tryJoinSchedule(conn, schId, userId);
                                if (joined) successOps.incrementAndGet();
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

    private boolean tryJoinSchedule(Connection conn, long scheduleId, long userId) throws SQLException {
        try (PreparedStatement checkPs = conn.prepareStatement(
                "SELECT current_count, user_limit FROM schedule WHERE schedule_id = ? FOR UPDATE")) {
            checkPs.setLong(1, scheduleId);
            ResultSet rs = checkPs.executeQuery();
            if (rs.next()) {
                int current = rs.getInt("current_count");
                int limit = rs.getInt("user_limit");
                if (current < limit) {
                    try (PreparedStatement insertPs = conn.prepareStatement(
                            "INSERT INTO user_schedule(user_id, schedule_id) VALUES (?, ?)")) {
                        insertPs.setLong(1, userId);
                        insertPs.setLong(2, scheduleId);
                        insertPs.executeUpdate();
                    }
                    try (PreparedStatement incPs = conn.prepareStatement(
                            "UPDATE schedule SET current_count = current_count + 1 WHERE schedule_id = ?")) {
                        incPs.setLong(1, scheduleId);
                        incPs.executeUpdate();
                    }
                    conn.commit();
                    return true;
                }
            }
            conn.rollback();
            return false;
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }

    private void verifyScheduleLimit(String url, String user, String pass, String vendor) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, pass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT s.current_count, s.user_limit, " +
                             "(SELECT COUNT(*) FROM user_schedule us WHERE us.schedule_id = s.schedule_id) AS actual " +
                             "FROM schedule s ORDER BY s.schedule_id LIMIT 1")) {
            assertThat(rs.next()).isTrue();
            int currentCount = rs.getInt("current_count");
            int userLimit = rs.getInt("user_limit");
            int actual = rs.getInt("actual");
            assertThat(currentCount)
                    .as("[%s] current_count(%d) <= user_limit(%d)", vendor, currentCount, userLimit)
                    .isLessThanOrEqualTo(userLimit);
            assertThat(actual)
                    .as("[%s] 실제 참가자 수(%d) == current_count(%d)", vendor, actual, currentCount)
                    .isEqualTo(currentCount);
        }
    }

    // ── 유틸리티 ──

    private boolean isDeadlock(SQLException e) {
        return e.getErrorCode() == 1213
                || "40P01".equals(e.getSQLState())
                || (e.getMessage() != null && e.getMessage().toLowerCase().contains("deadlock"));
    }

    private boolean isDuplicateKey(SQLException e) {
        return e.getErrorCode() == 1062
                || "23505".equals(e.getSQLState());
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
