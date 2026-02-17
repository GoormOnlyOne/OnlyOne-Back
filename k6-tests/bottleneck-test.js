import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// 병목 구간 식별 전용 테스트
// 각 잠재적 병목 포인트를 집중적으로 테스트
// ============================================

// 커스텀 메트릭
const dbConnectionPoolExhaustion = new Rate('db_connection_pool_exhaustion');
const cacheInvalidationRate = new Rate('cache_invalidation_rate');
const batchQueueOverflow = new Rate('batch_queue_overflow');
const sseConnectionLimit = new Rate('sse_connection_limit_reached');
const nginxBottleneck = new Rate('nginx_bottleneck');

const dbQueryDuration = new Trend('db_query_duration');
const cacheHitRate = new Gauge('cache_hit_rate_gauge');
const batchProcessingLag = new Trend('batch_processing_lag');
const sseConnectionTime = new Trend('sse_connection_time');
const nginxResponseTime = new Trend('nginx_response_time');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // 병목 1: DB 연결 풀 고갈 테스트
        db_connection_pool_test: {
            executor: 'ramping-vus',
            exec: 'testDBConnectionPool',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 100 },
                { duration: '1m', target: 300 },   // HikariCP default: 10
                { duration: '1m', target: 500 },   // MySQL max: 500
                { duration: '1m', target: 700 },   // 초과 - 에러 예상
                { duration: '30s', target: 0 },
            ],
            gracefulRampDown: '30s',
        },

        // 병목 2: Redis 캐시 효율성 테스트
        cache_efficiency_test: {
            executor: 'constant-vus',
            exec: 'testCacheEfficiency',
            vus: 200,
            duration: '3m',
            startTime: '5m',
            gracefulRampDown: '30s',
        },

        // 병목 3: 배치 처리 큐 오버플로우 테스트
        batch_queue_overflow_test: {
            executor: 'ramping-arrival-rate',
            exec: 'testBatchQueueOverflow',
            startRate: 100,
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 500,
            stages: [
                { duration: '1m', target: 500 },   // 초당 500개 알림 생성
                { duration: '2m', target: 1000 },  // 초당 1000개 - 큐 포화 예상
                { duration: '1m', target: 2000 },  // 초당 2000개 - 오버플로우
                { duration: '1m', target: 500 },
            ],
            startTime: '9m',
            gracefulStop: '30s',
        },

        // 병목 4: SSE 연결 한계 테스트
        sse_connection_limit_test: {
            executor: 'ramping-vus',
            exec: 'testSSEConnectionLimit',
            startVUs: 0,
            stages: [
                { duration: '2m', target: 1000 },
                { duration: '2m', target: 3000 },
                { duration: '2m', target: 5000 },
                { duration: '2m', target: 7000 },  // 설정 한계
                { duration: '2m', target: 8000 },  // 초과 - 에러 예상
                { duration: '1m', target: 0 },
            ],
            startTime: '16m',
            gracefulRampDown: '30s',
        },

        // 병목 5: Nginx 로드 밸런싱 불균형 테스트
        nginx_load_balance_test: {
            executor: 'constant-arrival-rate',
            exec: 'testNginxLoadBalance',
            rate: 500,  // 초당 500 요청
            timeUnit: '1s',
            duration: '3m',
            preAllocatedVUs: 50,
            maxVUs: 200,
            startTime: '28m',
            gracefulStop: '30s',
        },

        // 병목 6: N+1 쿼리 문제 테스트
        n_plus_one_query_test: {
            executor: 'constant-vus',
            exec: 'testNPlusOneQuery',
            vus: 100,
            duration: '3m',
            startTime: '32m',
            gracefulRampDown: '30s',
        },

        // 병목 7: 동시 쓰기 경합 테스트
        concurrent_write_contention_test: {
            executor: 'constant-vus',
            exec: 'testConcurrentWriteContention',
            vus: 300,
            duration: '3m',
            startTime: '36m',
            gracefulRampDown: '30s',
        },
    },

    thresholds: {
        db_connection_pool_exhaustion: ['rate<0.1'],  // 10% 미만
        cache_hit_rate_gauge: ['value>0.8'],           // 80% 이상
        batch_queue_overflow: ['rate<0.05'],           // 5% 미만
        sse_connection_limit_reached: ['rate<0.1'],    // 10% 미만
        http_req_duration: ['p(95)<1000'],
        http_req_failed: ['rate<0.05'],
    },
};

// 테스트 사용자
const testUsers = new SharedArray('bottleneck_test_users', function () {
    const users = [];
    for (let i = 1; i <= 5000; i++) {
        users.push({
            userId: i,
            kakaoId: 10000000 + i,
            status: 'ACTIVE',
            role: 'ROLE_USER',
        });
    }
    return users;
});

// ============================================
// 유틸리티
// ============================================

function generateJWT(user) {
    const now = Date.now();
    const expiryDate = now + (3600 * 1000);  // 1시간 후

    // JWT Header
    const header = {
        alg: 'HS512',
        typ: 'JWT'
    };

    // JWT Payload
    const payload = {
        sub: user.userId.toString(),
        kakaoId: user.kakaoId.toString(),
        nickname: `testuser${user.userId}`,
        status: user.status,
        role: user.role,
        type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor(expiryDate / 1000)
    };

    // Base64 URL Encoding
    const headerEncoded = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const payloadEncoded = encoding.b64encode(JSON.stringify(payload), 'rawurl');

    // Signature
    const signatureInput = `${headerEncoded}.${payloadEncoded}`;
    const signature = hmac('sha512', JWT_SECRET, signatureInput, 'base64rawurl');

    // JWT Token
    return `${signatureInput}.${signature}`;
}

function getHeaders(token) {
    return {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
    };
}

function getRandomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

// ============================================
// 병목 1: DB 연결 풀 고갈 테스트
// ============================================
export function testDBConnectionPool() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('DB Connection Pool Test', () => {
        // 무거운 쿼리 (JOIN이 많은 알림 목록 조회)
        const startTime = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=50`, { headers });
        const duration = Date.now() - startTime;

        dbQueryDuration.add(duration);

        const success = check(res, {
            'DB query successful': (r) => r.status === 200,
            'No connection timeout': (r) => !r.body.includes('Communications link failure'),
            'No connection pool exhausted': (r) => !r.body.includes('Connection is not available'),
        });

        if (!success) {
            dbConnectionPoolExhaustion.add(1);
            console.error(`DB Connection Pool Exhaustion detected! Status: ${res.status}`);
        } else {
            dbConnectionPoolExhaustion.add(0);
        }

        // 연결 풀 상태 로깅
        if (__ITER % 100 === 0) {
            console.log(`[DB Pool] VUs: ${__VU}, Iterations: ${__ITER}, Duration: ${duration}ms`);
        }
    });

    sleep(0.1);  // 짧은 대기 시간으로 압력 유지
}

// ============================================
// 병목 2: Redis 캐시 효율성 테스트
// ============================================
export function testCacheEfficiency() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    let cacheHitCount = 0;
    let totalRequests = 0;

    group('Cache Efficiency Test', () => {
        // Phase 1: 캐시 워밍업 (첫 요청은 캐시 미스)
        const res1 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
        totalRequests++;

        sleep(0.5);

        // Phase 2: 반복 요청 (캐시 히트 예상)
        for (let i = 0; i < 5; i++) {
            const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
            totalRequests++;

            if (res.status === 200) {
                const body = JSON.parse(res.body);
                if (body.cached === true || res.timings.duration < 50) {
                    cacheHitCount++;
                }
            }

            sleep(0.2);
        }

        // Phase 3: 캐시 무효화 (쓰기 작업)
        const notificationId = Math.floor(Math.random() * 10000000) + 1;
        const res3 = http.put(
            `${BASE_URL}/api/v1/notifications/${notificationId}/read`,
            null,
            { headers }
        );

        check(res3, {
            'Cache invalidation triggered': (r) => r.status === 200 || r.status === 404,
        });

        if (res3.status === 200) {
            cacheInvalidationRate.add(1);
        } else {
            cacheInvalidationRate.add(0);
        }

        // Phase 4: 캐시 미스 확인 (무효화 후)
        const res4 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
        totalRequests++;

        // 캐시 히트율 계산
        const hitRate = totalRequests > 0 ? cacheHitCount / totalRequests : 0;
        cacheHitRate.add(hitRate);

        if (__ITER % 50 === 0) {
            console.log(`[Cache] Hit Rate: ${(hitRate * 100).toFixed(2)}%, Hits: ${cacheHitCount}/${totalRequests}`);
        }
    });

    sleep(1);
}

// ============================================
// 병목 3: 배치 처리 큐 오버플로우 테스트
// ============================================
export function testBatchQueueOverflow() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Batch Queue Overflow Test', () => {
        // 배치 상태 확인
        const statusRes = http.get(`${BASE_URL}/api/v1/notifications/batch-status`, { headers });

        if (statusRes.status === 200) {
            const status = JSON.parse(statusRes.body).data;
            const queuedNotifications = status.totalQueuedNotifications || 0;
            const activeUsers = status.activeUsers || 0;

            // 큐 포화 감지 (사용자당 100개 제한)
            const averageQueueSize = activeUsers > 0 ? queuedNotifications / activeUsers : 0;

            if (averageQueueSize > 80) {
                batchQueueOverflow.add(1);
                console.warn(`[Batch Queue] Overflow warning! Avg queue size: ${averageQueueSize.toFixed(2)}`);
            } else {
                batchQueueOverflow.add(0);
            }

            // 배치 처리 지연 측정 (큐 크기가 클수록 지연 증가)
            batchProcessingLag.add(queuedNotifications * 0.05);  // 추정값

            if (__ITER % 100 === 0) {
                console.log(`[Batch Queue] Active Users: ${activeUsers}, Queued: ${queuedNotifications}, Avg: ${averageQueueSize.toFixed(2)}`);
            }
        }

        // 대량 알림 생성 시뮬레이션 (실제로는 서버 내부에서 생성)
        // 여기서는 읽음 처리로 대체 (캐시 무효화 → 배치 큐 추가)
        for (let i = 0; i < 5; i++) {
            const notificationId = Math.floor(Math.random() * 10000000) + 1;
            http.put(`${BASE_URL}/api/v1/notifications/${notificationId}/read`, null, { headers });
        }
    });

    sleep(0.1);
}

// ============================================
// 병목 4: SSE 연결 한계 테스트
// ============================================
export function testSSEConnectionLimit() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = {
        'Authorization': `Bearer ${token}`,
        'Accept': 'text/event-stream',
        'Cache-Control': 'no-cache',
    };

    group('SSE Connection Limit Test', () => {
        const startTime = Date.now();
        const res = http.get(`${BASE_URL}/sse/connect`, {
            headers: headers,
            timeout: '60s',
        });
        const duration = Date.now() - startTime;

        sseConnectionTime.add(duration);

        const success = check(res, {
            'SSE connection established': (r) => r.status === 200,
            'SSE content-type correct': (r) =>
                r.headers['Content-Type'] && r.headers['Content-Type'].includes('text/event-stream'),
            'No connection refused': (r) => !r.error.includes('connection refused'),
        });

        if (!success) {
            sseConnectionLimit.add(1);
            console.error(`[SSE] Connection limit reached! VUs: ${__VU}, Status: ${res.status}`);
        } else {
            sseConnectionLimit.add(0);
        }

        // SSE 연결 수 로깅
        if (__ITER % 100 === 0) {
            console.log(`[SSE] VUs: ${__VU}, Connection Time: ${duration}ms`);
        }
    });

    sleep(5);  // SSE 연결 유지 시뮬레이션
}

// ============================================
// 병목 5: Nginx 로드 밸런싱 불균형 테스트
// ============================================
export function testNginxLoadBalance() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Nginx Load Balance Test', () => {
        const startTime = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=10`, { headers });
        const duration = Date.now() - startTime;

        nginxResponseTime.add(duration);

        // Nginx 헤더 확인 (업스트림 서버 정보)
        const upstreamAddr = res.headers['X-Upstream-Addr'] || 'unknown';

        check(res, {
            'Nginx response successful': (r) => r.status === 200,
            'Response time acceptable': (r) => r.timings.duration < 1000,
        });

        // 특정 서버로만 요청이 몰리는지 감지
        if (duration > 1000) {
            nginxBottleneck.add(1);
            console.warn(`[Nginx] Slow response from ${upstreamAddr}: ${duration}ms`);
        } else {
            nginxBottleneck.add(0);
        }

        if (__ITER % 500 === 0) {
            console.log(`[Nginx] Upstream: ${upstreamAddr}, Response Time: ${duration}ms`);
        }
    });

    sleep(0.1);
}

// ============================================
// 병목 6: N+1 쿼리 문제 테스트
// ============================================
export function testNPlusOneQuery() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('N+1 Query Test', () => {
        // 큰 페이지 크기로 조회 (N+1 문제 발생 시 응답 시간 급증)
        const sizes = [10, 20, 50, 100];

        sizes.forEach(size => {
            const startTime = Date.now();
            const res = http.get(`${BASE_URL}/api/v1/notifications?size=${size}`, { headers });
            const duration = Date.now() - startTime;

            check(res, {
                [`N+1 test with size ${size}`]: (r) => r.status === 200,
            });

            // N+1 문제 감지: 응답 시간이 선형적으로 증가하면 문제
            // (정상: O(1), N+1: O(n))
            const expectedDuration = 200 + (size * 2);  // 기준값
            if (duration > expectedDuration * 2) {
                console.warn(`[N+1] Possible N+1 query! Size: ${size}, Duration: ${duration}ms`);
            }

            console.log(`[N+1] Size: ${size}, Duration: ${duration}ms`);
        });
    });

    sleep(2);
}

// ============================================
// 병목 7: 동시 쓰기 경합 테스트
// ============================================
export function testConcurrentWriteContention() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Concurrent Write Contention Test', () => {
        // 동일한 알림에 대한 동시 읽음 처리 (낙관적 락 테스트)
        const notificationId = 1000 + (__VU % 100);  // 100개의 알림에 집중

        const startTime = Date.now();
        const res = http.put(
            `${BASE_URL}/api/v1/notifications/${notificationId}/read`,
            null,
            { headers }
        );
        const duration = Date.now() - startTime;

        check(res, {
            'Concurrent write successful or expected conflict': (r) =>
                r.status === 200 || r.status === 409 || r.status === 404,
            'No deadlock': (r) => !r.body.includes('Deadlock'),
        });

        if (res.status === 409) {
            console.log(`[Concurrent Write] Optimistic lock conflict on notification ${notificationId}`);
        }

        if (duration > 1000) {
            console.warn(`[Concurrent Write] Slow write on notification ${notificationId}: ${duration}ms`);
        }
    });

    sleep(0.5);
}

// ============================================
// 테스트 라이프사이클
// ============================================

export function setup() {
    console.log('=== Bottleneck Identification Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log(`Test Users: ${testUsers.length}`);
    console.log('');
    console.log('Testing 7 potential bottlenecks:');
    console.log('1. DB Connection Pool (0-5m)');
    console.log('2. Redis Cache Efficiency (5-8m)');
    console.log('3. Batch Queue Overflow (9-15m)');
    console.log('4. SSE Connection Limit (16-27m)');
    console.log('5. Nginx Load Balancing (28-31m)');
    console.log('6. N+1 Query Problem (32-35m)');
    console.log('7. Concurrent Write Contention (36-39m)');
    console.log('');
    console.log('Total Duration: ~40 minutes');
    console.log('================================================');

    // 서버 상태 확인
    const healthCheck = http.get(`${BASE_URL}/actuator/health`);
    if (healthCheck.status !== 200) {
        console.error('ERROR: Server health check failed!');
        throw new Error('Server is not healthy');
    }
}

export function teardown(data) {
    console.log('');
    console.log('=== Bottleneck Identification Test Completed ===');
    console.log('');
    console.log('Review the following metrics:');
    console.log('- db_connection_pool_exhaustion');
    console.log('- cache_hit_rate_gauge');
    console.log('- batch_queue_overflow');
    console.log('- sse_connection_limit_reached');
    console.log('- nginx_bottleneck');
    console.log('');
    console.log('Check logs for warnings and errors.');
    console.log('==================================================');
}
