// 알림 목록 조회 부하 테스트
// API: GET /api/v1/notifications
// 커서 기반 페이징 성능 측정

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

const errorRate = new Rate('errors');
const listDuration = new Trend('notification_list_duration');
const cursorPageDuration = new Trend('cursor_page_duration');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        normal: {
            executor: 'ramping-vus',
            exec: 'normalList',
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
            exec: 'spikeList',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 20 },
                { duration: '10s', target: 200 },
                { duration: '40s', target: 200 },
                { duration: '10s', target: 20 },
                { duration: '10s', target: 0 },
            ],
            startTime: '3m30s',
            gracefulRampDown: '10s',
        },
        cursor_paging: {
            executor: 'constant-vus',
            exec: 'cursorPaging',
            vus: 30,
            duration: '1m30s',
            startTime: '5m',
            gracefulStop: '10s',
        },
    },

    thresholds: {
        'notification_list_duration': ['p(95)<500', 'p(99)<1000'],
        'cursor_page_duration': ['p(95)<500'],
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

// === 시나리오 ===

export function normalList() {
    const user = randomUser();
    const token = generateJWT(user);
    const size = [10, 20, 30][Math.floor(Math.random() * 3)];

    const res = http.get(`${BASE_URL}/api/v1/notifications?size=${size}`, params(token));
    const ok = check(res, {
        '목록 조회 200': (r) => r.status === 200,
        '응답 구조 정상': (r) => {
            try { const b = JSON.parse(r.body); return b.data && Array.isArray(b.data.notifications); }
            catch { return false; }
        },
    });
    errorRate.add(ok ? 0 : 1);
    listDuration.add(res.timings.duration);
    sleep(1);
}

export function spikeList() {
    const user = randomUser();
    const token = generateJWT(user);

    const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, params(token));
    const ok = check(res, { '스파이크 목록 200': (r) => r.status === 200 });
    errorRate.add(ok ? 0 : 1);
    listDuration.add(res.timings.duration);
    sleep(0.3);
}

export function cursorPaging() {
    const user = randomUser();
    const token = generateJWT(user);
    const p = params(token);

    group('커서 페이징', () => {
        const res1 = http.get(`${BASE_URL}/api/v1/notifications?size=10`, p);
        check(res1, { '1페이지 200': (r) => r.status === 200 });
        listDuration.add(res1.timings.duration);

        try {
            const body = JSON.parse(res1.body);
            if (body.data && body.data.hasMore && body.data.cursor) {
                const res2 = http.get(
                    `${BASE_URL}/api/v1/notifications?size=10&cursor=${body.data.cursor}`, p
                );
                check(res2, { '2페이지 200': (r) => r.status === 200 });
                cursorPageDuration.add(res2.timings.duration);
            }
        } catch (e) { /* ignore */ }
    });
    sleep(1);
}

export function setup() {
    console.log('=== 알림 목록 조회 테스트 ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('시나리오: normal(50VU) → spike(200VU) → cursor_paging(30VU)');
    console.log('총 소요: ~6분 30초');
}

export function teardown() { console.log('=== 테스트 완료 ==='); }
