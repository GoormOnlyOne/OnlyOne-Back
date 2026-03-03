package com.example.onlyone.domain.notification.comparison;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DynamoDB vs MongoDB 알림 저장소 동시성 비교 테스트.
 *
 * <p>Testcontainers로 LocalStack(DynamoDB Local) + MongoDB 7.0을
 * 동시 기동하고, 동일 1만건 시딩 후 VirtualThread 동시 읽기/쓰기/혼합을 비교한다.</p>
 *
 * <p>측정: ops/sec, avg/p95 지연, DynamoDB 스로틀링 빈도</p>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("DynamoDB vs MongoDB — 알림 저장소")
class NotificationDynamoBenchmark {

    @Container
    static final LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3"))
            .withServices(LocalStackContainer.Service.DYNAMODB);

    @Container
    static final MongoDBContainer mongodb = new MongoDBContainer("mongo:7.0");

    private static final int SEED_COUNT = 10_000;
    private static final int USER_COUNT = 100;
    private static final String TABLE_NAME = "notification";

    private static DynamoDbClient dynamoClient;
    private static MongoClient mongoClient;

    @BeforeAll
    static void initSchemas() {
        // DynamoDB 연결 + 테이블 생성
        dynamoClient = DynamoDbClient.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .build();

        dynamoClient.createTable(CreateTableRequest.builder()
                .tableName(TABLE_NAME)
                .keySchema(
                        KeySchemaElement.builder().attributeName("userId").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("numericId").keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("userId").attributeType(ScalarAttributeType.N).build(),
                        AttributeDefinition.builder().attributeName("numericId").attributeType(ScalarAttributeType.N).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());

        dynamoClient.waiter().waitUntilTableExists(b -> b.tableName(TABLE_NAME));

        // MongoDB 연결 + 인덱스
        mongoClient = MongoClients.create(mongodb.getConnectionString());
        MongoDatabase db = mongoClient.getDatabase("notification_test");
        MongoCollection<Document> coll = db.getCollection("notifications");
        coll.createIndex(new Document("userId", 1).append("numericId", -1));
        coll.createIndex(new Document("userId", 1).append("isRead", 1));
        coll.createIndex(new Document("userId", 1).append("delivered", 1));
    }

    @BeforeEach
    void seedData() {
        seedDynamo();
        seedMongoDB();
    }

    private void seedDynamo() {
        // 배치 쓰기 (25건 제한)
        List<WriteRequest> batch = new ArrayList<>(25);
        for (int i = 0; i < SEED_COUNT; i++) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("userId", AttributeValue.fromN(String.valueOf((i % USER_COUNT) + 1)));
            item.put("numericId", AttributeValue.fromN(String.valueOf(i + 1)));
            item.put("content", AttributeValue.fromS("알림 내용 #" + i));
            item.put("type", AttributeValue.fromS("LIKE"));
            item.put("isRead", AttributeValue.fromN(i % 3 == 0 ? "1" : "0"));
            item.put("delivered", AttributeValue.fromN(i % 2 == 0 ? "1" : "0"));
            item.put("createdAt", AttributeValue.fromS(java.time.LocalDateTime.now().toString()));

            batch.add(WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build());
            if (batch.size() == 25) {
                dynamoClient.batchWriteItem(BatchWriteItemRequest.builder()
                        .requestItems(Map.of(TABLE_NAME, new ArrayList<>(batch))).build());
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            dynamoClient.batchWriteItem(BatchWriteItemRequest.builder()
                    .requestItems(Map.of(TABLE_NAME, new ArrayList<>(batch))).build());
        }
    }

    private void seedMongoDB() {
        MongoDatabase db = mongoClient.getDatabase("notification_test");
        MongoCollection<Document> coll = db.getCollection("notifications");
        coll.drop();
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
        if (dynamoClient != null) dynamoClient.close();
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 1: 동시 읽기 (findByUserId)
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    @DisplayName("[비교] 100 VirtualThread 동시 읽기 — DynamoDB vs MongoDB")
    void concurrentReads() throws Exception {
        int threads = 100;
        int opsPerThread = 50;

        var dynamoResult = runConcurrentReads(threads, opsPerThread, "dynamodb");
        var mongoResult = runConcurrentReads(threads, opsPerThread, "mongodb");

        printComparisonHeader("동시 읽기 (threads=%d, ops/thread=%d)".formatted(threads, opsPerThread));
        printThroughputRow("DynamoDB", dynamoResult);
        printThroughputRow("MongoDB", mongoResult);
        printComparisonFooter();

        assertThat(dynamoResult.totalOps).isGreaterThan(0);
        assertThat(mongoResult.totalOps).isGreaterThan(0);
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 2: 동시 쓰기 (insert + markDelivered)
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    @DisplayName("[비교] 50 VirtualThread 동시 쓰기 — DynamoDB vs MongoDB")
    void concurrentWrites() throws Exception {
        int threads = 50;
        int opsPerThread = 40;

        var dynamoResult = runConcurrentWrites(threads, opsPerThread, "dynamodb");
        var mongoResult = runConcurrentWrites(threads, opsPerThread, "mongodb");

        printComparisonHeader("동시 쓰기 (threads=%d, ops/thread=%d)".formatted(threads, opsPerThread));
        printThroughputRow("DynamoDB", dynamoResult);
        printThroughputRow("MongoDB", mongoResult);
        printComparisonFooter();
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 3: 읽기 80 + 쓰기 20 혼합
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    @DisplayName("[비교] 읽기 80 + 쓰기 20 혼합 — DynamoDB vs MongoDB")
    void readWriteMix() throws Exception {
        int readers = 80;
        int writers = 20;
        int opsPerThread = 30;

        var dynamoResult = runReadWriteMix(readers, writers, opsPerThread, "dynamodb");
        var mongoResult = runReadWriteMix(readers, writers, opsPerThread, "mongodb");

        System.out.println("\n" + "=".repeat(100));
        System.out.println("  읽기-쓰기 혼합 (readers=%d, writers=%d, ops/thread=%d)".formatted(readers, writers, opsPerThread));
        System.out.println("=".repeat(100));
        System.out.printf("  %-10s | 읽기 ops/s: %8.0f | 읽기 avg: %6.2fms | 읽기 p95: %6.2fms | 쓰기 ops/s: %8.0f | 스로틀: %3d%n",
                "DynamoDB", dynamoResult.readOpsPerSec, dynamoResult.readAvgMs, dynamoResult.readP95Ms,
                dynamoResult.writeOpsPerSec, dynamoResult.conflicts);
        System.out.printf("  %-10s | 읽기 ops/s: %8.0f | 읽기 avg: %6.2fms | 읽기 p95: %6.2fms | 쓰기 ops/s: %8.0f | 스로틀: %3d%n",
                "MongoDB", mongoResult.readOpsPerSec, mongoResult.readAvgMs, mongoResult.readP95Ms,
                mongoResult.writeOpsPerSec, mongoResult.conflicts);
        System.out.println("=".repeat(100) + "\n");
    }

    // ── 실행 로직 ──────────────────────────────────────────────
    private ThroughputResult runConcurrentReads(int threads, int opsPerThread, String vendor) throws Exception {
        List<Long> latencies = new CopyOnWriteArrayList<>();
        AtomicInteger totalOps = new AtomicInteger();
        AtomicInteger throttles = new AtomicInteger();

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
                            try {
                                if ("dynamodb".equals(vendor)) {
                                    readDynamo(userId);
                                } else {
                                    readMongo(userId);
                                }
                                totalOps.incrementAndGet();
                            } catch (ProvisionedThroughputExceededException e) {
                                throttles.incrementAndGet();
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

            return computeThroughput(vendor, totalOps.get(), totalTime, latencies, throttles.get());
        }
    }

    private ThroughputResult runConcurrentWrites(int threads, int opsPerThread, String vendor) throws Exception {
        List<Long> latencies = new CopyOnWriteArrayList<>();
        AtomicInteger totalOps = new AtomicInteger();
        AtomicInteger throttles = new AtomicInteger();
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
                                if ("dynamodb".equals(vendor)) {
                                    writeDynamo(userId, idGen.getAndIncrement());
                                } else {
                                    writeMongo(userId, idGen.getAndIncrement());
                                }
                                totalOps.incrementAndGet();
                            } catch (ProvisionedThroughputExceededException e) {
                                throttles.incrementAndGet();
                            } catch (Exception e) {
                                // other errors
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

            return computeThroughput(vendor, totalOps.get(), totalTime, latencies, throttles.get());
        }
    }

    private MixResult runReadWriteMix(int readers, int writers, int opsPerThread, String vendor) throws Exception {
        List<Long> readLatencies = new CopyOnWriteArrayList<>();
        List<Long> writeLatencies = new CopyOnWriteArrayList<>();
        AtomicInteger readOps = new AtomicInteger();
        AtomicInteger writeOps = new AtomicInteger();
        AtomicInteger throttles = new AtomicInteger();
        AtomicLong idGen = new AtomicLong(SEED_COUNT + 100_000);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < readers; t++) {
                final long userId = (t % USER_COUNT) + 1;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            long start = System.nanoTime();
                            try {
                                if ("dynamodb".equals(vendor)) readDynamo(userId);
                                else readMongo(userId);
                                readOps.incrementAndGet();
                            } catch (ProvisionedThroughputExceededException e) {
                                throttles.incrementAndGet();
                            }
                            readLatencies.add((System.nanoTime() - start) / 1_000_000);
                        }
                    } catch (Exception e) { /* ignore */ }
                }));
            }

            for (int t = 0; t < writers; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            long userId = ThreadLocalRandom.current().nextLong(1, USER_COUNT + 1);
                            long start = System.nanoTime();
                            try {
                                if ("dynamodb".equals(vendor)) writeDynamo(userId, idGen.getAndIncrement());
                                else writeMongo(userId, idGen.getAndIncrement());
                                writeOps.incrementAndGet();
                            } catch (ProvisionedThroughputExceededException e) {
                                throttles.incrementAndGet();
                            } catch (Exception e) { /* ignore */ }
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
                    writeOpsPerSec, throttles.get());
        }
    }

    // ── DB 오퍼레이션 ──────────────────────────────────────────
    private void readDynamo(long userId) {
        dynamoClient.query(QueryRequest.builder()
                .tableName(TABLE_NAME)
                .keyConditionExpression("userId = :uid")
                .expressionAttributeValues(Map.of(":uid", AttributeValue.fromN(String.valueOf(userId))))
                .scanIndexForward(false)
                .limit(20)
                .build());
    }

    private void readMongo(long userId) {
        mongoClient.getDatabase("notification_test")
                .getCollection("notifications")
                .find(Filters.eq("userId", userId))
                .sort(new Document("numericId", -1))
                .limit(20)
                .into(new ArrayList<>());
    }

    private void writeDynamo(long userId, long seqId) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.fromN(String.valueOf(userId)));
        item.put("numericId", AttributeValue.fromN(String.valueOf(seqId)));
        item.put("content", AttributeValue.fromS("동시성 테스트 알림 #" + seqId));
        item.put("type", AttributeValue.fromS("COMMENT"));
        item.put("isRead", AttributeValue.fromN("0"));
        item.put("delivered", AttributeValue.fromN("0"));
        item.put("createdAt", AttributeValue.fromS(java.time.LocalDateTime.now().toString()));

        dynamoClient.putItem(PutItemRequest.builder().tableName(TABLE_NAME).item(item).build());

        // markDelivered — 조건부 업데이트
        try {
            dynamoClient.updateItem(UpdateItemRequest.builder()
                    .tableName(TABLE_NAME)
                    .key(Map.of(
                            "userId", AttributeValue.fromN(String.valueOf(userId)),
                            "numericId", AttributeValue.fromN(String.valueOf(seqId))))
                    .updateExpression("SET delivered = :t")
                    .conditionExpression("delivered = :f")
                    .expressionAttributeValues(Map.of(
                            ":t", AttributeValue.fromN("1"),
                            ":f", AttributeValue.fromN("0")))
                    .build());
        } catch (ConditionalCheckFailedException ignored) {
            // 이미 delivered
        }
    }

    private void writeMongo(long userId, long seqId) {
        MongoCollection<Document> coll = mongoClient.getDatabase("notification_test")
                .getCollection("notifications");
        coll.insertOne(new Document()
                .append("numericId", seqId)
                .append("userId", userId)
                .append("content", "동시성 테스트 알림 #" + seqId)
                .append("type", "COMMENT")
                .append("isRead", false)
                .append("delivered", false)
                .append("createdAt", new java.util.Date()));
        coll.updateOne(
                Filters.and(Filters.eq("userId", userId), Filters.eq("delivered", false)),
                Updates.set("delivered", true));
    }

    // ── 통계 ───────────────────────────────────────────────────
    private ThroughputResult computeThroughput(String vendor, int totalOps, long totalTimeMs,
                                                List<Long> latencies, int throttles) {
        double opsPerSec = totalOps * 1000.0 / totalTimeMs;
        var stats = computeLatencyStats(latencies);
        return new ThroughputResult(vendor, totalOps, opsPerSec, stats.avg, stats.p95, throttles);
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
        System.out.println("\n" + "=".repeat(100));
        System.out.println("  " + title);
        System.out.println("=".repeat(100));
        System.out.printf("  %-10s | total ops | ops/sec    | avg(ms)  | p95(ms)  | 스로틀%n", "벤더");
        System.out.println("  " + "-".repeat(82));
    }

    private void printThroughputRow(String vendor, ThroughputResult r) {
        System.out.printf("  %-10s | %9d | %10.0f | %8.2f | %8.2f | %4d%n",
                vendor, r.totalOps, r.opsPerSec, r.avgMs, r.p95Ms, r.throttles);
    }

    private void printComparisonFooter() {
        System.out.println("=".repeat(100) + "\n");
    }

    // ── 결과 레코드 ───────────────────────────────────────────
    record ThroughputResult(String vendor, int totalOps, double opsPerSec,
                            double avgMs, double p95Ms, int throttles) {}

    record MixResult(String vendor, double readOpsPerSec, double readAvgMs, double readP95Ms,
                     double writeOpsPerSec, int conflicts) {}
}
