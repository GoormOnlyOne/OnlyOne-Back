package com.example.onlyone.notification.comparison;

import org.junit.jupiter.api.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSE vs WebSocket vs WebFlux 실시간 전송 동시성 + 메모리 비교 테스트.
 *
 * <p>각 전송 방식의 연결 생성/해제 성능, 동시 전송 처리, 연결 폭주 내성을
 * JVM 레벨에서 비교한다. WebSocket은 {@link org.springframework.messaging.simp.SimpMessagingTemplate}
 * 대신 in-memory 큐 시뮬레이션으로 측정한다.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("SSE vs WebSocket vs WebFlux — 실시간 전송")
class DeliveryBenchmark {

    // ────────────────────────────────────────────────────────────
    // 테스트 1: 1000 연결 생성/해제 — 소요 시간 + 메모리
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    @DisplayName("[비교] 1000 연결 생성/해제 — SSE vs WebFlux vs WebSocket-sim")
    void connectionCreateAndClose() throws Exception {
        int count = 1000;

        var sseResult = measureSseConnections(count);
        var fluxResult = measureWebFluxConnections(count);
        var wsResult = measureWebSocketSimConnections(count);

        System.out.println("\n" + "=".repeat(85));
        System.out.println("  1000 연결 생성/해제 성능 + 메모리");
        System.out.println("=".repeat(85));
        System.out.printf("  %-15s | 생성(ms) | 해제(ms) | 연결당 메모리(bytes) | 총 메모리(KB)%n", "방식");
        System.out.println("  " + "-".repeat(71));
        printConnectionRow("SSE (SseEmitter)", sseResult);
        printConnectionRow("WebFlux (Sinks)", fluxResult);
        printConnectionRow("WebSocket (sim)", wsResult);
        System.out.println("=".repeat(85) + "\n");

        assertThat(sseResult.createTimeMs).isGreaterThan(0);
        assertThat(fluxResult.createTimeMs).isGreaterThan(0);
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 2: 500 동시 연결 + 100 동시 전송
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    @DisplayName("[비교] 500 연결 + 100 동시 전송 — SSE vs WebFlux vs WebSocket-sim")
    void concurrentSend() throws Exception {
        int connections = 500;
        int senders = 100;

        var sseResult = measureSseConcurrentSend(connections, senders);
        var fluxResult = measureWebFluxConcurrentSend(connections, senders);
        var wsResult = measureWebSocketSimConcurrentSend(connections, senders);

        System.out.println("\n" + "=".repeat(85));
        System.out.println("  500 연결 + 100 동시 전송");
        System.out.println("=".repeat(85));
        System.out.printf("  %-15s | 성공률      | 전송 avg(ms) | 전송 p95(ms) | 총 시간(ms)%n", "방식");
        System.out.println("  " + "-".repeat(68));
        printSendRow("SSE (SseEmitter)", sseResult);
        printSendRow("WebFlux (Sinks)", fluxResult);
        printSendRow("WebSocket (sim)", wsResult);
        System.out.println("=".repeat(85) + "\n");
    }

    // ────────────────────────────────────────────────────────────
    // 테스트 3: 연결 폭주 (5000 동시 연결 시도)
    // ────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    @DisplayName("[비교] 5000 동시 연결 시도 — SSE vs WebFlux vs WebSocket-sim")
    void connectionBurst() throws Exception {
        int burst = 5000;

        var sseResult = measureSseBurst(burst);
        var fluxResult = measureWebFluxBurst(burst);
        var wsResult = measureWebSocketSimBurst(burst);

        System.out.println("\n" + "=".repeat(85));
        System.out.println("  5000 동시 연결 폭주");
        System.out.println("=".repeat(85));
        System.out.printf("  %-15s | 활성 연결 | OOM | 소요(ms) | 메모리 증가(MB)%n", "방식");
        System.out.println("  " + "-".repeat(62));
        printBurstRow("SSE (SseEmitter)", sseResult);
        printBurstRow("WebFlux (Sinks)", fluxResult);
        printBurstRow("WebSocket (sim)", wsResult);
        System.out.println("=".repeat(85) + "\n");

        assertThat(sseResult.oomOccurred).isFalse();
        assertThat(fluxResult.oomOccurred).isFalse();
    }

    // ── SSE (SseEmitter) 측정 ──────────────────────────────────
    private ConnectionResult measureSseConnections(int count) {
        ConcurrentHashMap<Long, SseEmitter> map = new ConcurrentHashMap<>();

        long memBefore = usedMemory();
        long createStart = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            SseEmitter emitter = new SseEmitter(60_000L);
            map.put((long) i, emitter);
        }
        long createTime = System.currentTimeMillis() - createStart;
        long memAfter = usedMemory();

        long closeStart = System.currentTimeMillis();
        map.values().forEach(SseEmitter::complete);
        map.clear();
        long closeTime = System.currentTimeMillis() - closeStart;

        long perConn = count > 0 ? (memAfter - memBefore) / count : 0;
        return new ConnectionResult(createTime, closeTime, perConn, (memAfter - memBefore) / 1024);
    }

    private SendResult measureSseConcurrentSend(int connections, int senders) throws Exception {
        ConcurrentHashMap<Long, SseEmitter> map = new ConcurrentHashMap<>();
        for (int i = 0; i < connections; i++) {
            SseEmitter emitter = new SseEmitter(60_000L);
            map.put((long) i, emitter);
        }

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int s = 0; s < senders; s++) {
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < connections; i++) {
                            long targetId = ThreadLocalRandom.current().nextLong(connections);
                            SseEmitter emitter = map.get(targetId);
                            if (emitter == null) continue;
                            long start = System.nanoTime();
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("notification")
                                        .data("{\"msg\":\"test\"}"));
                                success.incrementAndGet();
                            } catch (IOException | IllegalStateException e) {
                                failure.incrementAndGet();
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

            map.values().forEach(SseEmitter::complete);
            map.clear();

            return computeSendResult(success.get(), failure.get(), latencies, totalTime);
        }
    }

    private BurstResult measureSseBurst(int burst) {
        ConcurrentHashMap<Long, SseEmitter> map = new ConcurrentHashMap<>();
        long memBefore = usedMemory();
        boolean oom = false;

        long start = System.currentTimeMillis();
        try {
            for (int i = 0; i < burst; i++) {
                map.put((long) i, new SseEmitter(60_000L));
            }
        } catch (OutOfMemoryError e) {
            oom = true;
        }
        long elapsed = System.currentTimeMillis() - start;
        long memAfter = usedMemory();

        int active = map.size();
        map.values().forEach(SseEmitter::complete);
        map.clear();

        return new BurstResult(active, oom, elapsed, (memAfter - memBefore) / (1024 * 1024));
    }

    // ── WebFlux (Sinks.Many) 측정 ──────────────────────────────
    private ConnectionResult measureWebFluxConnections(int count) {
        ConcurrentHashMap<Long, Sinks.Many<ServerSentEvent<String>>> map = new ConcurrentHashMap<>();

        long memBefore = usedMemory();
        long createStart = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer(256);
            map.put((long) i, sink);
        }
        long createTime = System.currentTimeMillis() - createStart;
        long memAfter = usedMemory();

        long closeStart = System.currentTimeMillis();
        map.values().forEach(Sinks.Many::tryEmitComplete);
        map.clear();
        long closeTime = System.currentTimeMillis() - closeStart;

        long perConn = count > 0 ? (memAfter - memBefore) / count : 0;
        return new ConnectionResult(createTime, closeTime, perConn, (memAfter - memBefore) / 1024);
    }

    private SendResult measureWebFluxConcurrentSend(int connections, int senders) throws Exception {
        ConcurrentHashMap<Long, Sinks.Many<ServerSentEvent<String>>> map = new ConcurrentHashMap<>();
        for (int i = 0; i < connections; i++) {
            Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer(256);
            map.put((long) i, sink);
            // subscribe to drain
            sink.asFlux().subscribe();
        }

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int s = 0; s < senders; s++) {
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < connections; i++) {
                            long targetId = ThreadLocalRandom.current().nextLong(connections);
                            Sinks.Many<ServerSentEvent<String>> sink = map.get(targetId);
                            if (sink == null) continue;
                            long start = System.nanoTime();
                            ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                                    .event("notification").data("{\"msg\":\"test\"}").build();
                            Sinks.EmitResult result = sink.tryEmitNext(event);
                            if (result.isSuccess()) success.incrementAndGet();
                            else failure.incrementAndGet();
                            latencies.add((System.nanoTime() - start) / 1_000_000);
                        }
                    } catch (Exception e) { /* ignore */ }
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(60, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            map.values().forEach(Sinks.Many::tryEmitComplete);
            map.clear();

            return computeSendResult(success.get(), failure.get(), latencies, totalTime);
        }
    }

    private BurstResult measureWebFluxBurst(int burst) {
        ConcurrentHashMap<Long, Sinks.Many<ServerSentEvent<String>>> map = new ConcurrentHashMap<>();
        long memBefore = usedMemory();
        boolean oom = false;

        long start = System.currentTimeMillis();
        try {
            for (int i = 0; i < burst; i++) {
                map.put((long) i, Sinks.many().multicast().onBackpressureBuffer(256));
            }
        } catch (OutOfMemoryError e) {
            oom = true;
        }
        long elapsed = System.currentTimeMillis() - start;
        long memAfter = usedMemory();

        int active = map.size();
        map.values().forEach(Sinks.Many::tryEmitComplete);
        map.clear();

        return new BurstResult(active, oom, elapsed, (memAfter - memBefore) / (1024 * 1024));
    }

    // ── WebSocket 시뮬레이션 (in-memory 큐) ────────────────────
    // 실제 STOMP 인프라 없이 ConcurrentLinkedQueue로 메시지 전달을 시뮬레이션
    private ConnectionResult measureWebSocketSimConnections(int count) {
        ConcurrentHashMap<Long, ConcurrentLinkedQueue<String>> map = new ConcurrentHashMap<>();

        long memBefore = usedMemory();
        long createStart = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            map.put((long) i, new ConcurrentLinkedQueue<>());
        }
        long createTime = System.currentTimeMillis() - createStart;
        long memAfter = usedMemory();

        long closeStart = System.currentTimeMillis();
        map.clear();
        long closeTime = System.currentTimeMillis() - closeStart;

        long perConn = count > 0 ? (memAfter - memBefore) / count : 0;
        return new ConnectionResult(createTime, closeTime, perConn, (memAfter - memBefore) / 1024);
    }

    private SendResult measureWebSocketSimConcurrentSend(int connections, int senders) throws Exception {
        ConcurrentHashMap<Long, ConcurrentLinkedQueue<String>> map = new ConcurrentHashMap<>();
        for (int i = 0; i < connections; i++) {
            map.put((long) i, new ConcurrentLinkedQueue<>());
        }

        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        List<Long> latencies = new CopyOnWriteArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch latch = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();

            for (int s = 0; s < senders; s++) {
                futures.add(executor.submit(() -> {
                    try {
                        latch.await();
                        for (int i = 0; i < connections; i++) {
                            long targetId = ThreadLocalRandom.current().nextLong(connections);
                            ConcurrentLinkedQueue<String> queue = map.get(targetId);
                            if (queue == null) continue;
                            long start = System.nanoTime();
                            queue.offer("{\"msg\":\"test\"}");
                            success.incrementAndGet();
                            latencies.add((System.nanoTime() - start) / 1_000_000);
                        }
                    } catch (Exception e) { /* ignore */ }
                }));
            }

            long startTime = System.currentTimeMillis();
            latch.countDown();
            for (var f : futures) f.get(60, TimeUnit.SECONDS);
            long totalTime = System.currentTimeMillis() - startTime;

            map.clear();
            return computeSendResult(success.get(), failure.get(), latencies, totalTime);
        }
    }

    private BurstResult measureWebSocketSimBurst(int burst) {
        ConcurrentHashMap<Long, ConcurrentLinkedQueue<String>> map = new ConcurrentHashMap<>();
        long memBefore = usedMemory();
        boolean oom = false;

        long start = System.currentTimeMillis();
        try {
            for (int i = 0; i < burst; i++) {
                map.put((long) i, new ConcurrentLinkedQueue<>());
            }
        } catch (OutOfMemoryError e) {
            oom = true;
        }
        long elapsed = System.currentTimeMillis() - start;
        long memAfter = usedMemory();

        int active = map.size();
        map.clear();

        return new BurstResult(active, oom, elapsed, (memAfter - memBefore) / (1024 * 1024));
    }

    // ── 유틸리티 ───────────────────────────────────────────────
    private long usedMemory() {
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private SendResult computeSendResult(int success, int failure, List<Long> latencies, long totalTimeMs) {
        double successRate = (success + failure) > 0 ? success * 100.0 / (success + failure) : 0;
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        double p95 = sorted.isEmpty() ? 0 : sorted.get(Math.min((int) (sorted.size() * 0.95), sorted.size() - 1));
        return new SendResult(successRate, avg, p95, totalTimeMs);
    }

    private void printConnectionRow(String label, ConnectionResult r) {
        System.out.printf("  %-15s | %8d | %8d | %19d | %12d%n",
                label, r.createTimeMs, r.closeTimeMs, r.perConnectionBytes, r.totalMemoryKB);
    }

    private void printSendRow(String label, SendResult r) {
        System.out.printf("  %-15s | %9.1f%% | %12.2f | %12.2f | %11d%n",
                label, r.successRate, r.avgMs, r.p95Ms, r.totalTimeMs);
    }

    private void printBurstRow(String label, BurstResult r) {
        System.out.printf("  %-15s | %8d | %3s | %8d | %14d%n",
                label, r.activeConnections, r.oomOccurred ? "YES" : "NO",
                r.elapsedMs, r.memoryIncreaseMB);
    }

    // ── 결과 레코드 ───────────────────────────────────────────
    record ConnectionResult(long createTimeMs, long closeTimeMs, long perConnectionBytes, long totalMemoryKB) {}
    record SendResult(double successRate, double avgMs, double p95Ms, long totalTimeMs) {}
    record BurstResult(int activeConnections, boolean oomOccurred, long elapsedMs, long memoryIncreaseMB) {}
}
