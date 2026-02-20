// 알림 읽음 처리 부하 테스트
// API: PUT /api/v1/notifications/{id}/read (단건)
//      PUT /api/v1/notifications/read-all (전체)
// 실제 알림 ID를 조회 후 사용 → 404 방지

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

const errorRate = new Rate('errors');
const markReadDuration = new Trend('mark_read_duration');
const markAllReadDuration = new Trend('mark_all_read_duration');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        single_read: {
            executor: 'ramping-vus',
            exec: 'singleMarkRead',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 30 },
                { duration: '2m', target: 30 },
                { duration: '30s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },
        bulk_read: {
            executor: 'constant-vus',
            exec: 'markAllRead',
            vus: 20,
            duration: '1m30s',
            startTime: '3m30s',
            gracefulStop: '10s',
        },
        spike_read: {
            executor: 'ramping-vus',
            exec: 'spikeMarkRead',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 10 },
                { duration: '10s', target: 100 },
                { duration: '40s', target: 100 },
                { duration: '10s', target: 10 },
                { duration: '10s', target: 0 },
            ],
            startTime: '5m30s',
            gracefulRampDown: '10s',
        },
    },

    thresholds: {
        'mark_read_duration': ['p(95)<300', 'p(99)<500'],
        'mark_all_read_duration': ['p(95)<500', 'p(99)<1000'],
        'http_req_failed': ['rate<0.1'],
        'errors': ['rate<0.1'],
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

/** 목록에서 실제 알림 ID 가져오기 */
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

export function singleMarkRead() {
    const user = randomUser();
    const token = generateJWT(user);

    group('단건 읽음 처리', () => {
        const ids = fetchNotificationIds(token);
        if (ids.length > 0) {
            const targetId = ids[Math.floor(Math.random() * ids.length)];
            const res = http.put(
                `${BASE_URL}/api/v1/notifications/${targetId}/read`, null, params(token)
            );
            const ok = check(res, { '단건 읽음 200': (r) => r.status === 200 });
            errorRate.add(ok ? 0 : 1);
            markReadDuration.add(res.timings.duration);
        } else {
            errorRate.add(0);
        }
    });
    sleep(1);
}

export function markAllRead() {
    const user = randomUser();
    const token = generateJWT(user);

    const res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, params(token));
    const ok = check(res, { '전체 읽음 200': (r) => r.status === 200 });
    errorRate.add(ok ? 0 : 1);
    markAllReadDuration.add(res.timings.duration);
    sleep(1.5);
}

export function spikeMarkRead() {
    const user = randomUser();
    const token = generateJWT(user);

    const ids = fetchNotificationIds(token);
    if (ids.length > 0) {
        const targetId = ids[Math.floor(Math.random() * ids.length)];
        const res = http.put(
            `${BASE_URL}/api/v1/notifications/${targetId}/read`, null, params(token)
        );
        const ok = check(res, { '스파이크 읽음 200': (r) => r.status === 200 });
        errorRate.add(ok ? 0 : 1);
        markReadDuration.add(res.timings.duration);
    } else {
        errorRate.add(0);
    }
    sleep(0.3);
}

export function setup() {
    console.log('=== 알림 읽음 처리 테스트 ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('시나리오: single_read(30VU) → bulk_read(20VU) → spike_read(100VU)');
    console.log('총 소요: ~7분');
    console.log('※ seed-reset.sql 실행 후 테스트 권장');
}

export function teardown() { console.log('=== 테스트 완료 ==='); }
