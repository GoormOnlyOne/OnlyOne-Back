// 읽지 않은 알림 개수 조회 부하 테스트
// API: GET /api/v1/notifications/unread-count

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

const errorRate = new Rate('errors');
const unreadDuration = new Trend('unread_count_duration');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        normal: {
            executor: 'ramping-vus',
            exec: 'normalUnread',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50 },
                { duration: '2m', target: 50 },
                { duration: '30s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },
        spike: {
            executor: 'ramping-vus',
            exec: 'spikeUnread',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 30 },
                { duration: '10s', target: 300 },
                { duration: '40s', target: 300 },
                { duration: '10s', target: 30 },
                { duration: '10s', target: 0 },
            ],
            startTime: '3m30s',
            gracefulRampDown: '10s',
        },
        sustained: {
            executor: 'constant-vus',
            exec: 'sustainedUnread',
            vus: 100,
            duration: '2m',
            startTime: '5m',
            gracefulStop: '10s',
        },
    },

    thresholds: {
        'unread_count_duration': ['p(95)<200', 'p(99)<500'],
        'http_req_failed': ['rate<0.05'],
        'errors': ['rate<0.05'],
    },
};

const testUsers = new SharedArray('users', function () {
    const users = [];
    for (let i = 1; i <= 1000; i++) {
        users.push({ userId: i, kakaoId: 10000000 + i, status: 'ACTIVE', role: 'ROLE_USER' });
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
        status: user.status, role: user.role, type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor((now + 3600000) / 1000),
    };
    const h = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const p = encoding.b64encode(JSON.stringify(payload), 'rawurl');
    const sig = hmac('sha512', JWT_SECRET, `${h}.${p}`, 'base64rawurl');
    return `${h}.${p}.${sig}`;
}

function params(token) {
    return { headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

function randomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

export function normalUnread() {
    const user = randomUser();
    const token = generateJWT(user);

    const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, params(token));
    const ok = check(res, {
        '읽지않은 수 200': (r) => r.status === 200,
        '응답이 숫자': (r) => {
            try { const b = JSON.parse(r.body); return typeof b.data === 'number'; }
            catch { return false; }
        },
    });
    errorRate.add(ok ? 0 : 1);
    unreadDuration.add(res.timings.duration);
    sleep(0.5);
}

export function spikeUnread() {
    const user = randomUser();
    const token = generateJWT(user);

    const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, params(token));
    const ok = check(res, { '스파이크 읽지않은 수 200': (r) => r.status === 200 });
    errorRate.add(ok ? 0 : 1);
    unreadDuration.add(res.timings.duration);
    sleep(0.2);
}

export function sustainedUnread() {
    const user = randomUser();
    const token = generateJWT(user);

    const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, params(token));
    const ok = check(res, { '지속 읽지않은 수 200': (r) => r.status === 200 });
    errorRate.add(ok ? 0 : 1);
    unreadDuration.add(res.timings.duration);
    sleep(0.3);
}

export function setup() {
    console.log('=== 읽지 않은 알림 개수 조회 테스트 ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('시나리오: normal(50VU) → spike(300VU) → sustained(100VU)');
    console.log('총 소요: ~7분');
}

export function teardown() { console.log('=== 테스트 완료 ==='); }
