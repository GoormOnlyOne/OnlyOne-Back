// 알림 삭제 부하 테스트
// API: DELETE /api/v1/notifications/{id}
// 실제 알림 ID를 조회 후 삭제 → 404 방지

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

const errorRate = new Rate('errors');
const deleteDuration = new Trend('delete_duration');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        normal_delete: {
            executor: 'ramping-vus',
            exec: 'normalDelete',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 20 },
                { duration: '2m', target: 20 },
                { duration: '30s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },
        spike_delete: {
            executor: 'ramping-vus',
            exec: 'spikeDelete',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 5 },
                { duration: '10s', target: 50 },
                { duration: '40s', target: 50 },
                { duration: '10s', target: 5 },
                { duration: '10s', target: 0 },
            ],
            startTime: '3m30s',
            gracefulRampDown: '10s',
        },
    },

    thresholds: {
        'delete_duration': ['p(95)<300', 'p(99)<500'],
        'http_req_failed': ['rate<0.15'],
        'errors': ['rate<0.15'],
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

function fetchNotificationIds(token) {
    const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, params(token));
    if (res.status !== 200) return [];
    try {
        const body = JSON.parse(res.body);
        if (body.data && body.data.notifications) {
            return body.data.notifications.map(n => n.notificationId);
        }
    } catch (e) { /* ignore */ }
    return [];
}

// === 시나리오 ===

export function normalDelete() {
    const user = randomUser();
    const token = generateJWT(user);

    group('일반 삭제', () => {
        const ids = fetchNotificationIds(token);
        if (ids.length > 0) {
            const targetId = ids[ids.length - 1];
            const res = http.del(
                `${BASE_URL}/api/v1/notifications/${targetId}`, null, params(token)
            );
            const ok = check(res, { '삭제 200': (r) => r.status === 200 });
            errorRate.add(ok ? 0 : 1);
            deleteDuration.add(res.timings.duration);
        } else {
            errorRate.add(0);
        }
    });
    sleep(1.5);
}

export function spikeDelete() {
    const user = randomUser();
    const token = generateJWT(user);

    const ids = fetchNotificationIds(token);
    if (ids.length > 0) {
        const targetId = ids[ids.length - 1];
        const res = http.del(
            `${BASE_URL}/api/v1/notifications/${targetId}`, null, params(token)
        );
        const ok = check(res, { '스파이크 삭제 200': (r) => r.status === 200 });
        errorRate.add(ok ? 0 : 1);
        deleteDuration.add(res.timings.duration);
    } else {
        errorRate.add(0);
    }
    sleep(0.5);
}

export function setup() {
    console.log('=== 알림 삭제 테스트 ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('시나리오: normal_delete(20VU) → spike_delete(50VU)');
    console.log('총 소요: ~5분');
    console.log('※ seed-reset.sql 실행 후 테스트 권장');
}

export function teardown() { console.log('=== 테스트 완료 ==='); }
