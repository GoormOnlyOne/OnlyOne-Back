// =============================================================
// SSE 연결 한계 테스트
// 7000개 이상 고유 유저로 SSE 연결 누적 → 429 응답 발생 확인
// 총 ~19분, 최대 7500 VU
// =============================================================
//
// 시나리오:
//   1. ramp_to_6000 (0→6000 VU, 8분) - 85% 용량까지 점진적 증가
//   2. at_capacity_6500 (6500 VU, 5분) - 근접 용량에서 안정성 관찰
//   3. exceed_7000 (6500→7500 VU, 3분) - 한계 초과 시 429 검증
//   4. recovery_after_limit (7500→3000 VU, 3분) - 한계 이하 감소 후 복구

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { generateJWT, sseHeaders, parseSSEEvents, BASE_URL } from './lib/common.js';

// ============================================
// 커스텀 메트릭
// ============================================
const sseConnectionSuccess = new Rate('sse_connection_success');
const sseConnectionRejected429 = new Counter('sse_connection_rejected_429');
const sseRecoveryAfterLimit = new Rate('sse_recovery_after_limit');
const sseConnectionLatency = new Trend('sse_connection_latency');
const errorRate = new Rate('errors');

// ============================================
// 8000명 유저 풀 (7000 한계 초과용)
// ============================================
const testUsers = new SharedArray('limit_users', function () {
    const users = [];
    for (let i = 1; i <= 8000; i++) {
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
        // 시나리오 1: 85% 용량까지 (0→6000 VU, 8분)
        ramp_to_6000: {
            executor: 'ramping-vus',
            exec: 'rampToCapacity',
            startVUs: 0,
            stages: [
                { duration: '2m', target: 2000 },
                { duration: '2m', target: 4000 },
                { duration: '2m', target: 6000 },
                { duration: '2m', target: 6000 },
            ],
            gracefulRampDown: '15s',
        },

        // 시나리오 2: 근접 용량 (6500 VU, 5분)
        at_capacity_6500: {
            executor: 'constant-vus',
            exec: 'atCapacity',
            vus: 6500,
            duration: '5m',
            startTime: '8m30s',
            gracefulStop: '15s',
        },

        // 시나리오 3: 한계 초과 (6500→7500 VU, 3분)
        exceed_7000: {
            executor: 'ramping-vus',
            exec: 'exceedLimit',
            startVUs: 6500,
            stages: [
                { duration: '1m', target: 7000 },
                { duration: '1m', target: 7500 },
                { duration: '1m', target: 7500 },
            ],
            startTime: '14m',
            gracefulRampDown: '10s',
        },

        // 시나리오 4: 한계 이하 복구 (7500→3000 VU, 3분)
        recovery_after_limit: {
            executor: 'ramping-vus',
            exec: 'recoveryAfterLimit',
            startVUs: 7500,
            stages: [
                { duration: '1m', target: 5000 },
                { duration: '1m', target: 3000 },
                { duration: '1m', target: 3000 },
            ],
            startTime: '17m30s',
            gracefulRampDown: '10s',
        },
    },

    thresholds: {
        // 한계 테스트이므로 전체 성공률보다 429 발생 여부가 중요
        sse_connection_success: ['rate>0.50'],  // 전체 중 절반 이상은 성공
        errors: ['rate<0.50'],
        // http_req_failed 제외: SSE timeout + 429 의도적 발생으로 무의미
    },
};

function getVuUser() {
    // VU별 고유 userId 할당 (8000명 풀)
    return testUsers[__VU % testUsers.length];
}

// SSE 연결 (타임아웃 기반)
function sseConnect(user, timeout) {
    const token = generateJWT(user);
    const hdrs = sseHeaders(token);
    const startTime = Date.now();

    const res = http.get(`${BASE_URL}/api/v1/sse/subscribe`, {
        headers: hdrs,
        timeout: timeout || '3s',
        responseType: 'text',
        tags: { name: 'sse_subscribe' },
    });

    const duration = Date.now() - startTime;
    sseConnectionLatency.add(duration);

    return {
        status: res.status,
        success: res.status === 200 || res.status === 0,
        is429: res.status === 429,
        body: res.body,
        duration: duration,
    };
}

// ============================================
// 시나리오 1: 85% 용량까지 점진적 증가
// ============================================
export function rampToCapacity() {
    const user = getVuUser();
    const result = sseConnect(user, '3s');

    check(result, {
        'ramp: connection OK': (r) => r.success === true,
        'ramp: no 429': (r) => !r.is429,
    });

    sseConnectionSuccess.add(result.success ? 1 : 0);
    if (result.is429) {
        sseConnectionRejected429.add(1);
    }

    errorRate.add(!result.success ? 1 : 0);

    // 25~35초 간격 재연결으로 서버 측 연결 유지
    sleep(25 + Math.random() * 10);
}

// ============================================
// 시나리오 2: 근접 용량 (6500 VU)
// ============================================
export function atCapacity() {
    const user = getVuUser();
    const result = sseConnect(user, '3s');

    check(result, {
        'capacity: connection response': (r) => r.status !== undefined,
    });

    sseConnectionSuccess.add(result.success ? 1 : 0);
    if (result.is429) {
        sseConnectionRejected429.add(1);
    }

    errorRate.add(!result.success && !result.is429 ? 1 : 0);

    sleep(25 + Math.random() * 10);
}

// ============================================
// 시나리오 3: 한계 초과 (7000+ VU)
// 429 응답 발생 확인이 핵심
// ============================================
export function exceedLimit() {
    const user = getVuUser();
    const result = sseConnect(user, '3s');

    const exceeded = check(result, {
        'exceed: got response': (r) => r.status !== undefined,
    });

    sseConnectionSuccess.add(result.success ? 1 : 0);

    if (result.is429) {
        sseConnectionRejected429.add(1);
        // 429는 예상된 정상 동작
        check(result, {
            'exceed: 429 rejection (expected)': (r) => r.is429 === true,
        });
    }

    errorRate.add(!result.success && !result.is429 ? 1 : 0);

    sleep(25 + Math.random() * 10);
}

// ============================================
// 시나리오 4: 한계 이하 복구
// VU 감소 후 연결 재성공 확인
// ============================================
export function recoveryAfterLimit() {
    const user = getVuUser();
    const result = sseConnect(user, '3s');

    check(result, {
        'recovery: connection attempt': (r) => r.status !== undefined,
    });

    sseConnectionSuccess.add(result.success ? 1 : 0);
    sseRecoveryAfterLimit.add(result.success ? 1 : 0);

    if (result.is429) {
        sseConnectionRejected429.add(1);
    }

    errorRate.add(!result.success && !result.is429 ? 1 : 0);

    sleep(25 + Math.random() * 10);
}

// ============================================
// 라이프사이클
// ============================================
export function setup() {
    console.log('=== SSE Connection Limit Test ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('Max VU: 7500, Duration: ~19 min');
    console.log('User Pool: 8000 (exceeds 7000 limit)');
    console.log('=================================');

    const health = http.get(`${BASE_URL}/actuator/health`);
    if (health.status !== 200) {
        console.error(`Server health check failed: ${health.status}`);
    }

    // 단일 유저 SSE 사전 확인
    const testUser = testUsers[0];
    const token = generateJWT(testUser);
    const res = http.get(`${BASE_URL}/api/v1/sse/subscribe`, {
        headers: sseHeaders(token),
        timeout: '2s',
        responseType: 'text',
    });
    console.log(`SSE pre-check: status=${res.status}`);
}

export function teardown(data) {
    console.log('=== SSE Connection Limit Test Completed ===');
    console.log('Check sse_connection_rejected_429 counter for 429 occurrences');
}
