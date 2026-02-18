package com.example.onlyone.global.filter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String[] AUTH_PATHS = {"/api/v1/auth/", "/api/v1/kakao/", "/api/v1/login/"};
    private static final String[] WS_PATHS = {"/ws", "/ws-native"};
    private static final long AUTH_WINDOW_MS = 30_000L;
    private static final long GENERAL_WINDOW_MS = 60_000L;
    private static final long CLEANUP_INTERVAL_MINUTES = 5L;

    private final ConcurrentHashMap<String, Deque<Long>> requestCounts = new ConcurrentHashMap<>();
    private ScheduledExecutorService cleanupScheduler;

    @Value("${app.rate-limit.requests-per-minute:60}")
    private int requestsPerMinute;

    @Value("${app.rate-limit.auth-requests-per-30s:10}")
    private int authRequestsPer30s;

    @PostConstruct
    void startCleanup() {
        cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupScheduler.scheduleAtFixedRate(this::evictStaleEntries,
                CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    @PreDestroy
    void stopCleanup() {
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdownNow();
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String clientIp = getClientIp(request);
        String path = request.getRequestURI();

        if (isWebSocketPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean isAuthPath = isAuthPath(path);

        String key = isAuthPath ? "auth:" + clientIp : "general:" + clientIp;
        long windowMs = isAuthPath ? AUTH_WINDOW_MS : GENERAL_WINDOW_MS;
        int maxRequests = isAuthPath ? authRequestsPer30s : requestsPerMinute;

        if (isRateLimited(key, windowMs, maxRequests)) {
            log.warn("Rate limit exceeded for IP: {}, path: {}", clientIp, path);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"Too many requests. Please try again later.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isRateLimited(String key, long windowMs, int maxRequests) {
        long now = System.currentTimeMillis();
        Deque<Long> timestamps = requestCounts.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        // Remove expired entries
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMs) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= maxRequests) {
            return true;
        }

        timestamps.addLast(now);
        return false;
    }

    private void evictStaleEntries() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (var it = requestCounts.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Deque<Long> deque = entry.getValue();
            // 만료된 타임스탬프 정리
            while (!deque.isEmpty() && now - deque.peekFirst() > GENERAL_WINDOW_MS) {
                deque.pollFirst();
            }
            // 빈 deque 엔트리 제거
            if (deque.isEmpty()) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Rate limit cleanup: removed {} stale entries, remaining {}", removed, requestCounts.size());
        }
    }

    private boolean isWebSocketPath(String path) {
        for (String wsPath : WS_PATHS) {
            if (path.equals(wsPath) || path.startsWith(wsPath + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean isAuthPath(String path) {
        for (String authPath : AUTH_PATHS) {
            if (path.startsWith(authPath)) {
                return true;
            }
        }
        return false;
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}
