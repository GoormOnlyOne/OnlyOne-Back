// 고성능 알림 시스템 부하 테스트 v3
//
// 목표: 알림 시스템의 한계 성능 측정 및 안정성 검증
// - 최대 500 VU로 동시 접속
// - SSE 연결 + REST API 혼합 고부하
// - 1000만건 알림 DB 기반 실전 시나리오
//
// 시나리오:
//   1. 워밍업 (50 VU, 1m) - 서버 JIT/캐시 예열
//   2. REST 스트레스 (0→300 VU, 5m) - 순수 알림 API 한계 측정
//   3. SSE 대량 연결 (0→200 VU, 3m) - SSE 커넥션 폭탄
//   4. SSE+REST 혼합 고부하 (0→500 VU, 5m) - 실전 피크 시나리오
//   5. 스파이크 (500 VU, 2m) - 급격한 트래픽 유지
//   6. 쿨다운 (500→0, 1m) - graceful 감소
// 총 ~17분, 최대 500 VU

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// 커스텀 메트릭
// ============================================
// SSE
const sseConnectionSuccess = new Rate('sse_connection_success');
const sseConnectionTime = new Trend('sse_connection_time_ms');
const sseConnectedEvent = new Rate('sse_connected_event_received');

// REST 성능
const notifListLatency = new Trend('notif_list_latency_ms');
const unreadCountLatency = new Trend('unread_count_latency_ms');
const markReadLatency = new Trend('mark_read_latency_ms');


// 에러율
const restErrorRate = new Rate('rest_error_rate');
const restSuccessRate = new Rate('rest_success_rate');

// 처리량
const totalNotifOps = new Counter('total_notification_ops');

// ============================================
// 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // 1. 워밍업 - JIT 컴파일, 커넥션풀 예열
        warmup: {
            executor: 'constant-vus',
            exec: 'restWorkload',
            vus: 50,
            duration: '1m',
            gracefulStop: '5s',
        },

        // 2. REST 스트레스 - 순수 알림 API 한계
        rest_stress: {
            executor: 'ramping-vus',
            exec: 'restWorkload',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 100 },
                { duration: '1m', target: 200 },
                { duration: '1m', target: 300 },
                { duration: '1m30s', target: 300 },
                { duration: '30s', target: 0 },
            ],
            startTime: '1m10s',
            gracefulRampDown: '5s',
        },

        // 3. SSE 대량 연결
        sse_flood: {
            executor: 'ramping-vus',
            exec: 'sseFlood',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 100 },
                { duration: '1m', target: 200 },
                { duration: '30s', target: 200 },
                { duration: '30s', target: 0 },
            ],
            startTime: '6m30s',
            gracefulRampDown: '5s',
        },

        // 4. SSE+REST 혼합 고부하 - 실전 피크
        mixed_peak: {
            executor: 'ramping-vus',
            exec: 'mixedHighLoad',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 200 },
                { duration: '1m', target: 400 },
                { duration: '1m', target: 500 },
                { duration: '2m', target: 500 },
            ],
            startTime: '10m',
            gracefulRampDown: '10s',
        },

        // 5. 스파이크 유지
        spike_hold: {
            executor: 'constant-vus',
            exec: 'restWorkload',
            vus: 500,
            duration: '2m',
            startTime: '15m',
            gracefulStop: '10s',
        },
    },

    thresholds: {
        // REST 성능 목표 (고성능 알림 시스템)
        notif_list_latency_ms: ['p(95)<300', 'p(99)<1000'],
        unread_count_latency_ms: ['p(95)<100', 'p(99)<500'],
        mark_read_latency_ms: ['p(95)<200', 'p(99)<500'],
        // SSE 연결
        sse_connection_success: ['rate>0.80'],
        sse_connected_event_received: ['rate>0.70'],

        // REST 에러율
        rest_error_rate: ['rate<0.05'],       // 5% 미만
        rest_success_rate: ['rate>0.95'],      // 95% 이상
    },
};

// ============================================
// 테스트 사용자 (DB에 실제 존재)
// ============================================
const testUsers = new SharedArray('users', function () {
    const users = [];
    for (let i = 1; i <= 1000; i++) {
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
// JWT
// ============================================
function generateJWT(user) {
    const now = Date.now();
    const header = { alg: 'HS512', typ: 'JWT' };
    const payload = {
        sub: user.userId.toString(),
        kakaoId: user.kakaoId.toString(),
        nickname: `testuser${user.userId}`,
        status: user.status,
        role: user.role,
        type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor((now + 3600000) / 1000),
    };
    const h = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const p = encoding.b64encode(JSON.stringify(payload), 'rawurl');
    const sig = hmac('sha512', JWT_SECRET, `${h}.${p}`, 'base64rawurl');
    return `${h}.${p}.${sig}`;
}

function headers(token) {
    return { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' };
}

function sseHeaders(token) {
    return { 'Authorization': `Bearer ${token}`, 'Accept': 'text/event-stream', 'Cache-Control': 'no-cache' };
}

function randomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

function vuUser() {
    return testUsers[__VU % testUsers.length];
}

// ============================================
// SSE 연결 헬퍼
// ============================================
function connectSSE(user, timeout) {
    const token = generateJWT(user);
    const start = Date.now();

    const res = http.get(`${BASE_URL}/sse/subscribe`, {
        headers: sseHeaders(token),
        timeout: timeout || '3s',
        responseType: 'text',
        tags: { name: 'sse_subscribe' },
    });

    const duration = Date.now() - start;
    sseConnectionTime.add(duration);

    // SSE: status 200 = 정상, status 0 = k6 타임아웃 (연결은 수립됨)
    const connected = res.status === 200 || res.status === 0;
    sseConnectionSuccess.add(connected ? 1 : 0);

    // connected 이벤트 파싱
    let hasConnectedEvent = false;
    if (res.body) {
        hasConnectedEvent = res.body.includes('event:connected') || res.body.includes('name:connected');
    }
    sseConnectedEvent.add(hasConnectedEvent ? 1 : 0);

    return { connected, hasConnectedEvent, duration };
}

// ============================================
// 시나리오: REST 워크로드
// ============================================
export function restWorkload() {
    const user = randomUser();
    const token = generateJWT(user);
    const h = headers(token);
    const action = Math.random();

    if (action < 0.30) {
        // 알림 목록 (가장 무거운 쿼리)
        const size = Math.random() < 0.5 ? 20 : 30;
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=${size}`, {
            headers: h, tags: { name: 'GET /notifications' },
        });
        const ok = res.status === 200;
        restSuccessRate.add(ok ? 1 : 0);
        restErrorRate.add(ok ? 0 : 1);
        if (ok) notifListLatency.add(res.timings.duration);
        totalNotifOps.add(1);

    } else if (action < 0.55) {
        // 읽지 않은 개수 (가장 빈번한 호출)
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: h, tags: { name: 'GET /unread-count' },
        });
        const ok = res.status === 200;
        restSuccessRate.add(ok ? 1 : 0);
        restErrorRate.add(ok ? 0 : 1);
        if (ok) unreadCountLatency.add(res.timings.duration);
        totalNotifOps.add(1);

    } else if (action < 0.70) {
        // 읽음 처리
        const notifId = Math.floor(Math.random() * 10000000) + 1;
        const res = http.put(`${BASE_URL}/api/v1/notifications/${notifId}/read`, null, {
            headers: h, tags: { name: 'PUT /read' },
        });
        const ok = res.status === 200 || res.status === 404;
        restSuccessRate.add(ok ? 1 : 0);
        restErrorRate.add(ok ? 0 : 1);
        if (res.status === 200) markReadLatency.add(res.timings.duration);
        totalNotifOps.add(1);

    } else if (action < 0.90) {
        // 목록 + 개수 연속 (실제 UI 패턴)
        const res1 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: h, tags: { name: 'GET /notifications' },
        });
        const res2 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: h, tags: { name: 'GET /unread-count' },
        });
        const ok1 = res1.status === 200;
        const ok2 = res2.status === 200;
        restSuccessRate.add(ok1 ? 1 : 0);
        restSuccessRate.add(ok2 ? 1 : 0);
        restErrorRate.add(ok1 ? 0 : 1);
        restErrorRate.add(ok2 ? 0 : 1);
        if (ok1) notifListLatency.add(res1.timings.duration);
        if (ok2) unreadCountLatency.add(res2.timings.duration);
        totalNotifOps.add(2);

    } else if (action < 0.95) {
        // 알림 삭제 (5%)
        const notifId = Math.floor(Math.random() * 10000000) + 1;
        const res = http.del(`${BASE_URL}/api/v1/notifications/${notifId}`, null, {
            headers: h, tags: { name: 'DELETE /notifications' },
        });
        const ok = res.status === 200 || res.status === 404;
        restSuccessRate.add(ok ? 1 : 0);
        restErrorRate.add(ok ? 0 : 1);
        totalNotifOps.add(1);

    } else {
        // 전체 읽음 (무거운 UPDATE)
        const res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: h, tags: { name: 'PUT /read-all' },
        });
        const ok = res.status === 200;
        restSuccessRate.add(ok ? 1 : 0);
        restErrorRate.add(ok ? 0 : 1);
        totalNotifOps.add(1);
    }

    sleep(0.1 + Math.random() * 0.3); // 100~400ms 간격
}

// ============================================
// 시나리오: SSE 대량 연결
// ============================================
export function sseFlood() {
    const user = vuUser();

    group('SSE Flood', () => {
        // SSE 연결 (3초 유지)
        const result = connectSSE(user, '3s');

        check(result, {
            'SSE flood: connected': (r) => r.connected,
            'SSE flood: got event': (r) => r.hasConnectedEvent,
        });

    });

    sleep(0.5 + Math.random() * 1); // 500~1500ms
}

// ============================================
// 시나리오: 혼합 고부하 (실전 피크)
// ============================================
export function mixedHighLoad() {
    const user = vuUser();
    const token = generateJWT(user);
    const h = headers(token);
    const scenario = Math.random();

    if (scenario < 0.20) {
        // 20%: SSE 연결 + 즉시 REST 호출 (앱 진입 시나리오)
        group('App Entry', () => {
            connectSSE(user, '2s');

            // SSE 연결 후 즉시 알림 데이터 로딩
            const batch = http.batch([
                ['GET', `${BASE_URL}/api/v1/notifications?size=20`, null, { headers: h, tags: { name: 'GET /notifications' } }],
                ['GET', `${BASE_URL}/api/v1/notifications/unread-count`, null, { headers: h, tags: { name: 'GET /unread-count' } }],
            ]);

            batch.forEach(res => {
                const ok = res.status === 200;
                restSuccessRate.add(ok ? 1 : 0);
                restErrorRate.add(ok ? 0 : 1);
            });

            if (batch[0].status === 200) notifListLatency.add(batch[0].timings.duration);
            if (batch[1].status === 200) unreadCountLatency.add(batch[1].timings.duration);
            totalNotifOps.add(2);
        });

    } else if (scenario < 0.50) {
        // 30%: 알림 목록 스크롤 (페이지네이션 연속)
        group('Scroll Notifications', () => {
            let cursor = null;
            for (let page = 0; page < 3; page++) {
                const url = cursor
                    ? `${BASE_URL}/api/v1/notifications?size=20&cursor=${cursor}`
                    : `${BASE_URL}/api/v1/notifications?size=20`;

                const res = http.get(url, {
                    headers: h, tags: { name: 'GET /notifications' },
                });

                const ok = res.status === 200;
                restSuccessRate.add(ok ? 1 : 0);
                restErrorRate.add(ok ? 0 : 1);
                if (ok) {
                    notifListLatency.add(res.timings.duration);
                    try {
                        const body = JSON.parse(res.body);
                        cursor = body.data && body.data.cursor;
                        if (!body.data || !body.data.hasMore) break;
                    } catch (e) { break; }
                }
                totalNotifOps.add(1);
            }
        });

    } else if (scenario < 0.70) {
        // 20%: 읽지 않은 개수 폴링 (앱 포그라운드)
        group('Unread Polling', () => {
            for (let i = 0; i < 5; i++) {
                const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
                    headers: h, tags: { name: 'GET /unread-count' },
                });
                const ok = res.status === 200;
                restSuccessRate.add(ok ? 1 : 0);
                restErrorRate.add(ok ? 0 : 1);
                if (ok) unreadCountLatency.add(res.timings.duration);
                totalNotifOps.add(1);
                sleep(0.2);
            }
        });

    } else if (scenario < 0.90) {
        // 20%: 알림 읽기 + 목록 갱신 (사용자 상호작용)
        group('Read & Refresh', () => {
            // 먼저 목록 조회
            const listRes = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
                headers: h, tags: { name: 'GET /notifications' },
            });
            const listOk = listRes.status === 200;
            restSuccessRate.add(listOk ? 1 : 0);
            restErrorRate.add(listOk ? 0 : 1);
            if (listOk) notifListLatency.add(listRes.timings.duration);
            totalNotifOps.add(1);

            // 알림 읽기 (3~5건)
            if (listOk) {
                try {
                    const body = JSON.parse(listRes.body);
                    const notifs = body.data && body.data.notifications || [];
                    const readCount = Math.min(notifs.length, 3 + Math.floor(Math.random() * 3));
                    for (let i = 0; i < readCount; i++) {
                        const nid = notifs[i].notificationId;
                        const readRes = http.put(`${BASE_URL}/api/v1/notifications/${nid}/read`, null, {
                            headers: h, tags: { name: 'PUT /read' },
                        });
                        if (readRes.status === 200) markReadLatency.add(readRes.timings.duration);
                        totalNotifOps.add(1);
                    }
                } catch (e) { /* parse error */ }
            }

            // 읽은 후 개수 갱신
            const countRes = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
                headers: h, tags: { name: 'GET /unread-count' },
            });
            const countOk = countRes.status === 200;
            restSuccessRate.add(countOk ? 1 : 0);
            restErrorRate.add(countOk ? 0 : 1);
            if (countOk) unreadCountLatency.add(countRes.timings.duration);
            totalNotifOps.add(1);
        });

    } else {
        // 10%: SSE 재연결 사이클
        group('SSE Reconnect', () => {
            connectSSE(user, '2s');
            sleep(0.3);
            connectSSE(user, '2s');
        });
    }

    sleep(0.1 + Math.random() * 0.2);
}

// ============================================
// 라이프사이클
// ============================================
export function setup() {
    console.log('=== High-Performance Notification Load Test v3 ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('Max VU: 500, Duration: ~17 min');
    console.log('DB: 10K users, 10M notifications');
    console.log('=================================================');

    // Health check
    const health = http.get(`${BASE_URL}/actuator/health`);
    check(health, { 'Setup: health OK': (r) => r.status === 200 });

    // Auth check
    const user = testUsers[0];
    const token = generateJWT(user);

    const unread = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: headers(token),
    });
    console.log(`Auth check: status=${unread.status}, body=${unread.body}`);
    check(unread, { 'Setup: auth works': (r) => r.status === 200 });

    const list = http.get(`${BASE_URL}/api/v1/notifications?size=5`, {
        headers: headers(token),
    });
    console.log(`List check: status=${list.status}, body_len=${list.body ? list.body.length : 0}`);

    // SSE check
    const sse = http.get(`${BASE_URL}/sse/subscribe`, {
        headers: sseHeaders(token),
        timeout: '2s',
        responseType: 'text',
    });
    console.log(`SSE check: status=${sse.status}, body_len=${sse.body ? sse.body.length : 0}`);
    if (sse.body) console.log(`SSE preview: ${sse.body.substring(0, 200)}`);
}

export function teardown() {
    console.log('=== Test Completed ===');
}
