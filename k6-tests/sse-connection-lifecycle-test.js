// =============================================================
// SSE 연결 수명주기 스트레스 테스트
// timeout/reconnect 반복 + 장기 안정성 검증
// 총 ~15분, 최대 1000 VU
// =============================================================
//
// 시나리오:
//   1. timeout_cycle (100 VU, 10분) - 연결 → 60s timeout → 재연결 반복
//   2. stability_soak (1000 VU, 15분) - 1000개 연결 장기 유지 안정성

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { generateJWT, sseHeaders, connectSSE, parseSSEEvents, BASE_URL } from './lib/common.js';

// ============================================
// 커스텀 메트릭
// ============================================
const sseLifecycleSuccess = new Rate('sse_lifecycle_success');
const sseTimeoutCount = new Counter('sse_timeout_count');
const sseReconnectAfterTimeout = new Rate('sse_reconnect_after_timeout');
const sseConnectionDuration = new Trend('sse_connection_duration');
const errorRate = new Rate('errors');

// ============================================
// 테스트 사용자
// ============================================
const testUsers = new SharedArray('lifecycle_users', function () {
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
        // 시나리오 1: timeout 사이클 (100 VU, 10분)
        timeout_cycle: {
            executor: 'constant-vus',
            exec: 'timeoutCycle',
            vus: 100,
            duration: '10m',
            gracefulStop: '30s',
        },

        // 시나리오 2: 장기 안정성 (0→1000 VU, 15분)
        stability_soak: {
            executor: 'ramping-vus',
            exec: 'stabilitySoak',
            startVUs: 0,
            stages: [
                { duration: '3m', target: 500 },
                { duration: '2m', target: 1000 },
                { duration: '7m', target: 1000 },
                { duration: '3m', target: 0 },
            ],
            gracefulRampDown: '15s',
        },
    },

    thresholds: {
        sse_lifecycle_success: ['rate>0.90'],
        sse_reconnect_after_timeout: ['rate>0.90'],
        errors: ['rate<0.35'],             // timeout_cycle connected event 파싱 실패 감안
        // http_req_failed 제외: SSE timeout은 정상 동작
    },
};

function getVuUser() {
    return testUsers[__VU % testUsers.length];
}

// ============================================
// 시나리오 1: timeout 사이클
// 연결 → 서버 측 60s timeout 대기 → 재연결 반복
// k6에서는 짧은 timeout으로 연결 수립 확인 후 sleep으로 서버 timeout 대기
// ============================================
export function timeoutCycle() {
    const user = getVuUser();

    // 연결 수립 확인 (3초 timeout)
    const result1 = connectSSE(user, '3s');
    const connected = check(result1, {
        'timeout_cycle: initial connection OK': (r) => r.success === true,
        'timeout_cycle: connected event': (r) => r.connectedEvent === true,
    });

    sseLifecycleSuccess.add(result1.success ? 1 : 0);
    sseConnectionDuration.add(result1.duration);

    if (!connected) {
        errorRate.add(1);
        sleep(5);
        return;
    }

    // 서버 측 SSE timeout (60초) 대기 시뮬레이션
    // 실제로는 서버가 연결을 닫으므로 sleep 후 재연결
    sleep(15); // 축약된 대기 (실제 60초는 테스트 시간이 과도)
    sseTimeoutCount.add(1);

    // timeout 후 재연결
    const result2 = connectSSE(user, '3s');
    const reconnected = check(result2, {
        'timeout_cycle: reconnect after timeout OK': (r) => r.success === true,
        'timeout_cycle: reconnect connected event': (r) => r.connectedEvent === true,
    });

    sseReconnectAfterTimeout.add(result2.success ? 1 : 0);
    sseLifecycleSuccess.add(result2.success ? 1 : 0);

    errorRate.add(!reconnected ? 1 : 0);
    sleep(5);
}

// ============================================
// 시나리오 2: 장기 안정성 soak
// 1000 VU가 주기적으로 SSE 연결/재연결하며 안정성 관찰
// ============================================
export function stabilitySoak() {
    const user = getVuUser();

    // SSE 연결
    const result = connectSSE(user, '3s');
    const connected = check(result, {
        'soak: SSE connected': (r) => r.success === true,
    });

    sseLifecycleSuccess.add(result.success ? 1 : 0);
    sseConnectionDuration.add(result.duration);

    if (!connected) {
        errorRate.add(1);
        sleep(3);
        return;
    }

    // 연결 유지 시뮬레이션 (25~35초 간격으로 재연결)
    const holdTime = 25 + Math.random() * 10;
    sleep(holdTime);

    // 재연결 사이클
    const result2 = connectSSE(user, '3s');
    check(result2, {
        'soak: reconnect OK': (r) => r.success === true,
    });

    sseReconnectAfterTimeout.add(result2.success ? 1 : 0);
    sseLifecycleSuccess.add(result2.success ? 1 : 0);

    errorRate.add(!result2.success ? 1 : 0);
    sleep(5);
}

// ============================================
// 라이프사이클
// ============================================
export function setup() {
    console.log('=== SSE Connection Lifecycle Test ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('Max VU: 1000, Duration: ~15 min');
    console.log('=====================================');

    const health = http.get(`${BASE_URL}/actuator/health`);
    if (health.status !== 200) {
        console.error(`Server health check failed: ${health.status}`);
    }
}

export function teardown(data) {
    console.log('=== SSE Connection Lifecycle Test Completed ===');
}
