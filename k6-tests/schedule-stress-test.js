import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// Schedule 고부하 스트레스 테스트
// 목적: 2차 최적화 후 한계치 확인 + 추가 병목 발견
// VU: 기존 대비 2배, 임계값 강화
// 총 소요시간: ~15분
// ============================================

const errorRate = new Rate('errors');

const scheduleListDuration = new Trend('schedule_list_duration', true);
const scheduleDetailDuration = new Trend('schedule_detail_duration', true);
const scheduleUserListDuration = new Trend('schedule_user_list_duration', true);
const scheduleJoinDuration = new Trend('schedule_join_duration', true);
const scheduleLeaveDuration = new Trend('schedule_leave_duration', true);
const walletContentionRate = new Rate('wallet_contention_rate');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // Phase 1: Schedule List — 400 VU, 3분
        schedule_list_stress: {
            executor: 'constant-vus',
            exec: 'scheduleListTest',
            vus: 400,
            duration: '3m',
            startTime: '0s',
            gracefulStop: '10s',
        },

        // Phase 2: Join/Leave Cycle — 0→600 VU, 4분
        join_leave_stress: {
            executor: 'ramping-vus',
            exec: 'joinLeaveCycle',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 600 },
                { duration: '2m30s', target: 600 },
                { duration: '30s', target: 0 },
            ],
            startTime: '3m30s',
            gracefulRampDown: '10s',
        },

        // Phase 3: Detail + User List — 300 VU, 3분
        detail_users_stress: {
            executor: 'constant-vus',
            exec: 'detailAndUserList',
            vus: 300,
            duration: '3m',
            startTime: '8m',
            gracefulStop: '10s',
        },

        // Phase 4: Mixed Realistic — 0→800 VU, 3분
        mixed_stress: {
            executor: 'ramping-vus',
            exec: 'mixedWorkload',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 400 },
                { duration: '1m30s', target: 800 },
                { duration: '1m', target: 800 },
            ],
            startTime: '11m30s',
            gracefulRampDown: '10s',
        },
    },

    thresholds: {
        // 강화된 임계값 (2차 최적화 후)
        schedule_list_duration: ['p(95)<200', 'p(50)<100'],
        schedule_detail_duration: ['p(95)<150'],
        schedule_user_list_duration: ['p(95)<150'],
        schedule_join_duration: ['p(95)<300'],
        schedule_leave_duration: ['p(95)<300'],
        http_req_failed: ['rate<0.05'],
        errors: ['rate<0.05'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('stress_users', function () {
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

const scheduleData = new SharedArray('stress_schedules', function () {
    const data = [];
    for (let i = 1; i <= 10000; i++) {
        data.push({
            scheduleId: i,
            clubId: Math.floor((i - 1) / 20) + 1,
        });
    }
    return data;
});

const denseClubIds = new SharedArray('stress_clubs', function () {
    const ids = [];
    for (let i = 1; i <= 500; i++) ids.push(i);
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
function randomSchedule() { return scheduleData[Math.floor(Math.random() * scheduleData.length)]; }
function randomClub() { return denseClubIds[Math.floor(Math.random() * denseClubIds.length)]; }

// ============================================
// Phase 1: Schedule List Stress (400 VU)
// ============================================
export function scheduleListTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const clubId = randomClub();

    group('schedule_list', () => {
        const res = http.get(
            `${BASE_URL}/api/v1/clubs/${clubId}/schedules`,
            headers(token)
        );

        const ok = check(res, {
            'list: status 200': (r) => r.status === 200,
        });
        errorRate.add(!ok);
        scheduleListDuration.add(res.timings.duration);

        if (res.timings.duration > 200 && __ITER % 100 === 0) {
            console.warn(`[List.Slow] clubId=${clubId}, ${res.timings.duration.toFixed(0)}ms`);
        }
    });

    sleep(0.2);
}

// ============================================
// Phase 2: Join/Leave Cycle (600 VU)
// ============================================
export function joinLeaveCycle() {
    const user = randomUser();
    const token = generateJWT(user);
    const sched = randomSchedule();
    const opts = headers(token);

    group('join_leave', () => {
        const joinRes = http.request(
            'PATCH',
            `${BASE_URL}/api/v1/clubs/${sched.clubId}/schedules/${sched.scheduleId}/users`,
            null, opts
        );
        check(joinRes, {
            'join: accepted': (r) => [200, 400, 404, 409].includes(r.status),
        });
        scheduleJoinDuration.add(joinRes.timings.duration);

        const isContention = joinRes.status === 409 || joinRes.status === 500;
        walletContentionRate.add(isContention ? 1 : 0);

        if (joinRes.timings.duration > 300 && __ITER % 50 === 0) {
            console.warn(`[Join.Slow] sched=${sched.scheduleId}, ${joinRes.timings.duration.toFixed(0)}ms, s=${joinRes.status}`);
        }

        sleep(0.3);

        if (joinRes.status === 200) {
            const leaveRes = http.del(
                `${BASE_URL}/api/v1/clubs/${sched.clubId}/schedules/${sched.scheduleId}/users`,
                null, opts
            );
            check(leaveRes, {
                'leave: accepted': (r) => [200, 400, 404].includes(r.status),
            });
            scheduleLeaveDuration.add(leaveRes.timings.duration);

            if (leaveRes.timings.duration > 300 && __ITER % 50 === 0) {
                console.warn(`[Leave.Slow] sched=${sched.scheduleId}, ${leaveRes.timings.duration.toFixed(0)}ms, s=${leaveRes.status}`);
            }
        }
    });

    sleep(0.3);
}

// ============================================
// Phase 3: Detail + User List (300 VU)
// ============================================
export function detailAndUserList() {
    const user = randomUser();
    const token = generateJWT(user);
    const sched = randomSchedule();
    const opts = headers(token);

    group('detail_userlist', () => {
        const detailRes = http.get(
            `${BASE_URL}/api/v1/clubs/${sched.clubId}/schedules/${sched.scheduleId}`,
            opts
        );
        check(detailRes, {
            'detail: status ok': (r) => r.status === 200 || r.status === 404,
        });
        if (detailRes.status === 200) {
            scheduleDetailDuration.add(detailRes.timings.duration);
        }

        sleep(0.1);

        const userListRes = http.get(
            `${BASE_URL}/api/v1/clubs/${sched.clubId}/schedules/${sched.scheduleId}/users`,
            opts
        );
        check(userListRes, {
            'user_list: status ok': (r) => r.status === 200 || r.status === 404,
        });
        if (userListRes.status === 200) {
            scheduleUserListDuration.add(userListRes.timings.duration);
        }
    });

    sleep(0.3);
}

// ============================================
// Phase 4: Mixed Stress (800 VU)
// 40% list, 25% join, 20% leave, 10% detail, 5% userlist
// ============================================
export function mixedWorkload() {
    const user = randomUser();
    const token = generateJWT(user);
    const sched = randomSchedule();
    const opts = headers(token);
    const action = Math.random();

    group('mixed', () => {
        if (action < 0.40) {
            const res = http.get(`${BASE_URL}/api/v1/clubs/${sched.clubId}/schedules`, opts);
            check(res, { 'mixed list: ok': (r) => r.status === 200 });
            scheduleListDuration.add(res.timings.duration);
        } else if (action < 0.65) {
            const res = http.request('PATCH',
                `${BASE_URL}/api/v1/clubs/${sched.clubId}/schedules/${sched.scheduleId}/users`,
                null, opts);
            check(res, { 'mixed join: accepted': (r) => [200,400,404,409].includes(r.status) });
            scheduleJoinDuration.add(res.timings.duration);
        } else if (action < 0.85) {
            const res = http.del(
                `${BASE_URL}/api/v1/clubs/${sched.clubId}/schedules/${sched.scheduleId}/users`,
                null, opts);
            check(res, { 'mixed leave: accepted': (r) => [200,400,404].includes(r.status) });
            scheduleLeaveDuration.add(res.timings.duration);
        } else if (action < 0.95) {
            const res = http.get(
                `${BASE_URL}/api/v1/clubs/${sched.clubId}/schedules/${sched.scheduleId}`, opts);
            if (res.status === 200) scheduleDetailDuration.add(res.timings.duration);
        } else {
            const res = http.get(
                `${BASE_URL}/api/v1/clubs/${sched.clubId}/schedules/${sched.scheduleId}/users`, opts);
            if (res.status === 200) scheduleUserListDuration.add(res.timings.duration);
        }
    });

    sleep(0.2);
}

// ============================================
// Lifecycle
// ============================================
export function setup() {
    console.log('=== Schedule STRESS Test (2x Load) ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('');
    console.log('Phase 1 (0-3m):      Schedule List — 400 VU');
    console.log('Phase 2 (3.5-7.5m):  Join/Leave — 0→600 VU');
    console.log('Phase 3 (8-11m):     Detail+UserList — 300 VU');
    console.log('Phase 4 (11.5-14.5m): Mixed — 0→800 VU');
    console.log('');
    console.log('Thresholds tightened: list p95<200ms, join/leave p95<300ms');
    console.log('Total: ~15 minutes');
    console.log('=======================================');

    const user = testUsers[0];
    const token = generateJWT(user);
    const smokeRes = http.get(`${BASE_URL}/api/v1/clubs/1/schedules`, headers(token));
    console.log(`Smoke test: status=${smokeRes.status}, body_length=${smokeRes.body.length}`);
    if (smokeRes.status !== 200) {
        console.error('Smoke test FAILED! Check if test data is seeded.');
    }
}

export function teardown() {
    console.log('');
    console.log('=== Schedule STRESS Test Complete ===');
    console.log('Compare with previous bottleneck test:');
    console.log('  Previous: 200/300/150/400 VU, thresholds 1s/500ms');
    console.log('  Current:  400/600/300/800 VU, thresholds 200ms/300ms');
    console.log('=====================================');
}
