// =============================================================
// 동시 쓰기 충돌 테스트
// setup()에서 실제 알림 ID 조회 → 다수 VU가 동일 알림에 read/delete 동시 요청
// 총 ~12분, 최대 100 VU
// =============================================================
//
// 사전 조건: seed-conflict-test.sql 실행 필요 (유저1에게 1,000건 알림)
//
// 시나리오:
//   1. concurrent_read_same_id (100 VU, 3분) - 동일 알림 동시 읽음 처리
//   2. concurrent_delete_same_id (50 VU, 3분) - 동일 알림 동시 삭제
//   3. read_delete_race (80 VU, 3분) - 절반 read, 절반 delete (레이스 컨디션)
//   4. read_all_while_single_read (100 VU, 3분) - read-all vs 개별 read

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { generateJWT, headers, fetchNotificationIds, BASE_URL } from './lib/common.js';

// ============================================
// 커스텀 메트릭
// ============================================
const serverErrorRate = new Rate('server_error_rate');
const concurrentReadLatency = new Trend('concurrent_read_latency');
const concurrentDeleteLatency = new Trend('concurrent_delete_latency');
const readAllLatency = new Trend('read_all_latency');
const conflictCount = new Counter('conflict_count');
const errorRate = new Rate('errors');

// ============================================
// 충돌 테스트용 유저 (유저 1 고정)
// ============================================
const conflictUser = {
    userId: 1,
    kakaoId: 10000001,
    status: 'ACTIVE',
    role: 'ROLE_USER',
};

// ============================================
// 시나리오 설정
// ============================================
export const options = {
    scenarios: {
        // 시나리오 1: 동일 알림 동시 읽음 (100 VU, 3분)
        concurrent_read_same_id: {
            executor: 'constant-vus',
            exec: 'concurrentReadSameId',
            vus: 100,
            duration: '3m',
            gracefulStop: '10s',
        },

        // 시나리오 2: 동일 알림 동시 삭제 (50 VU, 3분)
        concurrent_delete_same_id: {
            executor: 'constant-vus',
            exec: 'concurrentDeleteSameId',
            vus: 50,
            duration: '3m',
            startTime: '3m30s',
            gracefulStop: '10s',
        },

        // 시나리오 3: read/delete 레이스 (80 VU, 3분)
        read_delete_race: {
            executor: 'constant-vus',
            exec: 'readDeleteRace',
            vus: 80,
            duration: '3m',
            startTime: '7m',
            gracefulStop: '10s',
        },

        // 시나리오 4: read-all vs 개별 read (100 VU, 3분)
        read_all_while_single_read: {
            executor: 'constant-vus',
            exec: 'readAllWhileSingleRead',
            vus: 100,
            duration: '3m',
            startTime: '10m30s',
            gracefulStop: '10s',
        },
    },

    thresholds: {
        server_error_rate: ['rate<0.01'],      // 500 에러 1% 미만
        concurrent_read_latency: ['p(95)<500'],
        concurrent_delete_latency: ['p(95)<500'],
        errors: ['rate<0.10'],
        // http_req_failed 제외: 삭제된 알림 재접근 시 404는 정상 동작
    },
};

// ============================================
// setup: 실제 알림 ID 조회
// ============================================
export function setup() {
    console.log('=== Notification Concurrent Conflict Test ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('Max VU: 100, Duration: ~12 min');
    console.log('사전 조건: seed-conflict-test.sql 실행 필요');
    console.log('=============================================');

    const health = http.get(`${BASE_URL}/actuator/health`);
    if (health.status !== 200) {
        console.error(`Server health check failed: ${health.status}`);
    }

    // 유저 1의 알림 ID 목록 조회
    const token = generateJWT(conflictUser);
    const ids = fetchNotificationIds(token, 30);
    console.log(`Fetched ${ids.length} notification IDs for conflict testing`);

    return { notificationIds: ids };
}

// ============================================
// 시나리오 1: 동일 알림 동시 읽음 처리
// 100 VU가 같은 알림 ID에 read 요청
// ============================================
export function concurrentReadSameId(data) {
    const token = generateJWT(conflictUser);
    const hdrs = headers(token);
    const ids = data.notificationIds;

    if (!ids || ids.length === 0) {
        sleep(1);
        return;
    }

    group('Concurrent Read Same ID', () => {
        // 모든 VU가 같은 ID를 타겟 (라운드 로빈)
        const targetId = ids[__ITER % ids.length];

        const res = http.put(`${BASE_URL}/api/v1/notifications/${targetId}/read`, null, {
            headers: hdrs,
            tags: { name: 'concurrent_read' },
        });

        const isServerError = res.status >= 500;
        serverErrorRate.add(isServerError ? 1 : 0);

        if (isServerError) {
            conflictCount.add(1);
        }

        check(res, {
            'concurrent_read: no 500 error': (r) => r.status < 500,
            'concurrent_read: valid response': (r) => r.status === 200 || r.status === 404,
        });

        concurrentReadLatency.add(res.timings.duration);
        errorRate.add(isServerError ? 1 : 0);
    });

    sleep(0.5);
}

// ============================================
// 시나리오 2: 동일 알림 동시 삭제
// 50 VU가 같은 알림 ID에 delete 요청
// ============================================
export function concurrentDeleteSameId(data) {
    const token = generateJWT(conflictUser);
    const hdrs = headers(token);
    const ids = data.notificationIds;

    if (!ids || ids.length === 0) {
        sleep(1);
        return;
    }

    group('Concurrent Delete Same ID', () => {
        const targetId = ids[__ITER % ids.length];

        const res = http.del(`${BASE_URL}/api/v1/notifications/${targetId}`, null, {
            headers: hdrs,
            tags: { name: 'concurrent_delete' },
        });

        const isServerError = res.status >= 500;
        serverErrorRate.add(isServerError ? 1 : 0);

        if (isServerError) {
            conflictCount.add(1);
        }

        check(res, {
            'concurrent_delete: no 500 error': (r) => r.status < 500,
            'concurrent_delete: valid response': (r) => r.status === 200 || r.status === 404,
        });

        concurrentDeleteLatency.add(res.timings.duration);
        errorRate.add(isServerError ? 1 : 0);
    });

    sleep(0.5);
}

// ============================================
// 시나리오 3: read/delete 레이스 컨디션
// 절반은 read, 절반은 delete (같은 알림 ID)
// ============================================
export function readDeleteRace(data) {
    const token = generateJWT(conflictUser);
    const hdrs = headers(token);
    const ids = data.notificationIds;

    if (!ids || ids.length === 0) {
        sleep(1);
        return;
    }

    group('Read/Delete Race', () => {
        const targetId = ids[__ITER % ids.length];
        const isReader = __VU % 2 === 0;

        let res;
        if (isReader) {
            res = http.put(`${BASE_URL}/api/v1/notifications/${targetId}/read`, null, {
                headers: hdrs,
                tags: { name: 'race_read' },
            });
            concurrentReadLatency.add(res.timings.duration);
        } else {
            res = http.del(`${BASE_URL}/api/v1/notifications/${targetId}`, null, {
                headers: hdrs,
                tags: { name: 'race_delete' },
            });
            concurrentDeleteLatency.add(res.timings.duration);
        }

        const isServerError = res.status >= 500;
        serverErrorRate.add(isServerError ? 1 : 0);

        if (isServerError) {
            conflictCount.add(1);
        }

        check(res, {
            'race: no 500 error': (r) => r.status < 500,
            'race: valid response': (r) => r.status === 200 || r.status === 404,
        });

        errorRate.add(isServerError ? 1 : 0);
    });

    sleep(0.5);
}

// ============================================
// 시나리오 4: read-all vs 개별 read 동시 수행
// ============================================
export function readAllWhileSingleRead(data) {
    const token = generateJWT(conflictUser);
    const hdrs = headers(token);
    const ids = data.notificationIds;

    if (!ids || ids.length === 0) {
        sleep(1);
        return;
    }

    group('Read-All vs Single Read', () => {
        const isReadAll = __VU % 3 === 0; // 1/3은 read-all, 2/3는 개별 read

        let res;
        if (isReadAll) {
            res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
                headers: hdrs,
                tags: { name: 'read_all' },
            });
            readAllLatency.add(res.timings.duration);
        } else {
            const targetId = ids[__ITER % ids.length];
            res = http.put(`${BASE_URL}/api/v1/notifications/${targetId}/read`, null, {
                headers: hdrs,
                tags: { name: 'single_read' },
            });
            concurrentReadLatency.add(res.timings.duration);
        }

        const isServerError = res.status >= 500;
        serverErrorRate.add(isServerError ? 1 : 0);

        if (isServerError) {
            conflictCount.add(1);
        }

        check(res, {
            'read_all_mix: no 500 error': (r) => r.status < 500,
            'read_all_mix: valid response': (r) => r.status === 200 || r.status === 404,
        });

        errorRate.add(isServerError ? 1 : 0);
    });

    sleep(0.5);
}

export function teardown(data) {
    console.log('=== Notification Concurrent Conflict Test Completed ===');
    console.log(`Total conflict (500 errors) detected by metrics`);
}
