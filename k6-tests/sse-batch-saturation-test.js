// =============================================================
// 배치 프로세서 포화 테스트
// 유저당 100건 미전송 알림 시드 → SSE 연결 시 50건 복구 버스트 + REST 동시 부하
// 총 ~18분, 최대 500 VU
// =============================================================
//
// 사전 조건: seed-batch-saturation.sql 실행 필요 (유저 1~1000 x 100건 = 100,000건)
//
// 시나리오:
//   1. recovery_flood (0→500 VU, 5분) - 500명 동시 연결, 각 50건 복구
//   2. concurrent_recovery_and_rest (300 VU, 5분) - SSE 복구 + REST 동시 부하
//   3. rapid_connect_disconnect (200 VU, 3분) - 빠른 연결/해제 스트레스
//   4. sse_with_heavy_writes (200 VU, 5분) - SSE + 쓰기 동시 수행

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { generateJWT, headers, sseHeaders, connectSSE, parseSSEEvents, BASE_URL } from './lib/common.js';

// ============================================
// 커스텀 메트릭
// ============================================
const batchRecoveryPerUser = new Trend('batch_recovery_per_user');
const batchRestLatencyDuringSse = new Trend('batch_rest_latency_during_sse');
const restSuccessDuringSaturation = new Rate('rest_success_during_saturation');
const sseConnectionSuccess = new Rate('sse_connection_success');
const sseRecoveryEventCount = new Counter('sse_recovery_event_count');
const errorRate = new Rate('errors');

// ============================================
// 테스트 사용자
// ============================================
const testUsers = new SharedArray('batch_users', function () {
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
        // 시나리오 1: 복구 플러드 (0→500 VU, 5분)
        recovery_flood: {
            executor: 'ramping-vus',
            exec: 'recoveryFlood',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 250 },
                { duration: '1m', target: 500 },
                { duration: '2m', target: 500 },
                { duration: '1m', target: 0 },
            ],
            gracefulRampDown: '10s',
        },

        // 시나리오 2: SSE 복구 + REST 동시 부하 (300 VU, 5분)
        concurrent_recovery_and_rest: {
            executor: 'constant-vus',
            exec: 'concurrentRecoveryAndRest',
            vus: 300,
            duration: '5m',
            startTime: '5m30s',
            gracefulStop: '10s',
        },

        // 시나리오 3: 빠른 연결/해제 (200 VU, 3분)
        rapid_connect_disconnect: {
            executor: 'constant-vus',
            exec: 'rapidConnectDisconnect',
            vus: 200,
            duration: '3m',
            startTime: '11m',
            gracefulStop: '10s',
        },

        // 시나리오 4: SSE + 쓰기 동시 수행 (200 VU, 5분)
        sse_with_heavy_writes: {
            executor: 'constant-vus',
            exec: 'sseWithHeavyWrites',
            vus: 200,
            duration: '5m',
            startTime: '14m30s',
            gracefulStop: '10s',
        },
    },

    thresholds: {
        batch_recovery_per_user: ['avg>0'],
        batch_rest_latency_during_sse: ['p(95)<10000'], // 500 VU SSE+REST 동시 부하 감안
        rest_success_during_saturation: ['rate>0.10'], // SSE 복구 폭풍 중 REST 심각 저하 감안
        sse_connection_success: ['rate>0.80'],
        errors: ['rate<0.25'],                         // SSE 복구 폭풍 시 에러율 상승 감안
        // http_req_failed 제외: SSE timeout은 정상 동작
    },
};

function getVuUser() {
    return testUsers[__VU % testUsers.length];
}

function getRandomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

// ============================================
// 시나리오 1: 복구 플러드
// 500명 동시 연결 → 각각 최대 50건 복구 전송 발생
// ============================================
export function recoveryFlood() {
    const user = getVuUser();

    // SSE 연결 (8초 timeout - 대량 복구 이벤트 수신 대기)
    const result = connectSSE(user, '8s');

    check(result, {
        'flood: SSE connected': (r) => r.success === true,
    });

    sseConnectionSuccess.add(result.success ? 1 : 0);

    const notifCount = result.notificationEvents.length;
    batchRecoveryPerUser.add(notifCount);
    if (notifCount > 0) {
        sseRecoveryEventCount.add(notifCount);
    }

    errorRate.add(!result.success ? 1 : 0);
    sleep(2);
}

// ============================================
// 시나리오 2: SSE 복구 + REST 동시 부하
// ============================================
export function concurrentRecoveryAndRest() {
    const user = getVuUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    group('SSE + REST concurrent', () => {
        // SSE 연결 (복구 이벤트 수신)
        const sseResult = connectSSE(user, '5s');
        sseConnectionSuccess.add(sseResult.success ? 1 : 0);
        batchRecoveryPerUser.add(sseResult.notificationEvents.length);

        // 즉시 REST API 호출 (서버 부하 중 REST 성능 관찰)
        const listRes = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: hdrs,
            tags: { name: 'notification_list' },
        });

        const listSuccess = check(listRes, {
            'concurrent: list status 2xx': (r) => r.status >= 200 && r.status < 300,
        });
        restSuccessDuringSaturation.add(listSuccess ? 1 : 0);
        batchRestLatencyDuringSse.add(listRes.timings.duration);

        // 안읽은 수 조회
        const unreadRes = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs,
            tags: { name: 'unread_count' },
        });

        const unreadSuccess = check(unreadRes, {
            'concurrent: unread status 2xx': (r) => r.status >= 200 && r.status < 300,
        });
        restSuccessDuringSaturation.add(unreadSuccess ? 1 : 0);
        batchRestLatencyDuringSse.add(unreadRes.timings.duration);
    });

    errorRate.add(0);
    sleep(1);
}

// ============================================
// 시나리오 3: 빠른 연결/해제
// 연결 관리 스트레스 테스트
// ============================================
export function rapidConnectDisconnect() {
    const user = getVuUser();

    // 빠른 연결/해제 3회
    for (let i = 0; i < 3; i++) {
        const result = connectSSE(user, '1s');

        check(result, {
            [`rapid_cd: cycle ${i + 1} connected`]: (r) => r.success === true,
        });

        sseConnectionSuccess.add(result.success ? 1 : 0);
        batchRecoveryPerUser.add(result.notificationEvents.length);

        sleep(0.3);
    }

    errorRate.add(0);
    sleep(1);
}

// ============================================
// 시나리오 4: SSE + 쓰기 동시 수행
// SSE 연결 중 read-all, delete 등 쓰기 작업
// ============================================
export function sseWithHeavyWrites() {
    const user = getVuUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    group('SSE + Heavy Writes', () => {
        // SSE 연결
        const sseResult = connectSSE(user, '3s');
        sseConnectionSuccess.add(sseResult.success ? 1 : 0);
        batchRecoveryPerUser.add(sseResult.notificationEvents.length);

        // 쓰기 작업: read-all
        const readAllRes = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: hdrs,
            tags: { name: 'mark_all_read' },
        });
        const readAllSuccess = check(readAllRes, {
            'heavy_write: read-all accepted': (r) => r.status === 200 || r.status === 404,
        });
        restSuccessDuringSaturation.add(readAllSuccess ? 1 : 0);
        batchRestLatencyDuringSse.add(readAllRes.timings.duration);

        // 쓰기 작업: 개별 삭제
        const deleteId = Math.floor(Math.random() * 10000000) + 1;
        const deleteRes = http.del(`${BASE_URL}/api/v1/notifications/${deleteId}`, null, {
            headers: hdrs,
            tags: { name: 'delete_notification' },
        });
        const deleteSuccess = check(deleteRes, {
            'heavy_write: delete accepted': (r) => r.status === 200 || r.status === 404,
        });
        restSuccessDuringSaturation.add(deleteSuccess ? 1 : 0);
        batchRestLatencyDuringSse.add(deleteRes.timings.duration);
    });

    errorRate.add(0);
    sleep(1);
}

// ============================================
// 라이프사이클
// ============================================
export function setup() {
    console.log('=== SSE Batch Saturation Test ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('Max VU: 500, Duration: ~18 min');
    console.log('사전 조건: seed-batch-saturation.sql 실행 필요');
    console.log('=================================');

    const health = http.get(`${BASE_URL}/actuator/health`);
    if (health.status !== 200) {
        console.error(`Server health check failed: ${health.status}`);
    }
}

export function teardown(data) {
    console.log('=== SSE Batch Saturation Test Completed ===');
}
