// 알림 부하 테스트 (로컬 환경용 - VU 절반)
// 원본: notification-load-test.js에서 VU/시간 축소

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

const errorRate = new Rate('errors');
const notificationListDuration = new Trend('notification_list_duration');
const unreadCountDuration = new Trend('unread_count_duration');
const markAsReadDuration = new Trend('mark_as_read_duration');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // 시나리오 1: 일반 워크로드 (50 VU)
        normal_workload: {
            executor: 'ramping-vus',
            exec: 'normalWorkload',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 50 },
                { duration: '2m', target: 50 },
                { duration: '30s', target: 0 },
            ],
            gracefulRampDown: '30s',
        },

        // 시나리오 2: 스파이크 (200 VU)
        spike_test: {
            executor: 'ramping-vus',
            exec: 'spikeWorkload',
            startVUs: 0,
            stages: [
                { duration: '20s', target: 30 },
                { duration: '10s', target: 200 },
                { duration: '1m', target: 200 },
                { duration: '20s', target: 30 },
                { duration: '10s', target: 0 },
            ],
            startTime: '4m',
            gracefulRampDown: '30s',
        },

        // 시나리오 3: 스트레스 (최대 300 VU)
        stress_test: {
            executor: 'ramping-vus',
            exec: 'stressWorkload',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 100 },
                { duration: '2m', target: 200 },
                { duration: '1m', target: 300 },
                { duration: '1m', target: 200 },
                { duration: '30s', target: 0 },
            ],
            startTime: '7m',
            gracefulRampDown: '30s',
        },

        // 시나리오 4: 소크 (100 VU, 5분)
        soak_test: {
            executor: 'constant-vus',
            exec: 'soakWorkload',
            vus: 100,
            duration: '5m',
            startTime: '13m',
            gracefulStop: '30s',
        },

        // 시나리오 5: 읽기 전용 (150 VU)
        read_only_workload: {
            executor: 'constant-vus',
            exec: 'readOnlyWorkload',
            vus: 150,
            duration: '2m',
            startTime: '19m',
            gracefulStop: '30s',
        },

        // 시나리오 6: 쓰기 중심 (50 VU)
        write_heavy_workload: {
            executor: 'constant-vus',
            exec: 'writeHeavyWorkload',
            vus: 50,
            duration: '2m',
            startTime: '22m',
            gracefulStop: '30s',
        },
    },

    thresholds: {
        http_req_duration: ['p(95)<1000', 'p(99)<2000'],
        http_req_failed: ['rate<0.05'],
        errors: ['rate<0.1'],
        notification_list_duration: ['p(95)<500'],
        unread_count_duration: ['p(95)<200'],
        mark_as_read_duration: ['p(95)<300'],
    },
};

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

function getRandomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

function validateResponse(res, name) {
    const success = check(res, {
        [`${name}: status 2xx`]: (r) => r.status >= 200 && r.status < 300,
    });
    if (!success) errorRate.add(1);
    else errorRate.add(0);
    return success;
}

// 시나리오 1: 일반 워크로드
export function normalWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Normal Workload', () => {
        const action = Math.random();
        if (action < 0.4) {
            const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, { headers });
            validateResponse(res, 'notification list');
            notificationListDuration.add(res.timings.duration);
        } else if (action < 0.7) {
            const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
            validateResponse(res, 'unread count');
            unreadCountDuration.add(res.timings.duration);
        } else {
            const notifId = Math.floor(Math.random() * 10000000) + 1;
            const res = http.put(`${BASE_URL}/api/v1/notifications/${notifId}/read`, null, { headers });
            check(res, { 'mark read: accepted': (r) => r.status === 200 || r.status === 404 });
            markAsReadDuration.add(res.timings.duration);
        }
    });
    sleep(1);
}

// 시나리오 2: 스파이크
export function spikeWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, { headers });
    validateResponse(res, 'spike notification list');
    notificationListDuration.add(res.timings.duration);

    sleep(0.5);
}

// 시나리오 3: 스트레스
export function stressWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Stress Workload', () => {
        const res1 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, { headers });
        validateResponse(res1, 'stress list');
        notificationListDuration.add(res1.timings.duration);

        const res2 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
        validateResponse(res2, 'stress unread');
        unreadCountDuration.add(res2.timings.duration);
    });

    sleep(0.5);
}

// 시나리오 4: 소크
export function soakWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    const action = Math.random();
    if (action < 0.5) {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, { headers });
        validateResponse(res, 'soak list');
        notificationListDuration.add(res.timings.duration);
    } else {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
        validateResponse(res, 'soak unread');
        unreadCountDuration.add(res.timings.duration);
    }

    sleep(1.5);
}

// 시나리오 5: 읽기 전용
export function readOnlyWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Read Only', () => {
        http.get(`${BASE_URL}/api/v1/notifications?size=20`, { headers });
        http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
    });

    sleep(0.5);
}

// 시나리오 6: 쓰기 중심
export function writeHeavyWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Write Heavy', () => {
        const notifId = Math.floor(Math.random() * 10000000) + 1;
        const res = http.put(`${BASE_URL}/api/v1/notifications/${notifId}/read`, null, { headers });
        check(res, { 'write mark read: accepted': (r) => r.status === 200 || r.status === 404 });
        markAsReadDuration.add(res.timings.duration);
    });

    sleep(1);
}

export function setup() {
    console.log('=== Notification Load Test (Light) ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('Max VU: 300, Total Duration: ~24 minutes');
    console.log('=======================================');
}

export function teardown(data) {
    console.log('=== Test Completed ===');
}
