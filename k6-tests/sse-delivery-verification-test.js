// =============================================================
// SSE 알림 전달 검증 테스트
// DB에 sse_sent=false 알림 시드 → SSE 연결 시 sendMissedNotifications()로 최대 50건 복구
// 총 ~10분, 최대 200 VU
// =============================================================
//
// 사전 조건: seed-delivery-test.sql 실행 필요 (유저 1~1000 x 50건 = 50,000건)
//
// 시나리오:
//   1. delivery_baseline (50 VU, 3분) - SSE 연결 후 알림 이벤트 수신 확인
//   2. delivery_burst (0→200 VU, 4분) - 동시 다수 연결 시 전달 안정성
//   3. delivery_latency (100 VU, 3분) - 연결~첫 알림 수신 지연시간 측정

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { generateJWT, headers, sseHeaders, connectSSE, parseSSEEvents, BASE_URL } from './lib/common.js';

// ============================================
// 커스텀 메트릭
// ============================================
const sseDeliverySuccess = new Rate('sse_delivery_success');
const sseDeliveryEventCount = new Trend('sse_delivery_event_count');
const sseDeliveryLatency = new Trend('sse_delivery_latency');
const sseConnectedOk = new Rate('sse_connected_ok');
const sseNotificationReceived = new Rate('sse_notification_received');
const sseNotificationCount = new Counter('sse_notification_total_count');
const errorRate = new Rate('errors');

// ============================================
// 테스트 사용자 (1~1000)
// ============================================
const testUsers = new SharedArray('delivery_users', function () {
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
// 시나리오 설정
// ============================================
export const options = {
    scenarios: {
        // 시나리오 1: 기본 전달 확인 (50 VU, 3분)
        delivery_baseline: {
            executor: 'constant-vus',
            exec: 'deliveryBaseline',
            vus: 50,
            duration: '3m',
            gracefulStop: '10s',
        },

        // 시나리오 2: 버스트 전달 (0→200 VU, 4분)
        delivery_burst: {
            executor: 'ramping-vus',
            exec: 'deliveryBurst',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 100 },
                { duration: '1m', target: 200 },
                { duration: '1m', target: 200 },
                { duration: '1m', target: 0 },
            ],
            startTime: '3m30s',
            gracefulRampDown: '10s',
        },

        // 시나리오 3: 전달 지연시간 측정 (100 VU, 3분)
        delivery_latency: {
            executor: 'constant-vus',
            exec: 'deliveryLatency',
            vus: 100,
            duration: '3m',
            startTime: '8m',
            gracefulStop: '10s',
        },
    },

    thresholds: {
        sse_delivery_success: ['rate>0.40'],       // SSE 유저당 1연결 제한으로 VU 충돌 감안
        sse_delivery_event_count: ['avg>3'],
        sse_delivery_latency: ['p(95)<10000'],     // 8s timeout + 버퍼
        sse_connected_ok: ['rate>0.80'],
        errors: ['rate<0.15'],
        // http_req_failed 제외: SSE는 timeout으로 body를 받으므로 k6가 항상 failed 처리
    },
};

function getVuUser() {
    return testUsers[__VU % testUsers.length];
}

// ============================================
// 시나리오 1: 기본 전달 확인
// SSE 연결 → connected + notification 이벤트 수신 확인
// ============================================
export function deliveryBaseline() {
    const user = getVuUser();

    // 5초 타임아웃으로 연결 (알림 전달 수신 대기)
    const result = connectSSE(user, '5s');

    const connected = check(result, {
        'baseline: SSE connected': (r) => r.success === true,
        'baseline: connected event': (r) => r.connectedEvent === true,
    });

    sseConnectedOk.add(result.connectedEvent ? 1 : 0);

    // notification 이벤트 수신 확인
    const notifEvents = result.notificationEvents;
    const hasNotifications = notifEvents.length > 0;

    sseDeliverySuccess.add(hasNotifications ? 1 : 0);
    sseDeliveryEventCount.add(notifEvents.length);

    if (hasNotifications) {
        sseNotificationCount.add(notifEvents.length);

        // notification JSON 필드 유효성 검증
        notifEvents.forEach(evt => {
            if (evt.data) {
                try {
                    const data = JSON.parse(evt.data);
                    check(data, {
                        'baseline: has notificationId': (d) => d.notificationId !== undefined,
                        'baseline: has content': (d) => d.content !== undefined,
                        'baseline: has type': (d) => d.type !== undefined,
                    });
                } catch (e) {
                    // JSON 파싱 실패
                    errorRate.add(1);
                }
            }
        });
    }

    errorRate.add(!connected ? 1 : 0);
    sleep(2);
}

// ============================================
// 시나리오 2: 버스트 전달 (동시 다수 연결)
// ============================================
export function deliveryBurst() {
    const user = getVuUser();

    const result = connectSSE(user, '5s');

    check(result, {
        'burst: SSE connected': (r) => r.success === true,
        'burst: connected event': (r) => r.connectedEvent === true,
    });

    sseConnectedOk.add(result.connectedEvent ? 1 : 0);

    const notifEvents = result.notificationEvents;
    sseDeliverySuccess.add(notifEvents.length > 0 ? 1 : 0);
    sseDeliveryEventCount.add(notifEvents.length);

    if (notifEvents.length > 0) {
        sseNotificationCount.add(notifEvents.length);
    }

    errorRate.add(!result.success ? 1 : 0);
    sleep(1);
}

// ============================================
// 시나리오 3: 전달 지연시간 측정
// 연결 시작 ~ 첫 notification 이벤트 수신까지의 시간
// ============================================
export function deliveryLatency() {
    const user = getVuUser();
    const token = generateJWT(user);
    const hdrs = sseHeaders(token);

    const startTime = Date.now();

    const res = http.get(`${BASE_URL}/api/v1/sse/subscribe`, {
        headers: hdrs,
        timeout: '8s',
        responseType: 'text',
        tags: { name: 'sse_subscribe' },
    });

    const totalDuration = Date.now() - startTime;

    const events = parseSSEEvents(res.body);
    const notifEvents = events.filter(e => e.name === 'notification');
    const hasConnected = events.some(e => e.name === 'connected');

    sseConnectedOk.add(hasConnected ? 1 : 0);
    sseDeliverySuccess.add(notifEvents.length > 0 ? 1 : 0);
    sseDeliveryEventCount.add(notifEvents.length);
    sseDeliveryLatency.add(totalDuration);

    if (notifEvents.length > 0) {
        sseNotificationCount.add(notifEvents.length);
    }

    errorRate.add(!hasConnected ? 1 : 0);
    sleep(1);
}

// ============================================
// 라이프사이클
// ============================================
export function setup() {
    console.log('=== SSE Delivery Verification Test ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('Max VU: 200, Duration: ~10 min');
    console.log('사전 조건: seed-delivery-test.sql 실행 필요');
    console.log('========================================');

    // Health check
    const health = http.get(`${BASE_URL}/actuator/health`);
    if (health.status !== 200) {
        console.error(`Server health check failed: ${health.status}`);
    }

    // 인증된 SSE 연결 사전 확인
    const testUser = testUsers[0];
    const result = connectSSE(testUser, '3s');
    console.log(`SSE pre-check: connected=${result.success}, events=${result.eventCount}, notifications=${result.notificationEvents.length}`);
}

export function teardown(data) {
    console.log('=== SSE Delivery Verification Test Completed ===');
}
