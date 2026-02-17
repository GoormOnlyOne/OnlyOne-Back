import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// Club 도메인 병목 탐지 테스트 (축약 ~8분)
// 목적: detail 조회, join/leave 경합, memberCount 동시성 병목
// 검색(search)은 제외
// ============================================

const errorRate = new Rate('errors');
const clubDetailDuration = new Trend('club_detail_duration', true);
const clubJoinDuration = new Trend('club_join_duration', true);
const clubLeaveDuration = new Trend('club_leave_duration', true);
const memberCountContention = new Rate('member_count_contention');
const joinServerErrors = new Counter('join_server_errors');
const leaveServerErrors = new Counter('leave_server_errors');
const detailServerErrors = new Counter('detail_server_errors');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // Phase 1: Club Detail + Hot Join/Leave — 200 VU, 2.5분
        hot_club_contention: {
            executor: 'ramping-vus',
            exec: 'hotClubContention',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 200 },
                { duration: '1m30s', target: 200 },
                { duration: '30s', target: 0 },
            ],
            startTime: '0s',
            gracefulRampDown: '10s',
        },

        // Phase 2: Wide Club (전체 10000개 분산) — 300 VU, 2.5분
        wide_club_load: {
            executor: 'ramping-vus',
            exec: 'wideClubLoad',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 300 },
                { duration: '1m30s', target: 300 },
                { duration: '30s', target: 0 },
            ],
            startTime: '3m',
            gracefulRampDown: '10s',
        },

        // Phase 3: 고부하 혼합 (detail + join + leave) — 400 VU, 2분
        high_load_mixed: {
            executor: 'ramping-vus',
            exec: 'highLoadMixed',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 400 },
                { duration: '1m', target: 400 },
                { duration: '30s', target: 0 },
            ],
            startTime: '6m',
            gracefulRampDown: '10s',
        },
    },

    thresholds: {
        club_detail_duration: ['p(95)<500', 'p(50)<100'],
        club_join_duration: ['p(95)<500', 'p(50)<200'],
        club_leave_duration: ['p(95)<500', 'p(50)<200'],
        errors: ['rate<0.1'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('club_users', function () {
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

const clubIds = new SharedArray('club_ids', function () {
    const ids = [];
    for (let i = 1; i <= 10000; i++) ids.push(i);
    return ids;
});

// ============================================
// 유틸리티
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

function headers(token) {
    return { headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

function randomUser() { return testUsers[Math.floor(Math.random() * testUsers.length)]; }
function randomClub() { return clubIds[Math.floor(Math.random() * clubIds.length)]; }

// ============================================
// Phase 1: Hot Club — Detail + Join/Leave (10개 클럽 집중)
// ============================================
export function hotClubContention() {
    const user = randomUser();
    const token = generateJWT(user);
    const opts = headers(token);
    const hotClubId = (__VU % 10) + 1;

    group('hot_club', () => {
        const action = Math.random();

        if (action < 0.3) {
            // 30%: Detail 조회
            const res = http.get(`${BASE_URL}/api/v1/clubs/${hotClubId}`, opts);
            check(res, { 'hot detail: success': (r) => r.status === 200 });
            clubDetailDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) detailServerErrors.add(1);
        } else if (action < 0.7) {
            // 40%: Join
            const res = http.post(`${BASE_URL}/api/v1/clubs/${hotClubId}/join`, null, opts);
            check(res, { 'hot join: not server error': (r) => r.status < 500 });
            clubJoinDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            memberCountContention.add(res.status >= 500 ? 1 : 0);
            if (res.status >= 500) joinServerErrors.add(1);
        } else {
            // 30%: Leave
            const res = http.del(`${BASE_URL}/api/v1/clubs/${hotClubId}/leave`, null, opts);
            check(res, { 'hot leave: not server error': (r) => r.status < 500 });
            clubLeaveDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) leaveServerErrors.add(1);
        }
    });

    sleep(0.2);
}

// ============================================
// Phase 2: Wide Club — Detail + Join/Leave (전체 분산)
// ============================================
export function wideClubLoad() {
    const user = randomUser();
    const token = generateJWT(user);
    const opts = headers(token);
    const clubId = randomClub();

    group('wide_club', () => {
        const action = Math.random();

        if (action < 0.3) {
            // 30%: Detail 조회
            const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, opts);
            check(res, { 'wide detail: success': (r) => r.status === 200 });
            clubDetailDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) detailServerErrors.add(1);
        } else if (action < 0.65) {
            // 35%: Join
            const res = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/join`, null, opts);
            check(res, { 'wide join: not server error': (r) => r.status < 500 });
            clubJoinDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) joinServerErrors.add(1);
        } else {
            // 35%: Leave
            const res = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, opts);
            check(res, { 'wide leave: not server error': (r) => r.status < 500 });
            clubLeaveDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) leaveServerErrors.add(1);
        }
    });

    sleep(0.2);
}

// ============================================
// Phase 3: 고부하 혼합 (detail + join + leave) — 400 VU
// ============================================
export function highLoadMixed() {
    const user = randomUser();
    const token = generateJWT(user);
    const opts = headers(token);
    const action = Math.random();

    group('high_load', () => {
        if (action < 0.2) {
            // 20%: Detail hot club
            const hotClubId = (__VU % 20) + 1;
            const res = http.get(`${BASE_URL}/api/v1/clubs/${hotClubId}`, opts);
            clubDetailDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) detailServerErrors.add(1);
        } else if (action < 0.4) {
            // 20%: Join hot club
            const hotClubId = (__VU % 20) + 1;
            const res = http.post(`${BASE_URL}/api/v1/clubs/${hotClubId}/join`, null, opts);
            clubJoinDuration.add(res.timings.duration);
            memberCountContention.add(res.status >= 500 ? 1 : 0);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) joinServerErrors.add(1);
        } else if (action < 0.55) {
            // 15%: Leave hot club
            const hotClubId = (__VU % 20) + 1;
            const res = http.del(`${BASE_URL}/api/v1/clubs/${hotClubId}/leave`, null, opts);
            clubLeaveDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) leaveServerErrors.add(1);
        } else if (action < 0.7) {
            // 15%: Detail wide
            const clubId = randomClub();
            const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, opts);
            clubDetailDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) detailServerErrors.add(1);
        } else if (action < 0.85) {
            // 15%: Join wide
            const clubId = randomClub();
            const res = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/join`, null, opts);
            clubJoinDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) joinServerErrors.add(1);
        } else {
            // 15%: Leave wide
            const clubId = randomClub();
            const res = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, opts);
            clubLeaveDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) leaveServerErrors.add(1);
        }
    });

    sleep(0.15);
}

// ============================================
// Lifecycle
// ============================================
export function setup() {
    console.log('=== Club Bottleneck Test (~8min) ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('');
    console.log('Phase 1 (0-2.5m):   Hot Club (10 clubs) detail+join+leave — 0→200 VU');
    console.log('Phase 2 (3-5.5m):   Wide Club (10000 clubs) detail+join+leave — 0→300 VU');
    console.log('Phase 3 (6-8m):     High Load Mixed — 0→400 VU');
    console.log('');
    console.log('Endpoints: GET /{clubId}, POST /{clubId}/join, DELETE /{clubId}/leave');
    console.log('==============================================');

    // Smoke test: detail + join
    const user = testUsers[999];
    const token = generateJWT(user);
    const detailRes = http.get(`${BASE_URL}/api/v1/clubs/5000`, headers(token));
    console.log(`Smoke (detail club 5000): status=${detailRes.status}, ${detailRes.timings.duration.toFixed(0)}ms`);
    const joinRes = http.post(`${BASE_URL}/api/v1/clubs/5000/join`, null, headers(token));
    console.log(`Smoke (join club 5000): status=${joinRes.status}, ${joinRes.timings.duration.toFixed(0)}ms`);
}

export function teardown() {
    console.log('');
    console.log('=== Club Bottleneck Test Complete ===');
    console.log('Key metrics:');
    console.log('  club_detail_duration — 상세 조회 응답시간');
    console.log('  club_join_duration — p50 vs p95 gap = lock contention');
    console.log('  club_leave_duration — p50 vs p95 gap');
    console.log('  member_count_contention — 500 error rate on hot clubs');
    console.log('  join/leave/detail_server_errors — total 500s');
    console.log('=====================================');
}
