package com.example.onlyone.domain.notification.comparison;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MongoDB vs MySQL 알림 저장소 동시성 비교 테스트.
 *
 * <p>기존 k6 비교 결과(MongoDB 47.5x 처리량)를 보완하는
 * JVM 레벨 동시성 테스트. Testcontainers로 MySQL 8.0 + MongoDB 7.0을
 * 동시 기동하고, 동일 시나리오에서 처리량/지연/충돌을 비교한다.</p>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("MySQL vs MongoDB — 알림 저장소")
class NotificationStorageBenchmark {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("notification_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final MongoDBContainer mongodb = new MongoDBContainer("mongo:7.0");

    private static final int SEED_COUNT = 10_000;
    private static final int USER_COUNT = 100;
    private static MongoClient mongoClient;

    @BeforeAll
    static void initSchemas() throws Exception {
        // MySQL 스키마
        try (Connection conn = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE notification (
                    notification_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    content VARCHAR(500) NOT NULL,
                    type VARCHAR(50) NOT NULL,
                    is_read BOOLEAN NOT NULL DEFAULT FALSE,
                    sse_sent BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    INDEX idx_user_id_desc (user_id, notification_id DESC),
                    INDEX idx_user_read (user_id, is_read),
                    INDEX idx_user_sse (user_id, sse_sent)
                )
                """);
        }

        // MongoDB 연결 + 인덱스
        mongoClient = MongoClients.create(mongodb.getConnectionString());
        MongoDatabase db = mongoClient.getDatabase("notification_test");
        MongoCollection<Document> coll = db.getCollection("notifications");
        coll.createIndex(new Document("userId", 1).append("numericId", -1));
        coll.createIndex(new Document("userId", 1).append("isRead", 1));
        coll.createIndex(new Document("userId", 1).append("delivered", 1));
    }

    @BeforeEach
    void seedData() throws Exception {
        seedMySQL();
        seedMongoDB();
    }

    private void seedMySQL() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM notification");
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO notification(user_id, content, type, is_read, sse_sent) VALUES (?,?,?,?,?)")) {
                for (int i = 0; i < SEED_COUNT; i++) {
                    ps.setLong(1, (i % USER_COUNT) + 1);
                    ps.setString(2, "알림 내용 #" + i);
                    ps.setString(3, "LIKE");
                    ps.setBoolean(4, i % 3 == 0); // 33% 읽음
                    ps.setBoolean(5, i % 2 == 0); // 50% 전송됨
                    ps.addBatch();
                    if (i % 1000 == 0) ps.executeBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    private void seedMongoDB() {
        MongoDatabase db = mongoClient.getDatabase("notification_test");
        MongoCollection<Document> coll = db.getCollection("notifications");
        coll.drop();
        // 인덱스 재생성
        coll.createIndex(new Document("userId", 1).append("numericId", -1));
        coll.createIndex(new Document("userId", 1).append("isRead", 1));
        coll.createIndex(new Document("userId", 1).append("delivered", 1));

        List<Document> batch = new ArrayList<>(1000);
        for (int i = 0; i < SEED_COUNT; i++) {
            batch.add(new Document()
                    .append("numericId", (long) (i + 1))
                    .append("userId", (long) ((i % USER_COUNT) + 1))
                    .append("content", "알림 내용 #" + i)
                    .append("type", "LIKE")
                    .append("isRead", i % 3 == 0)
                    .append("delivered", i % 2 == 0)
                    .append("createdAt", new java.util.Date()));
            if (batch.size() == 1000) {
                coll.insertMany(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) coll.insertMany(batch);
    }

    @AfterAll
    static void cleanup() {
        if (mongoClient != null) mongoClient.close();
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 1: 동시 읽기 (findByUserId)
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    @DisplayName("[비교] 100 VirtualThread 동시 읽기 — MySQL vs MongoDB")
    void concurrentReads() throws Exception {
        int threads = 100;
        int opsPerThread = 50;

        var mysqlResult = runConcurrentReads(threads, opsPerThread, "mysql");
        var mongoResult = runConcurrentReads(threads, opsPerThread, "mongodb");

        printComparisonHeader("동시 읽기 (threads=%d, ops/thread=%d)".formatted(threads, opsPerThread));
        printThroughputRow("MySQL", mysqlResult);
        printThroughputRow("MongoDB", mongoResult);
        printComparisonFooter();

        // 둘 다 읽기는 성공해야 함
        assertThat(mysqlResult.totalOps).isGreaterThan(0);
        assertThat(mongoResult.totalOps).isGreaterThan(0);
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 2: 동시 쓰기 (insert + markDelivered)
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    @DisplayName("[비교] 50 VirtualThread 동시 쓰기 — MySQL vs MongoDB")
    void concurrentWrites() throws Exception {
        int threads = 50;
        int opsPerThread = 40;

        var mysqlResult = runConcurrentWrites(threads, opsPerThread, "mysql");
        var mongoResult = runConcurrentWrites(threads, opsPerThread, "mongodb");

        printComparisonHeader("동시 쓰기 (threads=%d, ops/thread=%d)".formatted(threads, opsPerThread));
        printThroughputRow("MySQL", mysqlResult);
        printThroughputRow("MongoDB", mongoResult);
        printComparisonFooter();
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 3: 읽기 80 + 쓰기 20 혼합
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    @DisplayName("[비교] 읽기 80 + 쓰기 20 혼합 — MySQL vs MongoDB")
    void readWriteMix() throws Exception {
        int readers = 80;
        int writers = 20;
        int opsPerThread = 30;

        var mysqlResult = runReadWriteMix(readers, writers, opsPerThread, "mysql");
        var mongoResult = runReadWriteMix(readers, writers, opsPerThread, "mongodb");

        System.out.println("\n" + "=".repeat(90));
        System.out.println("  읽기-쓰기 혼합 (readers=%d, writers=%d, ops/thread=%d)".formatted(readers, writers, opsPerThread));
        System.out.println("=".repeat(90));
        System.out.printf("  %-10s | 읽기 ops/s: %8.0f | 읽기 avg: %6.2fms | 읽기 p95: %6.2fms | 쓰기 ops/s: %8.0f | 충돌: %3d%n",
                "MySQL", mysqlResult.readOpsPerSec, mysqlResult.readAvgMs, mysqlResult.readP95Ms,
                mysqlResult.writeOpsPerSec, mysqlResult.conflicts);
        System.out.printf("  %-10s | 읽기 ops/s: %8.0f | 읽기 avg: %6.2fms | 읽기 p95: %6.2fms | 쓰기 ops/s: %8.0f | 충돌: %3d%n",
                "MongoDB", mongoResult.readOpsPerSec, mongoResult.readAvgMs, mongoResult.readP95Ms,
                mongoResult.writeOpsPerSec, mongoResult.conflicts);
        System.out.println("=".repeat(90) + "\n");
    }

    // ── 실행 로직 ──────────────────────────────────────────────
    private ThroughputResult runConcurrentReads(int threads, int opsPerThread, String vendor) throws Exception {
        List<Long> latencies = new CopyOnWriteArrayList<>();
        AtomicInteger totalOps = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final long userId = (t % USER_COUNT) + 1;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            long start = System.nanoTime();
                            if ("mysql".equals(vendor)) {
                                readMysql(userId);
                            } else {
                                readMongo(userId);
                            }
                            latencies.add((System.nanoTime() - start) / 1_000_000);
                            totalOps.incrementAndGet();
                        }
                    } catch (Exception e) { /* ignore */ }
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(60, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            return computeThroughput(vendor, totalOps.get(), totalTime, latencies, 0);
        }
    }

    private ThroughputResult runConcurrentWrites(int threads, int opsPerThread, String vendor) throws Exception {
        List<Long> latencies = new CopyOnWriteArrayList<>();
        AtomicInteger totalOps = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicLong idGen = new AtomicLong(SEED_COUNT + 1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            long userId = ThreadLocalRandom.current().nextLong(1, USER_COUNT + 1);
                            long start = System.nanoTime();
                            try {
                                if ("mysql".equals(vendor)) {
                                    writeMysql(userId, idGen.getAndIncrement());
                                } else {
                                    writeMongo(userId, idGen.getAndIncrement());
                                }
                                totalOps.incrementAndGet();
                            } catch (Exception e) {
                                if (e.getMessage() != null && (
                                        e.getMessage().contains("Deadlock")
                                        || e.getMessage().contains("deadlock")
                                        || e.getMessage().contains("lock"))) {
                                    conflicts.incrementAndGet();
                                }
                            }
                            latencies.add((System.nanoTime() - start) / 1_000_000);
                        }
                    } catch (Exception e) { /* ignore */ }
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(60, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            return computeThroughput(vendor, totalOps.get(), totalTime, latencies, conflicts.get());
        }
    }

    private MixResult runReadWriteMix(int readers, int writers, int opsPerThread, String vendor) throws Exception {
        List<Long> readLatencies = new CopyOnWriteArrayList<>();
        List<Long> writeLatencies = new CopyOnWriteArrayList<>();
        AtomicInteger readOps = new AtomicInteger();
        AtomicInteger writeOps = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicLong idGen = new AtomicLong(SEED_COUNT + 100_000);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            // 읽기
            for (int t = 0; t < readers; t++) {
                final long userId = (t % USER_COUNT) + 1;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            long start = System.nanoTime();
                            if ("mysql".equals(vendor)) readMysql(userId);
                            else readMongo(userId);
                            readLatencies.add((System.nanoTime() - start) / 1_000_000);
                            readOps.incrementAndGet();
                        }
                    } catch (Exception e) { /* ignore */ }
                }));
            }

            // 쓰기
            for (int t = 0; t < writers; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            long userId = ThreadLocalRandom.current().nextLong(1, USER_COUNT + 1);
                            long start = System.nanoTime();
                            try {
                                if ("mysql".equals(vendor)) writeMysql(userId, idGen.getAndIncrement());
                                else writeMongo(userId, idGen.getAndIncrement());
                                writeOps.incrementAndGet();
                            } catch (Exception e) {
                                conflicts.incrementAndGet();
                            }
                            writeLatencies.add((System.nanoTime() - start) / 1_000_000);
                        }
                    } catch (Exception e) { /* ignore */ }
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(60, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            double readOpsPerSec = readOps.get() * 1000.0 / totalTime;
            double writeOpsPerSec = writeOps.get() * 1000.0 / totalTime;
            var readStats = computeLatencyStats(readLatencies);

            return new MixResult(vendor, readOpsPerSec, readStats.avg, readStats.p95,
                    writeOpsPerSec, conflicts.get());
        }
    }

    // ── DB 오퍼레이션 ──────────────────────────────────────────
    private void readMysql(long userId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT notification_id, content, type, is_read, created_at FROM notification WHERE user_id = ? ORDER BY notification_id DESC LIMIT 20")) {
            ps.setLong(1, userId);
            ps.executeQuery();
        }
    }

    private void readMongo(long userId) {
        mongoClient.getDatabase("notification_test")
                .getCollection("notifications")
                .find(Filters.eq("userId", userId))
                .sort(new Document("numericId", -1))
                .limit(20)
                .into(new ArrayList<>());
    }

    private void writeMysql(long userId, long seqId) throws SQLException {
        try (Connection conn = DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
            conn.setAutoCommit(false);
            // INSERT
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO notification(user_id, content, type) VALUES (?, ?, ?)")) {
                ps.setLong(1, userId);
                ps.setString(2, "동시성 테스트 알림 #" + seqId);
                ps.setString(3, "COMMENT");
                ps.executeUpdate();
            }
            // markDelivered (랜덤 기존 알림)
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE notification SET sse_sent = TRUE WHERE user_id = ? AND sse_sent = FALSE LIMIT 1")) {
                ps.setLong(1, userId);
                ps.executeUpdate();
            }
            conn.commit();
        }
    }

    private void writeMongo(long userId, long seqId) {
        MongoCollection<Document> coll = mongoClient.getDatabase("notification_test")
                .getCollection("notifications");
        // INSERT
        coll.insertOne(new Document()
                .append("numericId", seqId)
                .append("userId", userId)
                .append("content", "동시성 테스트 알림 #" + seqId)
                .append("type", "COMMENT")
                .append("isRead", false)
                .append("delivered", false)
                .append("createdAt", new java.util.Date()));
        // markDelivered
        coll.updateOne(
                Filters.and(Filters.eq("userId", userId), Filters.eq("delivered", false)),
                Updates.set("delivered", true));
    }

    // ── 통계 ───────────────────────────────────────────────────
    private ThroughputResult computeThroughput(String vendor, int totalOps, long totalTimeMs,
                                                List<Long> latencies, int conflicts) {
        double opsPerSec = totalOps * 1000.0 / totalTimeMs;
        var stats = computeLatencyStats(latencies);
        return new ThroughputResult(vendor, totalOps, opsPerSec, stats.avg, stats.p95, conflicts);
    }

    private record LatencyStats(double avg, double p95) {}

    private LatencyStats computeLatencyStats(List<Long> latencies) {
        if (latencies.isEmpty()) return new LatencyStats(0, 0);
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        double p95 = sorted.get(Math.min((int) (sorted.size() * 0.95), sorted.size() - 1));
        return new LatencyStats(avg, p95);
    }

    // ── 출력 ───────────────────────────────────────────────────
    private void printComparisonHeader(String title) {
        System.out.println("\n" + "=".repeat(90));
        System.out.println("  " + title);
        System.out.println("=".repeat(90));
        System.out.printf("  %-10s | total ops | ops/sec    | avg(ms)  | p95(ms)  | 충돌%n", "벤더");
        System.out.println("  " + "-".repeat(76));
    }

    private void printThroughputRow(String vendor, ThroughputResult r) {
        System.out.printf("  %-10s | %9d | %10.0f | %8.2f | %8.2f | %4d%n",
                vendor, r.totalOps, r.opsPerSec, r.avgMs, r.p95Ms, r.conflicts);
    }

    private void printComparisonFooter() {
        System.out.println("=".repeat(90) + "\n");
    }

    // ── 결과 레코드 ───────────────────────────────────────────
    record ThroughputResult(String vendor, int totalOps, double opsPerSec,
                            double avgMs, double p95Ms, int conflicts) {}

    record MixResult(String vendor, double readOpsPerSec, double readAvgMs, double readP95Ms,
                     double writeOpsPerSec, int conflicts) {}
}
