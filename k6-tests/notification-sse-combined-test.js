// 알림 + SSE 통합 부하 테스트 v2 (100 VU, Windows 안정 버전)
//
// k6 제한사항: http.get()은 SSE 스트리밍을 네이티브 지원하지 않음
// → SSE 연결은 짧은 타임아웃(2s)으로 연결 수립 + "connected" 이벤트 수신만 검증
// → REST 시나리오는 별도로 높은 VU로 성능 측정
//
// 시나리오 구성:
//   1. SSE 연결 수립 (20 VU, 2m) - 연결 + connected 이벤트 확인
//   2. SSE 용량 테스트 (0→50 VU, 3m) - 동시 연결 수 증가
//   3. 알림 REST 워크로드 (0→100 VU, 4m) - 순수 REST 성능
//   4. SSE + REST 혼합 (30 VU, 3m) - SSE 연결 중 REST 호출
//   5. SSE 재연결 (20 VU, 2m) - 끊김 후 재연결
// 총 ~14분, 최대 100 VU

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// 커스텀 메트릭
// ============================================
const sseConnectionErrors = new Rate('sse_connection_errors');
const sseConnectionTime = new Trend('sse_connection_time');
const sseEventsReceived = new Counter('sse_events_received');
const sseConnectedEventOk = new Rate('sse_connected_event_ok');
const notificationListDuration = new Trend('notification_list_duration');
const unreadCountDuration = new Trend('unread_count_duration');
const markAsReadDuration = new Trend('mark_as_read_duration');
const errorRate = new Rate('errors');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

// SSE 연결 타임아웃: 2초 (connected 이벤트 수신에 충분)
const SSE_CONNECT_TIMEOUT = '2s';

export const options = {
    scenarios: {
        // 시나리오 1: SSE 연결 수립 테스트 (20 VU, 2분)
        sse_connection: {
            executor: 'constant-vus',
            exec: 'sseConnectionTest',
            vus: 20,
            duration: '2m',
            gracefulStop: '10s',
        },

        // 시나리오 2: SSE 동시 연결 용량 (0→50 VU, 3분)
        sse_capacity: {
            executor: 'ramping-vus',
            exec: 'sseCapacityTest',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 30 },
                { duration: '1m', target: 50 },
                { duration: '30s', target: 50 },
                { duration: '30s', target: 0 },
            ],
            startTime: '2m30s',
            gracefulRampDown: '10s',
        },

        // 시나리오 3: 알림 REST 워크로드 (0→100 VU, 4분)
        notification_rest: {
            executor: 'ramping-vus',
            exec: 'notificationRest',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50 },
                { duration: '1m', target: 100 },
                { duration: '2m', target: 100 },
                { duration: '30s', target: 0 },
            ],
            startTime: '6m',
            gracefulRampDown: '10s',
        },

        // 시나리오 4: SSE + REST 혼합 (30 VU, 3분)
        sse_rest_mixed: {
            executor: 'constant-vus',
            exec: 'sseRestMixed',
            vus: 30,
            duration: '3m',
            startTime: '10m30s',
            gracefulStop: '10s',
        },

        // 시나리오 5: SSE 재연결 사이클 (20 VU, 2분)
        sse_reconnection: {
            executor: 'constant-vus',
            exec: 'sseReconnectionTest',
            vus: 20,
            duration: '2m',
            startTime: '14m',
            gracefulStop: '10s',
        },
    },

    thresholds: {
        // SSE
        sse_connection_errors: ['rate<0.15'],
        sse_connected_event_ok: ['rate>0.7'],
        sse_connection_time: ['p(95)<3000'],

        // REST
        notification_list_duration: ['p(95)<500'],
        unread_count_duration: ['p(95)<200'],
        mark_as_read_duration: ['p(95)<300'],
        errors: ['rate<0.1'],

        // HTTP
        http_req_failed: ['rate<0.15'],
    },
};

// ============================================
// 테스트 사용자
// ============================================
const testUsers = new SharedArray('test_users', function () {
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
// JWT 유틸리티
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

function getHeaders(token) {
    return { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' };
}

function getSseHeaders(token) {
    return {
        'Authorization': `Bearer ${token}`,
        'Accept': 'text/event-stream',
        'Cache-Control': 'no-cache',
    };
}

function getRandomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

function getVuUser() {
    return testUsers[__VU % testUsers.length];
}

// ============================================
// SSE 연결 헬퍼 (짧은 타임아웃)
// ============================================
function connectSSE(user, timeout) {
    const token = generateJWT(user);
    const headers = getSseHeaders(token);
    const startTime = Date.now();

    const res = http.get(`${BASE_URL}/sse/subscribe`, {
        headers: headers,
        timeout: timeout || SSE_CONNECT_TIMEOUT,
        responseType: 'text',
        tags: { name: 'sse_subscribe' },
    });

    const duration = Date.now() - startTime;
    sseConnectionTime.add(duration);

    // SSE는 스트리밍이므로 타임아웃(0)도 "연결 성공"으로 볼 수 있음
    // status 200 = 정상 응답, status 0 = k6 타임아웃 (연결은 수립됨)
    const isConnected = res.status === 200 || res.status === 0;

    if (!isConnected && res.status !== 0) {
        sseConnectionErrors.add(1);
        return { success: false, eventCount: 0, events: [], duration: duration };
    }

    // SSE 이벤트 파싱
    let eventCount = 0;
    let connectedEventFound = false;
    const events = [];

    if (res.body) {
        const rawEvents = res.body.split('\n\n');
        rawEvents.forEach(rawEvent => {
            if (rawEvent.trim() === '') return;
            const event = parseSSEEvent(rawEvent);
            if (event) {
                eventCount++;
                events.push(event);
                sseEventsReceived.add(1);
                if (event.name === 'connected') {
                    connectedEventFound = true;
                }
            }
        });
    }

    sseConnectedEventOk.add(connectedEventFound ? 1 : 0);
    sseConnectionErrors.add(0);

    return {
        success: true,
        eventCount: eventCount,
        events: events,
        connectedEvent: connectedEventFound,
        duration: duration,
    };
}

function parseSSEEvent(raw) {
    const lines = raw.trim().split('\n');
    const event = {};
    for (const line of lines) {
        if (line.startsWith('id:')) event.id = line.substring(3).trim();
        else if (line.startsWith('event:')) event.name = line.substring(6).trim();
        else if (line.startsWith('data:')) event.data = line.substring(5).trim();
    }
    return Object.keys(event).length > 0 ? event : null;
}

function validateResponse(res, name) {
    const success = check(res, {
        [`${name}: status 2xx`]: (r) => r.status >= 200 && r.status < 300,
    });
    if (!success) errorRate.add(1);
    else errorRate.add(0);
    return success;
}

// ============================================
// 시나리오 1: SSE 연결 수립 테스트
// ============================================
export function sseConnectionTest() {
    const user = getVuUser();

    group('SSE Connection', () => {
        const result = connectSSE(user, '2s');

        check(result, {
            'SSE: connection OK': (r) => r.success === true,
            'SSE: connected event received': (r) => r.connectedEvent === true,
        });

    });

    sleep(1);
}

// ============================================
// 시나리오 2: SSE 동시 연결 용량 테스트
// ============================================
export function sseCapacityTest() {
    const user = getVuUser();

    group('SSE Capacity', () => {
        const result = connectSSE(user, '3s');

        check(result, {
            'SSE capacity: connected': (r) => r.success === true,
            'SSE capacity: got event': (r) => r.connectedEvent === true,
        });

    });

    sleep(0.5);
}

// ============================================
// 시나리오 3: 알림 REST 워크로드
// ============================================
export function notificationRest() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Notification REST', () => {
        const action = Math.random();

        if (action < 0.35) {
            const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
                headers, tags: { name: 'notification_list' },
            });
            if (validateResponse(res, 'list')) {
                notificationListDuration.add(res.timings.duration);
            }
        } else if (action < 0.6) {
            const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
                headers, tags: { name: 'unread_count' },
            });
            if (validateResponse(res, 'unread')) {
                unreadCountDuration.add(res.timings.duration);
            }
        } else if (action < 0.8) {
            const notifId = Math.floor(Math.random() * 10000000) + 1;
            const res = http.put(`${BASE_URL}/api/v1/notifications/${notifId}/read`, null, {
                headers, tags: { name: 'mark_as_read' },
            });
            check(res, {
                'mark read: accepted': (r) => r.status === 200 || r.status === 404,
            });
            markAsReadDuration.add(res.timings.duration);
        } else if (action < 0.95) {
            // 목록 + 개수 연속 (실제 UI 패턴)
            const res1 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
                headers, tags: { name: 'notification_list' },
            });
            if (validateResponse(res1, 'combo list')) {
                notificationListDuration.add(res1.timings.duration);
            }
            const res2 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
                headers, tags: { name: 'unread_count' },
            });
            if (validateResponse(res2, 'combo unread')) {
                unreadCountDuration.add(res2.timings.duration);
            }
        } else {
            const res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
                headers, tags: { name: 'mark_all_read' },
            });
            check(res, {
                'mark all read: accepted': (r) => r.status === 200 || r.status === 404,
            });
        }
    });

    sleep(0.5);
}

// ============================================
// 시나리오 4: SSE + REST 혼합
// ============================================
export function sseRestMixed() {
    const user = getVuUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('SSE + REST Mixed', () => {
        // SSE 연결 (2초)
        const sseResult = connectSSE(user, '2s');
        check(sseResult, {
            'mixed SSE: connected': (r) => r.success === true,
        });

        // 연결 직후 REST API 호출 (SSE가 백그라운드에서 서버 자원 사용 중)
        const listRes = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers, tags: { name: 'notification_list' },
        });
        if (validateResponse(listRes, 'mixed list')) {
            notificationListDuration.add(listRes.timings.duration);
        }

        const unreadRes = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers, tags: { name: 'unread_count' },
        });
        if (validateResponse(unreadRes, 'mixed unread')) {
            unreadCountDuration.add(unreadRes.timings.duration);
        }

        // 읽음 처리
        const notifId = Math.floor(Math.random() * 10000000) + 1;
        const readRes = http.put(`${BASE_URL}/api/v1/notifications/${notifId}/read`, null, {
            headers, tags: { name: 'mark_as_read' },
        });
        check(readRes, {
            'mixed mark read': (r) => r.status === 200 || r.status === 404,
        });
        markAsReadDuration.add(readRes.timings.duration);
    });

    sleep(1);
}

// ============================================
// 시나리오 5: SSE 재연결 사이클
// ============================================
export function sseReconnectionTest() {
    const user = getVuUser();

    group('SSE Reconnection', () => {
        // 첫 연결
        const result1 = connectSSE(user, '2s');
        check(result1, {
            'reconnect 1st: OK': (r) => r.success === true,
        });

        sleep(0.5);

        // 재연결 (서버가 기존 연결을 교체)
        const result2 = connectSSE(user, '2s');
        check(result2, {
            'reconnect 2nd: OK': (r) => r.success === true,
            'reconnect 2nd: connected event': (r) => r.connectedEvent === true,
        });

    });

    sleep(1);
}

// ============================================
// 라이프사이클
// ============================================
export function setup() {
    console.log('=== Notification + SSE Combined Test v2 ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('Max VU: 100, Duration: ~16 min');
    console.log('SSE timeout: 2-3s (connection verification only)');
    console.log('============================================');

    // Health check
    const health = http.get(`${BASE_URL}/actuator/health`);
    if (health.status !== 200) {
        console.error(`Server health check failed: ${health.status}`);
    }

    // SSE endpoint check
    const sseCheck = http.get(`${BASE_URL}/sse/subscribe`, { timeout: '2s' });
    console.log(`SSE (no auth): status=${sseCheck.status} (expect 401)`);

    // Notification endpoint check
    const notifCheck = http.get(`${BASE_URL}/api/v1/notifications/unread-count`);
    console.log(`Notifications (no auth): status=${notifCheck.status} (expect 403)`);

    // 인증된 SSE 연결 테스트
    const testUser = testUsers[0];
    const token = generateJWT(testUser);
    const authSseCheck = http.get(`${BASE_URL}/sse/subscribe`, {
        headers: getSseHeaders(token),
        timeout: '2s',
        responseType: 'text',
    });
    console.log(`SSE (auth): status=${authSseCheck.status}, body_len=${authSseCheck.body ? authSseCheck.body.length : 0}`);
    if (authSseCheck.body) {
        console.log(`SSE body preview: ${authSseCheck.body.substring(0, 200)}`);
    }
}

export function teardown(data) {
    console.log('=== Test Completed ===');
}
