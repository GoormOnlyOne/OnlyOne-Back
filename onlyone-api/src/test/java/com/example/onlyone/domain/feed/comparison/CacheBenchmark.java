package com.example.onlyone.domain.feed.comparison;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis vs Caffeine 캐시 벤더 비교 테스트.
 *
 * <p>Testcontainers Redis 7 + Caffeine 인메모리 캐시를 동일 시나리오에서 비교한다.
 * 순차 읽기/쓰기, 동시 읽기, hit/miss 비율별 성능, 대용량 값 직렬화 오버헤드를 측정한다.</p>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Redis vs Caffeine — 피드 캐시")
class CacheBenchmark {

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static final int SEED_COUNT = 10_000;
    private static Jedis jedis;
    private static Cache<String, String> caffeineCache;

    @BeforeAll
    static void init() {
        jedis = new Jedis(redis.getHost(), redis.getMappedPort(6379));
        caffeineCache = Caffeine.newBuilder()
                .maximumSize(50_000)
                .build();
    }

    @BeforeEach
    void seedData() {
        jedis.flushAll();
        caffeineCache.invalidateAll();
        for (int i = 0; i < SEED_COUNT; i++) {
            String key = "feed:" + i;
            String value = "feedId:%d,likeCount:%d,commentCount:%d".formatted(i, i * 3, i * 2);
            jedis.set(key, value);
            caffeineCache.put(key, value);
        }
    }

    @AfterAll
    static void cleanup() {
        if (jedis != null) jedis.close();
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 1: 순차 읽기/쓰기 10만회
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    @DisplayName("[비교] 순차 읽기/쓰기 10만회 — Redis vs Caffeine")
    void sequentialReadWrite() {
        int ops = 100_000;

        // Redis 읽기
        long redisReadStart = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            jedis.get("feed:" + (i % SEED_COUNT));
        }
        double redisReadAvg = (System.nanoTime() - redisReadStart) / 1_000_000.0 / ops;

        // Redis 쓰기
        long redisWriteStart = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            jedis.set("write:" + i, "value-" + i);
        }
        double redisWriteAvg = (System.nanoTime() - redisWriteStart) / 1_000_000.0 / ops;

        // Caffeine 읽기
        long cafReadStart = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            caffeineCache.getIfPresent("feed:" + (i % SEED_COUNT));
        }
        double cafReadAvg = (System.nanoTime() - cafReadStart) / 1_000_000.0 / ops;

        // Caffeine 쓰기
        long cafWriteStart = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            caffeineCache.put("write:" + i, "value-" + i);
        }
        double cafWriteAvg = (System.nanoTime() - cafWriteStart) / 1_000_000.0 / ops;

        System.out.println("\n" + "=".repeat(70));
        System.out.println("  순차 읽기/쓰기 %d회 — 평균 지연(ms)".formatted(ops));
        System.out.println("=".repeat(70));
        System.out.printf("  %-12s | 읽기 avg: %.6fms | 쓰기 avg: %.6fms%n", "Redis", redisReadAvg, redisWriteAvg);
        System.out.printf("  %-12s | 읽기 avg: %.6fms | 쓰기 avg: %.6fms%n", "Caffeine", cafReadAvg, cafWriteAvg);
        System.out.printf("  %-12s | 읽기: %.0fx | 쓰기: %.0fx%n", "배율 (R/C)",
                redisReadAvg / Math.max(cafReadAvg, 0.000001),
                redisWriteAvg / Math.max(cafWriteAvg, 0.000001));
        System.out.println("=".repeat(70) + "\n");

        assertThat(cafReadAvg).isLessThan(redisReadAvg); // in-memory는 항상 빨라야 함
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 2: 100 VirtualThread 동시 읽기
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    @DisplayName("[비교] 100 VirtualThread 동시 읽기 — Redis vs Caffeine")
    void concurrentReads() throws Exception {
        int threads = 100;
        int opsPerThread = 1000;

        var redisResult = runConcurrentRead(threads, opsPerThread, "redis");
        var cafResult = runConcurrentRead(threads, opsPerThread, "caffeine");

        System.out.println("\n" + "=".repeat(70));
        System.out.println("  동시 읽기 (threads=%d, ops/thread=%d)".formatted(threads, opsPerThread));
        System.out.println("=".repeat(70));
        System.out.printf("  %-12s | ops/sec: %,12.0f | avg: %.4fms%n", "Redis", redisResult.opsPerSec, redisResult.avgMs);
        System.out.printf("  %-12s | ops/sec: %,12.0f | avg: %.4fms%n", "Caffeine", cafResult.opsPerSec, cafResult.avgMs);
        System.out.printf("  %-12s | 처리량 배율: %.0fx%n", "배율 (C/R)",
                cafResult.opsPerSec / Math.max(redisResult.opsPerSec, 1));
        System.out.println("=".repeat(70) + "\n");
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 3: Cache hit/miss 비율별 성능 (hit 90%, 50%, 10%)
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    @DisplayName("[비교] Hit/Miss 비율별 성능 — Redis vs Caffeine")
    void hitMissRatioPerformance() {
        int ops = 50_000;

        System.out.println("\n" + "=".repeat(70));
        System.out.println("  Hit/Miss 비율별 성능 (%d ops)".formatted(ops));
        System.out.println("=".repeat(70));
        System.out.printf("  %-12s | %-10s | avg(ms)%n", "벤더", "Hit Rate");
        System.out.println("  " + "-".repeat(40));

        for (double hitRate : new double[]{0.9, 0.5, 0.1}) {
            double redisAvg = measureHitMissPerformance(ops, hitRate, "redis");
            double cafAvg = measureHitMissPerformance(ops, hitRate, "caffeine");

            System.out.printf("  %-12s | %8.0f%% | %.6f%n", "Redis", hitRate * 100, redisAvg);
            System.out.printf("  %-12s | %8.0f%% | %.6f%n", "Caffeine", hitRate * 100, cafAvg);
            System.out.println("  " + "-".repeat(40));
        }
        System.out.println("=".repeat(70) + "\n");
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 4: 대용량 값 (1KB, 10KB, 100KB) — 직렬화 오버헤드
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(4)
    @DisplayName("[비교] 대용량 값 직렬화 오버헤드 — Redis vs Caffeine")
    void largeValueOverhead() {
        int ops = 1000;

        System.out.println("\n" + "=".repeat(80));
        System.out.println("  대용량 값 직렬화 오버헤드 (%d ops)".formatted(ops));
        System.out.println("=".repeat(80));
        System.out.printf("  %-12s | %-8s | 쓰기 avg(ms) | 읽기 avg(ms)%n", "벤더", "크기");
        System.out.println("  " + "-".repeat(54));

        for (int sizeKB : new int[]{1, 10, 100}) {
            String value = "x".repeat(sizeKB * 1024);

            // Redis 쓰기
            long rws = System.nanoTime();
            for (int i = 0; i < ops; i++) jedis.set("large:" + i, value);
            double redisWriteAvg = (System.nanoTime() - rws) / 1_000_000.0 / ops;

            // Redis 읽기
            long rrs = System.nanoTime();
            for (int i = 0; i < ops; i++) jedis.get("large:" + i);
            double redisReadAvg = (System.nanoTime() - rrs) / 1_000_000.0 / ops;

            // Caffeine 쓰기
            long cws = System.nanoTime();
            for (int i = 0; i < ops; i++) caffeineCache.put("large:" + i, value);
            double cafWriteAvg = (System.nanoTime() - cws) / 1_000_000.0 / ops;

            // Caffeine 읽기
            long crs = System.nanoTime();
            for (int i = 0; i < ops; i++) caffeineCache.getIfPresent("large:" + i);
            double cafReadAvg = (System.nanoTime() - crs) / 1_000_000.0 / ops;

            System.out.printf("  %-12s | %5dKB | %12.4f | %12.4f%n", "Redis", sizeKB, redisWriteAvg, redisReadAvg);
            System.out.printf("  %-12s | %5dKB | %12.4f | %12.4f%n", "Caffeine", sizeKB, cafWriteAvg, cafReadAvg);
            System.out.println("  " + "-".repeat(54));
        }
        System.out.println("=".repeat(80) + "\n");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────
    private CacheResult runConcurrentRead(int threads, int opsPerThread, String vendor) throws Exception {
        AtomicInteger totalOps = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < threads; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        // 각 스레드에 별도 Jedis 연결 (Jedis는 스레드 세이프하지 않음)
                        Jedis threadJedis = "redis".equals(vendor)
                                ? new Jedis(redis.getHost(), redis.getMappedPort(6379))
                                : null;
                        latch.await();
                        for (int i = 0; i < opsPerThread; i++) {
                            String key = "feed:" + (i % SEED_COUNT);
                            if ("redis".equals(vendor)) {
                                threadJedis.get(key);
                            } else {
                                caffeineCache.getIfPresent(key);
                            }
                            totalOps.incrementAndGet();
                        }
                        if (threadJedis != null) threadJedis.close();
                    } catch (Exception e) { /* ignore */ }
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(60, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            double opsPerSec = totalOps.get() * 1000.0 / totalTime;
            double avgMs = totalTime * 1.0 / totalOps.get();
            return new CacheResult(opsPerSec, avgMs);
        }
    }

    private double measureHitMissPerformance(int ops, double hitRate, String vendor) {
        int hitBoundary = (int) (SEED_COUNT * hitRate);

        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            // hitRate 비율만큼 존재하는 키를 조회, 나머지는 miss 키
            String key = (i % SEED_COUNT < hitBoundary)
                    ? "feed:" + (i % hitBoundary)
                    : "miss:" + i;
            if ("redis".equals(vendor)) {
                jedis.get(key);
            } else {
                caffeineCache.getIfPresent(key);
            }
        }
        return (System.nanoTime() - start) / 1_000_000.0 / ops;
    }

    // ── 결과 레코드 ───────────────────────────────────────────
    record CacheResult(double opsPerSec, double avgMs) {}
}
