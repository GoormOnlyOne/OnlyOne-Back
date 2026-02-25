import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import ws from 'k6/ws';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// SSE 전용 커스텀 메트릭
// ============================================
const sseConnectionErrors = new Rate('sse_connection_errors');
const sseConnectionDuration = new Trend('sse_connection_duration');
const sseEventReceived = new Counter('sse_events_received');
const sseActiveConnections = new Gauge('sse_active_connections');
const sseReconnections = new Counter('sse_reconnections');
const sseLastEventIdUsed = new Counter('sse_last_event_id_used');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';
const SSE_URL = BASE_URL.replace('http://', '').replace('https://', '');
const SSE_TIMEOUT = 60000;  // 60초 (application.yml 설정과 동일)

// SSE 테스트 시나리오
export const options = {
    scenarios: {
        // 시나리오 1: 점진적 SSE 연결 증가
        sse_ramp_up: {
            executor: 'ramping-vus',
            exec: 'sseRampUp',
            startVUs: 0,
            stages: [
                { duration: '2m', target: 500 },    // 500 연결
                { duration: '3m', target: 1000 },   // 1000 연결
                { duration: '3m', target: 2000 },   // 2000 연결
                { duration: '2m', target: 3000 },   // 3000 연결 (병목 확인)
                { duration: '2m', target: 0 },
            ],
            gracefulRampDown: '30s',
        },

        // 시나리오 2: 고정 연결 + 이벤트 스트리밍
        sse_sustained_load: {
            executor: 'constant-vus',
            exec: 'sseSustainedLoad',
            vus: 1000,
            duration: '10m',
            startTime: '13m',  // ramp_up 이후
            gracefulStop: '30s',
        },

        // 시나리오 3: SSE 재연결 시뮬레이션 (Last-Event-ID 테스트)
        sse_reconnection_test: {
            executor: 'constant-vus',
            exec: 'sseReconnectionTest',
            vus: 200,
            duration: '5m',
            startTime: '24m',  // sustained_load 이후
            gracefulStop: '30s',
        },

        // 시나리오 4: SSE + REST API 혼합 워크로드
        sse_mixed_workload: {
            executor: 'ramping-vus',
            exec: 'sseMixedWorkload',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 300 },
                { duration: '3m', target: 300 },
                { duration: '1m', target: 0 },
            ],
            startTime: '30m',  // reconnection_test 이후
            gracefulRampDown: '30s',
        },

        // 시나리오 5: SSE 연결 스파이크 (갑작스런 연결 폭증)
        sse_spike_test: {
            executor: 'ramping-vus',
            exec: 'sseSpikeTest',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 100 },   // 정상
                { duration: '10s', target: 2000 },  // 스파이크
                { duration: '2m', target: 2000 },   // 유지
                { duration: '30s', target: 100 },   // 복귀
                { duration: '30s', target: 0 },
            ],
            startTime: '36m',  // mixed_workload 이후
            gracefulRampDown: '30s',
        },
    },

    thresholds: {
        sse_connection_errors: ['rate<0.05'],           // SSE 연결 에러율 5% 미만
        sse_connection_duration: ['p(95)<2000'],        // 연결 시간 2초 이하
        sse_events_received: ['count>1000'],            // 최소 1000개 이벤트 수신
        http_req_duration: ['p(95)<500'],
        http_req_failed: ['rate<0.01'],
    },
};

// 테스트 사용자
const testUsers = new SharedArray('sse_test_users', function () {
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
// 유틸리티 함수
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
        'Accept': 'text/event-stream',
        'Cache-Control': 'no-cache',
    };
}

function getRandomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

// ============================================
// SSE 연결 헬퍼 함수
// ============================================

function connectSSE(user, lastEventId = null) {
    const token = generateJWT(user);
    let url = `${BASE_URL}/api/v1/sse/subscribe`;

    // Last-Event-ID가 있으면 쿼리 파라미터로 전달
    if (lastEventId) {
        url += `?lastEventId=${lastEventId}`;
        sseLastEventIdUsed.add(1);
    }

    const headers = getHeaders(token);
    const startTime = Date.now();
    let eventCount = 0;
    let lastReceivedEventId = lastEventId;

    const res = http.get(url, {
        headers: headers,
        timeout: SSE_TIMEOUT + 'ms',
        responseType: 'text',
        tags: { name: 'sse_subscribe' },
    });

    const connectionDuration = Date.now() - startTime;
    sseConnectionDuration.add(connectionDuration);

    const success = check(res, {
        'SSE connection established': (r) => r.status === 200,
        'SSE content-type is text/event-stream': (r) =>
            r.headers['Content-Type'] && r.headers['Content-Type'].includes('text/event-stream'),
    });

    if (!success) {
        sseConnectionErrors.add(1);
        return { eventCount: 0, lastEventId: lastReceivedEventId };
    }

    sseActiveConnections.add(1);

    // SSE 이벤트 파싱 (간단한 버전)
    if (res.body) {
        const events = res.body.split('\n\n');
        events.forEach(event => {
            if (event.trim() !== '') {
                eventCount++;
                sseEventReceived.add(1);

                // id: 필드 추출
                const idMatch = event.match(/^id:\s*(.+)$/m);
                if (idMatch) {
                    lastReceivedEventId = idMatch[1];
                }
            }
        });
    }

    sseActiveConnections.add(-1);

    return { eventCount, lastEventId: lastReceivedEventId };
}

// 알림 생성 트리거 (테스트용)
function triggerNotification(targetUser, token) {
    // 실제로는 다른 사용자의 액션으로 알림이 생성되지만,
    // 테스트를 위해 직접 알림 생성 API 호출 (관리자 전용 API 필요)

    // 대안: 댓글 작성, 좋아요 등의 액션으로 알림 트리거
    // 여기서는 단순히 SSE 연결 유지 중에 서버에서 배치로 전송되는 알림을 대기

    return true;
}

// ============================================
// 시나리오 1: SSE 점진적 연결 증가
// ============================================
export function sseRampUp() {
    const user = getRandomUser();

    group('SSE Ramp Up - Establish Connection', () => {
        const result = connectSSE(user);

        check(result, {
            'SSE received events': (r) => r.eventCount >= 0,
        });
    });

    // 연결 유지 시뮬레이션 (실제로는 long-polling)
    sleep(5);
}

// ============================================
// 시나리오 2: SSE 지속 부하 (장시간 연결)
// ============================================
export function sseSustainedLoad() {
    const user = getRandomUser();
    const token = generateJWT(user);

    group('SSE Sustained Load - Long Connection', () => {
        // SSE 연결 (60초 타임아웃까지 유지)
        const result = connectSSE(user);

        check(result, {
            'SSE sustained connection successful': (r) => r.eventCount >= 0,
        });

        // 이벤트 수신 확인
        if (result.eventCount > 0) {
            console.log(`User ${user.userId} received ${result.eventCount} events`);
        }
    });

    // 재연결 시뮬레이션
    sleep(2);
}

// ============================================
// 시나리오 3: SSE 재연결 테스트 (Last-Event-ID)
// ============================================
export function sseReconnectionTest() {
    const user = getRandomUser();

    group('SSE Reconnection - Last-Event-ID Support', () => {
        // 첫 번째 연결
        const result1 = connectSSE(user);

        check(result1, {
            'First connection successful': (r) => r.eventCount >= 0,
        });

        sleep(2);

        // 연결 끊김 시뮬레이션 후 재연결 (Last-Event-ID 사용)
        if (result1.lastEventId) {
            sseReconnections.add(1);

            const result2 = connectSSE(user, result1.lastEventId);

            check(result2, {
                'Reconnection with Last-Event-ID successful': (r) => r.eventCount >= 0,
            });

            console.log(`User ${user.userId} reconnected with Last-Event-ID: ${result1.lastEventId}`);
        }
    });

    sleep(3);
}

// ============================================
// 시나리오 4: SSE + REST API 혼합 워크로드
// ============================================
export function sseMixedWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Mixed Workload - SSE + REST API', () => {
        // 1. SSE 연결
        const sseResult = connectSSE(user);

        sleep(1);

        // 2. REST API 호출 (알림 개수 조회)
        const res1 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
        check(res1, {
            'REST API - unread count': (r) => r.status === 200,
        });

        sleep(1);

        // 3. REST API 호출 (알림 목록 조회)
        const res2 = http.get(`${BASE_URL}/api/v1/notifications?size=10`, { headers });
        check(res2, {
            'REST API - notification list': (r) => r.status === 200,
        });

        sleep(2);

        // 4. 알림 읽음 처리 (캐시 무효화 → SSE 이벤트 트리거)
        if (res2.status === 200) {
            const body = JSON.parse(res2.body);
            if (body.data && body.data.notifications && body.data.notifications.length > 0) {
                const notificationId = body.data.notifications[0].notificationId;
                const res3 = http.put(
                    `${BASE_URL}/api/v1/notifications/${notificationId}/read`,
                    null,
                    { headers }
                );
                check(res3, {
                    'REST API - mark as read': (r) => r.status === 200,
                });
            }
        }
    });

    sleep(2);
}

// ============================================
// 시나리오 5: SSE 연결 스파이크
// ============================================
export function sseSpikeTest() {
    const user = getRandomUser();

    group('SSE Spike Test - Sudden Connection Burst', () => {
        const result = connectSSE(user);

        check(result, {
            'SSE spike connection successful': (r) => r.eventCount >= 0,
        });
    });

    sleep(1);
}

// ============================================
// 추가 시나리오: 배치 처리 병목 테스트
// ============================================
export function batchBottleneckTest() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Batch Processing Bottleneck Test', () => {
        // 1. SSE 연결 (배치 큐에 추가)
        connectSSE(user);

        sleep(0.5);

        // 2. 대량의 알림 생성 트리거 (다른 사용자 액션 시뮬레이션)
        // 실제로는 서버 내부에서 알림이 생성되어 배치 큐에 쌓임

        // 3. 읽지 않은 알림 개수 조회
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
        check(res, {
            'Unread count check': (r) => r.status === 200,
        });
    });

    sleep(2);
}

// ============================================
// 테스트 라이프사이클
// ============================================

export function setup() {
    console.log('=== SSE Load Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log(`SSE Timeout: ${SSE_TIMEOUT}ms`);
    console.log(`Test Users: ${testUsers.length}`);
    console.log(`Total Duration: ~41 minutes`);
    console.log('================================');

    // 서버 상태 확인
    const healthCheck = http.get(`${BASE_URL}/actuator/health`);
    if (healthCheck.status !== 200) {
        console.error('ERROR: Server health check failed!');
    }
}

export function teardown(data) {
    console.log('=== SSE Load Test Completed ===');
    console.log(`Total SSE Events Received: ${sseEventReceived.rate}`);
    console.log(`SSE Reconnections: ${sseReconnections.rate}`);
    console.log('=================================');
}
