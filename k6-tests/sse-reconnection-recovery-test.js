// =============================================================
// SSE 재연결 복구 검증 테스트
// 1차 연결(짧은 timeout) → 종료 → 2차 연결(긴 timeout) → 미전송 알림 복구 확인
// 총 ~13분, 최대 200 VU
// =============================================================
//
// 사전 조건: seed-delivery-test.sql 실행 필요
//
// 시나리오:
//   1. single_reconnect (30 VU, 3분) - 1회 disconnect-reconnect 사이클
//   2. rapid_reconnect (50 VU, 3분) - 3회 빠른 reconnect 사이클
//   3. delayed_reconnect (20 VU, 4분) - 10초 대기 후 재연결
//   4. mass_reconnect (0→200 VU, 3분) - 대규모 동시 재연결 폭풍

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { generateJWT, sseHeaders, connectSSE, parseSSEEvents, BASE_URL } from './lib/common.js';

// ============================================
// 커스텀 메트릭
// ============================================
const recoverySuccessRate = new Rate('recovery_success_rate');
const recoveryNotificationCount = new Trend('recovery_notification_count');
const reconnectLatency = new Trend('reconnect_latency');
const sseConnectedOk = new Rate('sse_connected_ok');
const reconnectCycleCount = new Counter('reconnect_cycle_count');
const errorRate = new Rate('errors');

// ============================================
// 테스트 사용자
// ============================================
const testUsers = new SharedArray('reconnect_users', function () {
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
        // 시나리오 1: 1회 disconnect-reconnect
        single_reconnect: {
            executor: 'constant-vus',
            exec: 'singleReconnect',
            vus: 30,
            duration: '3m',
            gracefulStop: '10s',
        },

        // 시나리오 2: 3회 빠른 reconnect
        rapid_reconnect: {
            executor: 'constant-vus',
            exec: 'rapidReconnect',
            vus: 50,
            duration: '3m',
            startTime: '3m30s',
            gracefulStop: '10s',
        },

        // 시나리오 3: 10초 대기 후 재연결
        delayed_reconnect: {
            executor: 'constant-vus',
            exec: 'delayedReconnect',
            vus: 20,
            duration: '4m',
            startTime: '7m',
            gracefulStop: '10s',
        },

        // 시나리오 4: 대규모 동시 재연결 폭풍
        mass_reconnect: {
            executor: 'ramping-vus',
            exec: 'massReconnect',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 100 },
                { duration: '30s', target: 200 },
                { duration: '1m', target: 200 },
                { duration: '1m', target: 0 },
            ],
            startTime: '11m30s',
            gracefulRampDown: '10s',
        },
    },

    thresholds: {
        recovery_success_rate: ['rate>0.70'],
        recovery_notification_count: ['avg>1'],
        reconnect_latency: ['p(95)<6000'],         // 5s timeout + 버퍼
        sse_connected_ok: ['rate>0.75'],
        errors: ['rate<0.20'],
        // http_req_failed 제외: SSE timeout은 정상 동작
    },
};

function getVuUser() {
    return testUsers[__VU % testUsers.length];
}

// ============================================
// 시나리오 1: 1회 disconnect-reconnect
// ============================================
export function singleReconnect() {
    const user = getVuUser();

    // 1차 연결 (짧은 timeout - 연결 수립만)
    const result1 = connectSSE(user, '2s');
    check(result1, {
        'single: 1st connection OK': (r) => r.success === true,
    });
    reconnectCycleCount.add(1);

    sleep(1);

    // 2차 연결 (긴 timeout - 복구 알림 수신 대기)
    const startTime = Date.now();
    const result2 = connectSSE(user, '5s');
    const latency = Date.now() - startTime;

    const connected = check(result2, {
        'single: 2nd connection OK': (r) => r.success === true,
        'single: 2nd connected event': (r) => r.connectedEvent === true,
    });

    sseConnectedOk.add(result2.connectedEvent ? 1 : 0);
    reconnectLatency.add(latency);

    const notifCount = result2.notificationEvents.length;
    recoveryNotificationCount.add(notifCount);
    recoverySuccessRate.add(notifCount > 0 ? 1 : 0);

    errorRate.add(!connected ? 1 : 0);
    sleep(2);
}

// ============================================
// 시나리오 2: 3회 빠른 reconnect
// ============================================
export function rapidReconnect() {
    const user = getVuUser();
    let lastNotifCount = 0;

    for (let i = 0; i < 3; i++) {
        // 짧은 연결
        const shortResult = connectSSE(user, '1s');
        reconnectCycleCount.add(1);
        sleep(0.5);

        // 재연결 (복구 확인)
        const startTime = Date.now();
        const result = connectSSE(user, '4s');
        const latency = Date.now() - startTime;

        check(result, {
            [`rapid: cycle ${i + 1} connected`]: (r) => r.success === true,
        });

        sseConnectedOk.add(result.connectedEvent ? 1 : 0);
        reconnectLatency.add(latency);

        lastNotifCount = result.notificationEvents.length;
        sleep(0.5);
    }

    recoveryNotificationCount.add(lastNotifCount);
    recoverySuccessRate.add(lastNotifCount > 0 ? 1 : 0);
    sleep(1);
}

// ============================================
// 시나리오 3: 10초 대기 후 재연결 (네트워크 장애 시뮬레이션)
// ============================================
export function delayedReconnect() {
    const user = getVuUser();

    // 1차 연결
    const result1 = connectSSE(user, '2s');
    check(result1, {
        'delayed: 1st connection OK': (r) => r.success === true,
    });
    reconnectCycleCount.add(1);

    // 10초 대기 (네트워크 장애 시뮬레이션)
    sleep(10);

    // 재연결
    const startTime = Date.now();
    const result2 = connectSSE(user, '5s');
    const latency = Date.now() - startTime;

    const connected = check(result2, {
        'delayed: reconnection OK': (r) => r.success === true,
        'delayed: connected event': (r) => r.connectedEvent === true,
    });

    sseConnectedOk.add(result2.connectedEvent ? 1 : 0);
    reconnectLatency.add(latency);

    const notifCount = result2.notificationEvents.length;
    recoveryNotificationCount.add(notifCount);
    recoverySuccessRate.add(notifCount > 0 ? 1 : 0);

    errorRate.add(!connected ? 1 : 0);
    sleep(2);
}

// ============================================
// 시나리오 4: 대규모 동시 재연결 폭풍
// ============================================
export function massReconnect() {
    const user = getVuUser();

    // 빠른 1차 연결
    connectSSE(user, '1s');
    reconnectCycleCount.add(1);

    sleep(0.3);

    // 즉시 재연결 (대규모 동시)
    const startTime = Date.now();
    const result = connectSSE(user, '5s');
    const latency = Date.now() - startTime;

    check(result, {
        'mass: reconnection OK': (r) => r.success === true,
        'mass: connected event': (r) => r.connectedEvent === true,
    });

    sseConnectedOk.add(result.connectedEvent ? 1 : 0);
    reconnectLatency.add(latency);

    const notifCount = result.notificationEvents.length;
    recoveryNotificationCount.add(notifCount);
    recoverySuccessRate.add(notifCount > 0 ? 1 : 0);

    errorRate.add(!result.success ? 1 : 0);
    sleep(1);
}

// ============================================
// 라이프사이클
// ============================================
export function setup() {
    console.log('=== SSE Reconnection Recovery Test ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('Max VU: 200, Duration: ~13 min');
    console.log('사전 조건: seed-delivery-test.sql 실행 필요');
    console.log('=======================================');

    const health = http.get(`${BASE_URL}/actuator/health`);
    if (health.status !== 200) {
        console.error(`Server health check failed: ${health.status}`);
    }
}

export function teardown(data) {
    console.log('=== SSE Reconnection Recovery Test Completed ===');
}
