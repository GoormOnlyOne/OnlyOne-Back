import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// Schedule + Wallet 도메인 부하 테스트
// 대상 병목: Wallet 비관적 잠금 경합, Schedule N+1
// 총 소요시간: ~35분
// ============================================

// 커스텀 메트릭
const errorRate = new Rate('errors');
const scheduleJoinDuration = new Trend('schedule_join_duration');
const scheduleLeaveDuration = new Trend('schedule_leave_duration');
const scheduleListDuration = new Trend('schedule_list_duration');
const scheduleDeleteDuration = new Trend('schedule_delete_duration');
const walletTransactionDuration = new Trend('wallet_transaction_duration');
const walletContentionRate = new Rate('wallet_contention_rate');
const holdFailedConcurrency = new Counter('hold_failed_concurrency');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // 시나리오 1: Wallet hold 경합 (동일 wallet row lock, 100명 핫유저)
        wallet_hold_contention: {
            executor: 'constant-vus',
            exec: 'walletHoldContention',
            vus: 300,
            duration: '5m',
            gracefulStop: '30s',
        },

        // 시나리오 2: 일정 참여/탈퇴 반복 사이클
        schedule_join_leave_cycle: {
            executor: 'ramping-vus',
            exec: 'scheduleJoinLeaveCycle',
            startVUs: 0,
            stages: [
                { duration: '2m', target: 200 },
                { duration: '3m', target: 400 },
                { duration: '2m', target: 200 },
                { duration: '1m', target: 0 },
            ],
            startTime: '6m',
            gracefulRampDown: '30s',
        },

        // 시나리오 3: 일정 목록 N+1 (countBySchedule per schedule)
        schedule_list_n_plus_one: {
            executor: 'constant-vus',
            exec: 'scheduleListNPlusOne',
            vus: 200,
            duration: '4m',
            startTime: '15m',
            gracefulStop: '30s',
        },

        // 시나리오 4: 지갑 거래내역 페이징
        wallet_transaction_read: {
            executor: 'constant-vus',
            exec: 'walletTransactionRead',
            vus: 150,
            duration: '3m',
            startTime: '20m',
            gracefulStop: '30s',
        },

        // 시나리오 5: 동시 일정 삭제 (batchReleaseHoldBalance IN절)
        concurrent_schedule_delete: {
            executor: 'constant-vus',
            exec: 'concurrentScheduleDelete',
            vus: 100,
            duration: '3m',
            startTime: '24m',
            gracefulStop: '30s',
        },

        // 시나리오 6: 전체 일정 라이프사이클 혼합
        mixed_schedule_workflow: {
            executor: 'ramping-vus',
            exec: 'mixedScheduleWorkflow',
            startVUs: 0,
            stages: [
                { duration: '2m', target: 150 },
                { duration: '3m', target: 300 },
                { duration: '1m', target: 150 },
                { duration: '1m', target: 0 },
            ],
            startTime: '28m',
            gracefulRampDown: '30s',
        },
    },

    thresholds: {
        schedule_join_duration: ['p(95)<500'],
        wallet_contention_rate: ['rate<0.15'],
        schedule_list_duration: ['p(95)<400'],
        wallet_transaction_duration: ['p(95)<300'],
        http_req_duration: ['p(95)<1000'],
        http_req_failed: ['rate<0.05'],
        errors: ['rate<0.05'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('schedule_test_users', function () {
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

const scheduleData = new SharedArray('schedule_data', function () {
    const data = [];
    for (let i = 1; i <= 5000; i++) {
        data.push({
            scheduleId: i,
            clubId: ((i - 1) % 10000) + 1,
        });
    }
    return data;
});

// ============================================
// 유틸리티 함수
// ============================================
function generateJWT(user) {
    const now = Date.now();
    const expiryDate = now + (3600 * 1000);

    const header = { alg: 'HS512', typ: 'JWT' };
    const payload = {
        sub: user.userId.toString(),
        kakaoId: user.kakaoId.toString(),
        nickname: `testuser${user.userId}`,
        status: user.status,
        role: user.role,
        type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor(expiryDate / 1000),
    };

    const headerEncoded = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const payloadEncoded = encoding.b64encode(JSON.stringify(payload), 'rawurl');
    const signatureInput = `${headerEncoded}.${payloadEncoded}`;
    const signature = hmac('sha512', JWT_SECRET, signatureInput, 'base64rawurl');

    return `${signatureInput}.${signature}`;
}

function getHeaders(token) {
    return {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
    };
}

function getRandomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

function getRandomSchedule() {
    return scheduleData[Math.floor(Math.random() * scheduleData.length)];
}

// 핫 유저 패턴: __VU % 100으로 100명 유저에 집중 → row lock 경합 유도
function getHotUser() {
    const hotUserId = (__VU % 100) + 1;
    return testUsers[hotUserId - 1];
}

function validateResponse(response, expectedStatus, metricName) {
    const success = check(response, {
        [`${metricName}: status is ${expectedStatus}`]: (r) => r.status === expectedStatus,
        [`${metricName}: response time < 2s`]: (r) => r.timings.duration < 2000,
    });
    errorRate.add(!success);
    return success;
}

// ============================================
// 시나리오 1: Wallet Hold 경합 (동일 wallet row lock)
// ============================================
export function walletHoldContention() {
    const user = getHotUser();  // 100명 핫유저에 집중
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const schedule = getRandomSchedule();

    group('Wallet Hold Contention', () => {
        const res = http.request(
            'PATCH',
            `${BASE_URL}/api/v1/clubs/${schedule.clubId}/schedules/${schedule.scheduleId}/users`,
            null,
            { headers }
        );

        const isContention = res.status === 409 || res.status === 423 ||
            res.status === 500;

        check(res, {
            'wallet hold: accepted status': (r) =>
                r.status === 200 || r.status === 400 || r.status === 404 ||
                r.status === 409 || r.status === 423 || r.status === 500,
        });

        scheduleJoinDuration.add(res.timings.duration);

        if (isContention) {
            walletContentionRate.add(1);
            holdFailedConcurrency.add(1);
        } else {
            walletContentionRate.add(0);
        }

        if (res.timings.duration > 500 && __ITER % 20 === 0) {
            console.warn(`[Wallet Contention] userId=${user.userId}, duration=${res.timings.duration.toFixed(0)}ms, status=${res.status}`);
        }
    });

    sleep(0.5);
}

// ============================================
// 시나리오 2: 일정 참여/탈퇴 반복 사이클
// ============================================
export function scheduleJoinLeaveCycle() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const schedule = getRandomSchedule();

    group('Schedule Join/Leave Cycle', () => {
        // 참여
        const joinRes = http.request(
            'PATCH',
            `${BASE_URL}/api/v1/clubs/${schedule.clubId}/schedules/${schedule.scheduleId}/users`,
            null,
            { headers }
        );

        check(joinRes, {
            'schedule join: accepted status': (r) =>
                r.status === 200 || r.status === 400 || r.status === 404 || r.status === 409,
        });
        scheduleJoinDuration.add(joinRes.timings.duration);
        sleep(1);

        // 탈퇴
        const leaveRes = http.del(
            `${BASE_URL}/api/v1/clubs/${schedule.clubId}/schedules/${schedule.scheduleId}/users`,
            null,
            { headers }
        );

        check(leaveRes, {
            'schedule leave: accepted status': (r) =>
                r.status === 200 || r.status === 400 || r.status === 404,
        });
        scheduleLeaveDuration.add(leaveRes.timings.duration);
    });

    sleep(1);
}

// ============================================
// 시나리오 3: 일정 목록 N+1 (countBySchedule per schedule)
// ============================================
export function scheduleListNPlusOne() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const clubId = ((user.userId - 1) % 1000) + 1;

    group('Schedule List N+1', () => {
        const res = http.get(
            `${BASE_URL}/api/v1/clubs/${clubId}/schedules`,
            { headers }
        );

        check(res, {
            'schedule list: status 200': (r) => r.status === 200,
        });
        scheduleListDuration.add(res.timings.duration);

        if (res.timings.duration > 400 && __ITER % 30 === 0) {
            console.warn(`[Schedule N+1] clubId=${clubId}, duration=${res.timings.duration.toFixed(0)}ms`);
        }
    });

    sleep(1);
}

// ============================================
// 시나리오 4: 지갑 거래내역 페이징
// ============================================
export function walletTransactionRead() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Wallet Transaction Read', () => {
        const pages = [0, 1, 2];
        for (const page of pages) {
            const res = http.get(
                `${BASE_URL}/api/v1/users/wallet?page=${page}&size=20`,
                { headers }
            );

            check(res, {
                [`wallet transactions page=${page}: status 200`]: (r) => r.status === 200,
            });
            walletTransactionDuration.add(res.timings.duration);
            sleep(0.3);
        }
    });

    sleep(1);
}

// ============================================
// 시나리오 5: 동시 일정 삭제 (batchReleaseHoldBalance IN절)
// ============================================
export function concurrentScheduleDelete() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const schedule = getRandomSchedule();

    group('Concurrent Schedule Delete', () => {
        const res = http.del(
            `${BASE_URL}/api/v1/clubs/${schedule.clubId}/schedules/${schedule.scheduleId}`,
            null,
            { headers }
        );

        check(res, {
            'schedule delete: accepted status': (r) =>
                r.status === 200 || r.status === 403 || r.status === 404,
        });
        scheduleDeleteDuration.add(res.timings.duration);

        if (res.timings.duration > 500 && __ITER % 10 === 0) {
            console.warn(`[Schedule Delete] scheduleId=${schedule.scheduleId}, duration=${res.timings.duration.toFixed(0)}ms, status=${res.status}`);
        }
    });

    sleep(1);
}

// ============================================
// 시나리오 6: 전체 일정 라이프사이클 혼합
// ============================================
export function mixedScheduleWorkflow() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const schedule = getRandomSchedule();
    const clubId = schedule.clubId;

    group('Mixed Schedule Workflow', () => {
        const action = Math.random();

        if (action < 0.3) {
            // 30%: 일정 목록 조회
            const res = http.get(
                `${BASE_URL}/api/v1/clubs/${clubId}/schedules`,
                { headers }
            );
            validateResponse(res, 200, 'mixed_schedule_list');
            scheduleListDuration.add(res.timings.duration);
        } else if (action < 0.5) {
            // 20%: 일정 참여
            const res = http.request(
                'PATCH',
                `${BASE_URL}/api/v1/clubs/${clubId}/schedules/${schedule.scheduleId}/users`,
                null,
                { headers }
            );
            check(res, {
                'mixed join: accepted': (r) =>
                    r.status === 200 || r.status === 400 || r.status === 404 || r.status === 409,
            });
            scheduleJoinDuration.add(res.timings.duration);
        } else if (action < 0.7) {
            // 20%: 일정 탈퇴
            const res = http.del(
                `${BASE_URL}/api/v1/clubs/${clubId}/schedules/${schedule.scheduleId}/users`,
                null,
                { headers }
            );
            check(res, {
                'mixed leave: accepted': (r) =>
                    r.status === 200 || r.status === 400 || r.status === 404,
            });
            scheduleLeaveDuration.add(res.timings.duration);
        } else if (action < 0.85) {
            // 15%: 지갑 거래내역
            const res = http.get(
                `${BASE_URL}/api/v1/users/wallet?page=0&size=20`,
                { headers }
            );
            validateResponse(res, 200, 'mixed_wallet');
            walletTransactionDuration.add(res.timings.duration);
        } else {
            // 15%: 일정 상세
            const res = http.get(
                `${BASE_URL}/api/v1/clubs/${clubId}/schedules/${schedule.scheduleId}`,
                { headers }
            );
            check(res, {
                'mixed schedule detail: accepted': (r) =>
                    r.status === 200 || r.status === 404,
            });
        }
    });

    sleep(0.5);
}

// ============================================
// 테스트 라이프사이클
// ============================================
export function setup() {
    console.log('=== Schedule + Wallet Load Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log(`Test Users: ${testUsers.length}`);
    console.log('');
    console.log('Testing 6 scenarios:');
    console.log('1. Wallet Hold Contention (0-5m, 300 VU, 100 hot users)');
    console.log('2. Schedule Join/Leave Cycle (6-14m, 0->400 VU)');
    console.log('3. Schedule List N+1 (15-19m, 200 VU)');
    console.log('4. Wallet Transaction Read (20-23m, 150 VU)');
    console.log('5. Concurrent Schedule Delete (24-27m, 100 VU)');
    console.log('6. Mixed Schedule Workflow (28-35m, 0->300 VU)');
    console.log('');
    console.log('Total Duration: ~35 minutes');
    console.log('============================================');
}

export function teardown(data) {
    console.log('');
    console.log('=== Schedule + Wallet Load Test Completed ===');
    console.log('Review metrics: schedule_join_duration, wallet_contention_rate,');
    console.log('hold_failed_concurrency, schedule_list_duration');
    console.log('===============================================');
}
