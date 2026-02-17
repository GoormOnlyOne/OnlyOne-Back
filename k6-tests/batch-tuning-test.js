// 알림 배치 파라미터 튜닝 테스트
//
// 목적: BATCH_SIZE, batch-processing-interval 조합별 성능 비교
// 사용법:
//   1. application.yml에서 batch-size, batch-processing-interval 변경
//   2. 서버 재시작
//   3. k6 run k6-tests/batch-tuning-test.js
//   4. 결과 비교
//
// 테스트 구성 (~3분, 빠른 반복):
//   - notification_write: 30 VU, 2m30s - 알림 생성 (배치 큐 투입)
//   - notification_read: 20 VU, 2m30s - 알림 조회 (쓰기 중 읽기 성능)
//   - batch_monitor: 1 VU, 3m - 배치 상태 모니터링 (큐 깊이 추적)

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// 커스텀 메트릭
// ============================================
const createDuration = new Trend('notification_create_duration');
const listDuration = new Trend('notification_list_duration');
const unreadDuration = new Trend('unread_count_duration');
const batchQueueDepth = new Gauge('batch_queue_depth');
const batchActiveUsers = new Gauge('batch_active_users');
const createThroughput = new Counter('notification_create_count');
const createErrors = new Rate('create_error_rate');
const readErrors = new Rate('read_error_rate');

// ============================================
// 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';
const CONFIG_LABEL = __ENV.CONFIG_LABEL || 'default';

export const options = {
    scenarios: {
        // 알림 생성 (배치 큐 투입 부하)
        notification_write: {
            executor: 'constant-vus',
            exec: 'writeNotifications',
            vus: 30,
            duration: '2m30s',
            gracefulStop: '10s',
        },

        // 알림 조회 (쓰기 중 읽기 성능)
        notification_read: {
            executor: 'constant-vus',
            exec: 'readNotifications',
            vus: 20,
            duration: '2m30s',
            gracefulStop: '10s',
        },

        // 배치 상태 모니터링
        batch_monitor: {
            executor: 'constant-vus',
            exec: 'monitorBatch',
            vus: 1,
            duration: '3m',
            gracefulStop: '5s',
        },
    },

    thresholds: {
        notification_create_duration: ['p(95)<500'],
        notification_list_duration: ['p(95)<400'],
        unread_count_duration: ['p(95)<200'],
        create_error_rate: ['rate<0.1'],
        read_error_rate: ['rate<0.1'],
    },
};

// ============================================
// 테스트 사용자
// ============================================
const testUsers = new SharedArray('test_users', function () {
    const users = [];
    for (let i = 1; i <= 500; i++) {
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

function getVuUser() {
    return testUsers[__VU % testUsers.length];
}

// ============================================
// Setup: 설정 정보 출력
// ============================================
export function setup() {
    console.log(`\n=== 배치 튜닝 테스트 시작 ===`);
    console.log(`CONFIG: ${CONFIG_LABEL}`);
    console.log(`BASE_URL: ${BASE_URL}`);

    // 배치 상태 조회로 현재 설정 확인
    const monitorUser = testUsers[0];
    const token = generateJWT(monitorUser);
    const statusRes = http.get(`${BASE_URL}/api/v1/notifications/batch-status`, {
        headers: getHeaders(token),
        tags: { name: 'setup_batch_status' },
    });

    if (statusRes.status === 200) {
        try {
            const body = JSON.parse(statusRes.body);
            const data = body.data || body;
            console.log(`현재 배치 설정: batchSize=${data.batchSize}, maxQueue=${data.maxQueueSizePerUser}`);
        } catch (e) {
            console.log(`배치 상태 응답: ${statusRes.body}`);
        }
    }

    // 알림 생성 테스트 엔드포인트 확인
    const testRes = http.post(`${BASE_URL}/test/notifications/create?type=LIKE&count=1`, null, {
        headers: getHeaders(token),
        tags: { name: 'setup_test_create' },
    });
    console.log(`테스트 엔드포인트: status=${testRes.status}`);
    if (testRes.status !== 200) {
        console.warn(`테스트 엔드포인트 실패! 서버가 local 프로필로 실행 중인지 확인하세요.`);
        console.warn(`응답: ${testRes.body}`);
    }

    console.log(`=== Setup 완료 ===\n`);
    return { configLabel: CONFIG_LABEL };
}

// ============================================
// 시나리오 1: 알림 생성 (배치 큐 투입)
// ============================================
export function writeNotifications() {
    const user = getVuUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    // 1회 반복에 알림 3개 생성 (빠른 생성으로 큐 부하)
    const startTime = Date.now();
    const res = http.post(
        `${BASE_URL}/test/notifications/create?type=LIKE&count=3`,
        null,
        { headers, tags: { name: 'create_notification' } }
    );
    const duration = Date.now() - startTime;

    createDuration.add(duration);
    const ok = check(res, {
        'create: status 200': (r) => r.status === 200,
    });
    createErrors.add(!ok);
    if (ok) {
        createThroughput.add(3);
    }

    sleep(0.1); // 100ms 간격
}

// ============================================
// 시나리오 2: 알림 조회 (읽기 부하)
// ============================================
export function readNotifications() {
    const user = getVuUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    // 알림 목록 조회
    {
        const startTime = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers, tags: { name: 'list_notifications' },
        });
        const duration = Date.now() - startTime;
        listDuration.add(duration);
        const ok = check(res, { 'list: status 200': (r) => r.status === 200 });
        readErrors.add(!ok);
    }

    sleep(0.2);

    // 읽지 않은 알림 개수 조회
    {
        const startTime = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers, tags: { name: 'unread_count' },
        });
        const duration = Date.now() - startTime;
        unreadDuration.add(duration);
        const ok = check(res, { 'unread: status 200': (r) => r.status === 200 });
        readErrors.add(!ok);
    }

    sleep(0.3);
}

// ============================================
// 시나리오 3: 배치 모니터링
// ============================================
export function monitorBatch() {
    const user = testUsers[0];
    const token = generateJWT(user);
    const headers = getHeaders(token);

    const res = http.get(`${BASE_URL}/api/v1/notifications/batch-status`, {
        headers, tags: { name: 'batch_status' },
    });

    if (res.status === 200) {
        try {
            const body = JSON.parse(res.body);
            const data = body.data || body;
            batchQueueDepth.add(data.totalQueuedNotifications || 0);
            batchActiveUsers.add(data.activeUsers || 0);

            // 10초마다 상태 로그
            if (__ITER % 5 === 0) {
                console.log(
                    `[Monitor] queue=${data.totalQueuedNotifications}, ` +
                    `activeUsers=${data.activeUsers}, ` +
                    `avgQueue=${(data.averageQueueSize || 0).toFixed(1)}, ` +
                    `batchSize=${data.batchSize}, maxQueue=${data.maxQueueSizePerUser}`
                );
            }
        } catch (e) {
            // parse error, skip
        }
    }

    sleep(2); // 2초 간격 모니터링
}

// ============================================
// Teardown: 결과 요약
// ============================================
export function teardown(data) {
    console.log(`\n=== 배치 튜닝 테스트 완료 ===`);
    console.log(`CONFIG: ${data.configLabel}`);
    console.log(`결과를 다른 설정과 비교하여 최적값을 찾으세요.`);
    console.log(`=== 끝 ===\n`);
}
