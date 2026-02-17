import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// Schedule 병목 프로파일링 테스트
// 목적: N+1, Wallet 경합, Lazy 로딩 등 실제 병목 측정
// 총 소요시간: ~10분
// 데이터: 500 클럽 × 20 스케줄, 스케줄당 9명 참여
// ============================================

// 커스텀 메트릭
const errorRate = new Rate('errors');

// 시나리오별 지연 측정
const scheduleListDuration = new Trend('schedule_list_duration', true);
const scheduleDetailDuration = new Trend('schedule_detail_duration', true);
const scheduleUserListDuration = new Trend('schedule_user_list_duration', true);
const scheduleJoinDuration = new Trend('schedule_join_duration', true);
const scheduleLeaveDuration = new Trend('schedule_leave_duration', true);
const scheduleDeleteDuration = new Trend('schedule_delete_duration', true);
const walletContentionRate = new Rate('wallet_contention_rate');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // Phase 1: N+1 측정 — getScheduleList (20 스케줄/클럽 → 41 쿼리)
        schedule_list_n1: {
            executor: 'constant-vus',
            exec: 'scheduleListTest',
            vus: 200,
            duration: '2m',
            startTime: '0s',
            gracefulStop: '10s',
        },

        // Phase 2: 참여/탈퇴 (wallet hold + 8쿼리 직렬)
        join_leave_cycle: {
            executor: 'ramping-vus',
            exec: 'joinLeaveCycle',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 300 },
                { duration: '2m', target: 300 },
                { duration: '30s', target: 0 },
            ],
            startTime: '2m30s',
            gracefulRampDown: '10s',
        },

        // Phase 3: 스케줄 상세 + 참여자 목록 (추가 엔드포인트)
        detail_and_users: {
            executor: 'constant-vus',
            exec: 'detailAndUserList',
            vus: 150,
            duration: '2m',
            startTime: '6m30s',
            gracefulStop: '10s',
        },

        // Phase 4: 혼합 워크로드 (실제 사용 패턴)
        mixed_realistic: {
            executor: 'ramping-vus',
            exec: 'mixedWorkload',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 200 },
                { duration: '1m30s', target: 400 },
                { duration: '30s', target: 0 },
            ],
            startTime: '9m',
            gracefulRampDown: '10s',
        },
    },

    thresholds: {
        schedule_list_duration: ['p(95)<1000', 'p(50)<300'],
        schedule_detail_duration: ['p(95)<300'],
        schedule_user_list_duration: ['p(95)<300'],
        schedule_join_duration: ['p(95)<500'],
        schedule_leave_duration: ['p(95)<500'],
        http_req_failed: ['rate<0.10'],
        errors: ['rate<0.10'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('bottleneck_users', function () {
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

// 500 클럽 × 20 스케줄 = 10,000
const scheduleData = new SharedArray('bottleneck_schedules', function () {
    const data = [];
    for (let i = 1; i <= 10000; i++) {
        data.push({
            scheduleId: i,
            clubId: Math.floor((i - 1) / 20) + 1,  // 1~500
        });
    }
    return data;
});

// N+1 측정용: 클럽 1~500 (각 20개 스케줄)
const denseClubIds = new SharedArray('dense_clubs', function () {
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
// Phase 1: Schedule List N+1 측정
// 클럽당 20 스케줄 → 1 + 2×20 = 41 쿼리 예상
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

        if (res.timings.duration > 500 && __ITER % 50 === 0) {
            console.warn(`[N+1] clubId=${clubId}, ${res.timings.duration.toFixed(0)}ms, status=${res.status}`);
        }
    });

    sleep(0.3);
}

// ============================================
// Phase 2: Join/Leave Cycle (Wallet 경합 + 쿼리 직렬)
// ============================================
export function joinLeaveCycle() {
    const user = randomUser();
    const token = generateJWT(user);
    const sched = randomSchedule();
    const opts = headers(token);

    group('join_leave', () => {
        // Join
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

        if (joinRes.timings.duration > 500 && __ITER % 30 === 0) {
            console.warn(`[Join] sched=${sched.scheduleId}, ${joinRes.timings.duration.toFixed(0)}ms, status=${joinRes.status}`);
        }

        sleep(0.5);

        // Leave
        if (joinRes.status === 200) {
            const leaveRes = http.del(
                `${BASE_URL}/api/v1/clubs/${sched.clubId}/schedules/${sched.scheduleId}/users`,
                null, opts
            );
            check(leaveRes, {
                'leave: accepted': (r) => [200, 400, 404].includes(r.status),
            });
            scheduleLeaveDuration.add(leaveRes.timings.duration);
        }
    });

    sleep(0.5);
}

// ============================================
// Phase 3: Detail + User List
// ============================================
export function detailAndUserList() {
    const user = randomUser();
    const token = generateJWT(user);
    const sched = randomSchedule();
    const opts = headers(token);

    group('detail_userlist', () => {
        // Schedule Detail
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

        sleep(0.2);

        // Participant List
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

    sleep(0.5);
}

// ============================================
// Phase 4: Mixed Realistic Workload
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

    sleep(0.3);
}

// ============================================
// Lifecycle
// ============================================
export function setup() {
    console.log('=== Schedule Bottleneck Profiling Test ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('Data: 500 clubs × 20 schedules, 9 participants each');
    console.log('');
    console.log('Phase 1 (0-2m):    Schedule List N+1 — 200 VU');
    console.log('Phase 2 (2.5-6m):  Join/Leave Cycle — 0→300 VU');
    console.log('Phase 3 (6.5-8.5m): Detail + User List — 150 VU');
    console.log('Phase 4 (9-11.5m): Mixed Realistic — 0→400 VU');
    console.log('');
    console.log('Total: ~12 minutes');
    console.log('==========================================');

    // Smoke test
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
    console.log('=== Schedule Bottleneck Test Complete ===');
    console.log('Key metrics to analyze:');
    console.log('  schedule_list_duration  — N+1 query impact (target: p95 < 1s)');
    console.log('  schedule_join_duration  — Wallet + 8 queries (target: p95 < 500ms)');
    console.log('  wallet_contention_rate  — Lock contention (target: < 15%)');
    console.log('  schedule_detail/user_list — Baseline read perf');
    console.log('=========================================');
}
