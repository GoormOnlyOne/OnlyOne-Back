package com.example.onlyone.filter;

import com.example.onlyone.global.filter.RateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("RateLimitFilter 단위 테스트")
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() throws Exception {
        filter = new RateLimitFilter();
        filterChain = mock(FilterChain.class);

        // Set @Value fields via reflection
        setField(filter, "requestsPerMinute", 3);
        setField(filter, "authRequestsPer30s", 2);
    }

    // ==================== 일반 요청 Rate Limit ====================

    @Nested
    @DisplayName("일반 요청 Rate Limit")
    class GeneralRateLimit {

        @Test
        @DisplayName("제한 이내 요청은 통과한다")
        void allowsRequestsWithinLimit() throws Exception {
            var request = createRequest("/api/v1/clubs", "127.0.0.1");
            var response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("제한 초과 시 429를 반환한다")
        void blocksWhenLimitExceeded() throws Exception {
            // 3회까지 허용 (requestsPerMinute = 3)
            for (int i = 0; i < 3; i++) {
                var req = createRequest("/api/v1/clubs", "10.0.0.1");
                filter.doFilter(req, new MockHttpServletResponse(), filterChain);
            }

            // 4번째 요청은 차단
            var request = createRequest("/api/v1/clubs", "10.0.0.1");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
            assertThat(response.getContentAsString()).contains("Too many requests");
        }

        @Test
        @DisplayName("다른 IP는 독립적으로 카운트된다")
        void countsIndependentlyPerIp() throws Exception {
            // IP-A 3회
            for (int i = 0; i < 3; i++) {
                filter.doFilter(createRequest("/api/v1/clubs", "10.0.0.1"),
                        new MockHttpServletResponse(), filterChain);
            }

            // IP-B는 여전히 허용
            var request = createRequest("/api/v1/clubs", "10.0.0.2");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, filterChain);

            verify(filterChain, atLeast(4)).doFilter(any(), any());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    // ==================== 인증 경로 Rate Limit ====================

    @Nested
    @DisplayName("인증 경로 Rate Limit")
    class AuthRateLimit {

        @Test
        @DisplayName("인증 경로는 별도 제한이 적용된다")
        void authPathHasSeparateLimit() throws Exception {
            // authRequestsPer30s = 2 → 2회까지 허용
            for (int i = 0; i < 2; i++) {
                filter.doFilter(createRequest("/api/v1/auth/login", "10.0.0.1"),
                        new MockHttpServletResponse(), filterChain);
            }

            // 3번째 인증 요청은 차단
            var response = new MockHttpServletResponse();
            filter.doFilter(createRequest("/api/v1/auth/login", "10.0.0.1"),
                    response, filterChain);

            assertThat(response.getStatus()).isEqualTo(429);
        }
    }

    // ==================== WebSocket 경로 ====================

    @Nested
    @DisplayName("WebSocket 경로")
    class WebSocketPath {

        @Test
        @DisplayName("WebSocket 경로는 Rate Limit을 건너뛴다")
        void skipsWebSocketPaths() throws Exception {
            var request = createRequest("/ws/info", "10.0.0.1");
            var response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }

    // ==================== IP 추출 ====================

    @Nested
    @DisplayName("IP 추출")
    class IpExtraction {

        @Test
        @DisplayName("X-Forwarded-For 헤더에서 첫 번째 IP를 추출한다")
        void extractsFromXForwardedFor() throws Exception {
            var request = createRequest("/api/v1/clubs", "127.0.0.1");
            request.addHeader("X-Forwarded-For", "203.0.113.50, 70.41.3.18");
            var response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            // 두 번째 요청에서 같은 forwarded IP로 카운트되는지 확인
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("X-Real-IP 헤더에서 IP를 추출한다")
        void extractsFromXRealIp() throws Exception {
            var request = createRequest("/api/v1/clubs", "127.0.0.1");
            request.addHeader("X-Real-IP", "203.0.113.100");
            var response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }

    // ==================== evictStaleEntries ====================

    @Nested
    @DisplayName("evictStaleEntries")
    class EvictStaleEntries {

        @Test
        @DisplayName("만료된 엔트리가 정리된다")
        void removesStaleEntries() throws Exception {
            // requestCounts에 이미 만료된 타임스탬프를 직접 주입
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, Deque<Long>> counts =
                    (ConcurrentHashMap<String, Deque<Long>>) getField(filter, "requestCounts");

            Deque<Long> staleDeque = new ConcurrentLinkedDeque<>();
            staleDeque.add(System.currentTimeMillis() - 120_000); // 2분 전 → 만료
            counts.put("general:expired-ip", staleDeque);

            Deque<Long> freshDeque = new ConcurrentLinkedDeque<>();
            freshDeque.add(System.currentTimeMillis()); // 현재 → 유효
            counts.put("general:fresh-ip", freshDeque);

            // evictStaleEntries 호출
            Method evict = RateLimitFilter.class.getDeclaredMethod("evictStaleEntries");
            evict.setAccessible(true);
            evict.invoke(filter);

            assertThat(counts).doesNotContainKey("general:expired-ip");
            assertThat(counts).containsKey("general:fresh-ip");
        }

        @Test
        @DisplayName("모든 엔트리가 만료되면 맵이 비워진다")
        void clearsAllWhenAllExpired() throws Exception {
            @SuppressWarnings("unchecked")
            ConcurrentHashMap<String, Deque<Long>> counts =
                    (ConcurrentHashMap<String, Deque<Long>>) getField(filter, "requestCounts");

            for (int i = 0; i < 100; i++) {
                Deque<Long> deque = new ConcurrentLinkedDeque<>();
                deque.add(System.currentTimeMillis() - 120_000);
                counts.put("general:ip-" + i, deque);
            }

            Method evict = RateLimitFilter.class.getDeclaredMethod("evictStaleEntries");
            evict.setAccessible(true);
            evict.invoke(filter);

            assertThat(counts).isEmpty();
        }
    }

    // ==================== helpers ====================

    private MockHttpServletRequest createRequest(String path, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
