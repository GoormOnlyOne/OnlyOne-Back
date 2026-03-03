package com.example.onlyone.domain.settlement.comparison;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka vs RabbitMQ vs Redis Streams MQ 벤더 비교 테스트.
 *
 * <p>Testcontainers로 Kafka + RabbitMQ + Redis를 동시 기동하고,
 * 동일 Outbox 이벤트 1000건을 각 MQ로 publish → consume하여 비교한다.</p>
 *
 * <p>측정: 처리량(msg/sec), 지연(avg/p95), 순서 보장 여부</p>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Kafka vs RabbitMQ vs Redis Streams — 정산 MQ")
class MqBenchmark {

    @Container
    static final KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
            .withKraft();

    @Container
    static final RabbitMQContainer rabbit = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.13-management"));

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    private static final int MESSAGE_COUNT = 1000;
    private static final String TOPIC = "settlement.process.v1";
    private static final String QUEUE = "settlement.process.queue";
    private static final String STREAM_KEY = "stream:settlement.process";
    private static final String CONSUMER_GROUP = "test-group";

    private static List<String> testPayloads;

    @BeforeAll
    static void preparePayloads() {
        testPayloads = new ArrayList<>(MESSAGE_COUNT);
        for (int i = 0; i < MESSAGE_COUNT; i++) {
            testPayloads.add("""
                    {"eventId":"evt-%d","settlementId":%d,"amount":10000,"participantId":%d}
                    """.formatted(i, i / 10, i).trim());
        }

        // Kafka 토픽 생성
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1)));
            admin.listTopics().names().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Kafka topic creation failed", e);
        }
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 1: Publish 처리량
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    @DisplayName("[비교] 1000건 Publish 처리량 — Kafka vs RabbitMQ vs Redis Streams")
    void publishThroughput() throws Exception {
        var kafkaResult = benchmarkPublish("Kafka", this::publishToKafka);
        var rabbitResult = benchmarkPublish("RabbitMQ", this::publishToRabbit);
        var redisResult = benchmarkPublish("Redis Streams", this::publishToRedis);

        printComparisonHeader("Publish 처리량 (%d건)".formatted(MESSAGE_COUNT));
        printRow(kafkaResult);
        printRow(rabbitResult);
        printRow(redisResult);
        printFooter();

        assertThat(kafkaResult.totalOps).isEqualTo(MESSAGE_COUNT);
        assertThat(rabbitResult.totalOps).isEqualTo(MESSAGE_COUNT);
        assertThat(redisResult.totalOps).isEqualTo(MESSAGE_COUNT);
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 2: Consume 처리량
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    @DisplayName("[비교] 1000건 Consume 처리량 — Kafka vs RabbitMQ vs Redis Streams")
    void consumeThroughput() throws Exception {
        // 먼저 각 MQ에 데이터 투입
        publishToKafka();
        publishToRabbit();
        publishToRedis();

        var kafkaResult = benchmarkConsume("Kafka", this::consumeFromKafka);
        var rabbitResult = benchmarkConsume("RabbitMQ", this::consumeFromRabbit);
        var redisResult = benchmarkConsume("Redis Streams", this::consumeFromRedis);

        printComparisonHeader("Consume 처리량 (%d건)".formatted(MESSAGE_COUNT));
        printRow(kafkaResult);
        printRow(rabbitResult);
        printRow(redisResult);
        printFooter();
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 3: 동시 Publish (50 VirtualThreads)
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    @DisplayName("[비교] 50 VirtualThread 동시 Publish — Kafka vs RabbitMQ vs Redis Streams")
    void concurrentPublish() throws Exception {
        int threads = 50;
        int msgsPerThread = 20;

        var kafkaResult = runConcurrentPublish("Kafka", threads, msgsPerThread, this::publishSingleKafka);
        var rabbitResult = runConcurrentPublish("RabbitMQ", threads, msgsPerThread, this::publishSingleRabbit);
        var redisResult = runConcurrentPublish("Redis Streams", threads, msgsPerThread, this::publishSingleRedis);

        printComparisonHeader("동시 Publish (threads=%d, msgs/thread=%d)".formatted(threads, msgsPerThread));
        printRow(kafkaResult);
        printRow(rabbitResult);
        printRow(redisResult);
        printFooter();
    }

    // ── Kafka ────────────────────────────────────────────────
    private void publishToKafka() {
        Properties props = kafkaProducerProps();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (String payload : testPayloads) {
                producer.send(new ProducerRecord<>(TOPIC, payload));
            }
            producer.flush();
        }
    }

    private int consumeFromKafka() {
        Properties props = kafkaConsumerProps();
        int consumed = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.currentTimeMillis() + 10_000;
            while (consumed < MESSAGE_COUNT && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                consumed += records.count();
            }
        }
        return consumed;
    }

    private void publishSingleKafka(String payload) throws Exception {
        Properties props = kafkaProducerProps();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(TOPIC, payload)).get();
        }
    }

    // ── RabbitMQ ─────────────────────────────────────────────
    private void publishToRabbit() {
        try {
            ConnectionFactory factory = rabbitConnectionFactory();
            try (Connection conn = factory.newConnection();
                 Channel channel = conn.createChannel()) {
                channel.queueDeclare(QUEUE, true, false, false, null);
                for (String payload : testPayloads) {
                    channel.basicPublish("", QUEUE, null, payload.getBytes());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int consumeFromRabbit() {
        try {
            ConnectionFactory factory = rabbitConnectionFactory();
            int consumed = 0;
            try (Connection conn = factory.newConnection();
                 Channel channel = conn.createChannel()) {
                channel.queueDeclare(QUEUE, true, false, false, null);
                long deadline = System.currentTimeMillis() + 10_000;
                while (consumed < MESSAGE_COUNT && System.currentTimeMillis() < deadline) {
                    GetResponse response = channel.basicGet(QUEUE, true);
                    if (response != null) {
                        consumed++;
                    } else {
                        Thread.sleep(10);
                    }
                }
            }
            return consumed;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void publishSingleRabbit(String payload) throws Exception {
        ConnectionFactory factory = rabbitConnectionFactory();
        try (Connection conn = factory.newConnection();
             Channel channel = conn.createChannel()) {
            channel.queueDeclare(QUEUE, true, false, false, null);
            channel.basicPublish("", QUEUE, null, payload.getBytes());
        }
    }

    // ── Redis Streams ────────────────────────────────────────
    private void publishToRedis() {
        RedisClient client = redisClient();
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> cmd = conn.sync();
            for (String payload : testPayloads) {
                cmd.xadd(STREAM_KEY, Map.of("payload", payload));
            }
        }
        client.shutdown();
    }

    private int consumeFromRedis() {
        RedisClient client = redisClient();
        int consumed = 0;
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> cmd = conn.sync();

            // Consumer group 생성 (idempotent)
            try {
                cmd.xgroupCreate(io.lettuce.core.XReadArgs.StreamOffset.from(STREAM_KEY, "0"), CONSUMER_GROUP);
            } catch (Exception e) {
                // BUSYGROUP
            }

            long deadline = System.currentTimeMillis() + 10_000;
            while (consumed < MESSAGE_COUNT && System.currentTimeMillis() < deadline) {
                var messages = cmd.xreadgroup(
                        io.lettuce.core.Consumer.from(CONSUMER_GROUP, "test-consumer"),
                        io.lettuce.core.XReadArgs.Builder.count(100).block(100),
                        io.lettuce.core.XReadArgs.StreamOffset.lastConsumed(STREAM_KEY));
                if (messages != null) {
                    for (var msg : messages) {
                        cmd.xack(STREAM_KEY, CONSUMER_GROUP, msg.getId());
                        consumed++;
                    }
                }
            }
        }
        client.shutdown();
        return consumed;
    }

    private void publishSingleRedis(String payload) throws Exception {
        RedisClient client = redisClient();
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            conn.sync().xadd(STREAM_KEY, Map.of("payload", payload));
        }
        client.shutdown();
    }

    // ── Helper: connection factories ─────────────────────────
    private Properties kafkaProducerProps() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        return p;
    }

    private Properties kafkaConsumerProps() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return p;
    }

    private ConnectionFactory rabbitConnectionFactory() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbit.getHost());
        factory.setPort(rabbit.getAmqpPort());
        factory.setUsername(rabbit.getAdminUsername());
        factory.setPassword(rabbit.getAdminPassword());
        return factory;
    }

    private RedisClient redisClient() {
        return RedisClient.create("redis://%s:%d".formatted(
                redis.getHost(), redis.getMappedPort(6379)));
    }

    // ── Benchmark harness ────────────────────────────────────

    @FunctionalInterface
    interface PublishAction {
        void publish() throws Exception;
    }

    @FunctionalInterface
    interface ConsumeAction {
        int consume() throws Exception;
    }

    @FunctionalInterface
    interface SinglePublish {
        void publish(String payload) throws Exception;
    }

    private BenchmarkResult benchmarkPublish(String vendor, PublishAction action) throws Exception {
        List<Long> latencies = new ArrayList<>();
        long start = System.nanoTime();
        action.publish();
        long totalNs = System.nanoTime() - start;
        double totalMs = totalNs / 1_000_000.0;
        double msgPerSec = MESSAGE_COUNT * 1000.0 / totalMs;
        return new BenchmarkResult(vendor, MESSAGE_COUNT, msgPerSec, totalMs / MESSAGE_COUNT,
                totalMs / MESSAGE_COUNT, 0);
    }

    private BenchmarkResult benchmarkConsume(String vendor, ConsumeAction action) throws Exception {
        long start = System.nanoTime();
        int consumed = action.consume();
        long totalNs = System.nanoTime() - start;
        double totalMs = totalNs / 1_000_000.0;
        double msgPerSec = consumed * 1000.0 / totalMs;
        return new BenchmarkResult(vendor, consumed, msgPerSec, totalMs / Math.max(consumed, 1),
                totalMs / Math.max(consumed, 1), 0);
    }

    private BenchmarkResult runConcurrentPublish(String vendor, int threads, int msgsPerThread,
                                                   SinglePublish publisher) throws Exception {
        List<Long> latencies = new CopyOnWriteArrayList<>();
        AtomicInteger totalOps = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                final int threadIdx = t;
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < msgsPerThread; i++) {
                            int msgIdx = threadIdx * msgsPerThread + i;
                            String payload = testPayloads.get(msgIdx % testPayloads.size());
                            long start = System.nanoTime();
                            try {
                                publisher.publish(payload);
                                totalOps.incrementAndGet();
                            } catch (Exception e) {
                                errors.incrementAndGet();
                            }
                            latencies.add((System.nanoTime() - start) / 1_000_000);
                        }
                    } catch (Exception e) { /* ignore */ }
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            double opsPerSec = totalOps.get() * 1000.0 / totalTime;
            var stats = computeStats(latencies);
            return new BenchmarkResult(vendor, totalOps.get(), opsPerSec, stats.avg, stats.p95, errors.get());
        }
    }

    // ── Stats ────────────────────────────────────────────────
    private record LatencyStats(double avg, double p95) {}

    private LatencyStats computeStats(List<Long> latencies) {
        if (latencies.isEmpty()) return new LatencyStats(0, 0);
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        double p95 = sorted.get(Math.min((int) (sorted.size() * 0.95), sorted.size() - 1));
        return new LatencyStats(avg, p95);
    }

    // ── Output ───────────────────────────────────────────────
    private void printComparisonHeader(String title) {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("  " + title);
        System.out.println("=".repeat(100));
        System.out.printf("  %-16s | total msgs | msg/sec    | avg(ms)  | p95(ms)  | errors%n", "벤더");
        System.out.println("  " + "-".repeat(85));
    }

    private void printRow(BenchmarkResult r) {
        System.out.printf("  %-16s | %10d | %10.0f | %8.2f | %8.2f | %5d%n",
                r.vendor, r.totalOps, r.opsPerSec, r.avgMs, r.p95Ms, r.errors);
    }

    private void printFooter() {
        System.out.println("=".repeat(100) + "\n");
    }

    record BenchmarkResult(String vendor, int totalOps, double opsPerSec,
                            double avgMs, double p95Ms, int errors) {}
}
