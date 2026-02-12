import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// Club 도메인 부하 테스트
// 대상 병목: 이벤트 발행 오버헤드, memberCount 경합
// 총 소요시간: ~21분
// ============================================

// 커스텀 메트릭
const errorRate = new Rate('errors');
const clubCreateDuration = new Trend('club_create_duration');
const clubJoinDuration = new Trend('club_join_duration');
const clubLeaveDuration = new Trend('club_leave_duration');
const memberCountContentionRate = new Rate('member_count_contention_rate');
const eventPublishErrors = new Counter('event_publish_errors');
const joinLimitExceeded = new Counter('join_limit_exceeded');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // 시나리오 1: 클럽 생성 + 이벤트 발행 (ClubCreatedEvent → ES + ChatRoom)
        club_create_with_events: {
            executor: 'constant-vus',
            exec: 'clubCreateWithEvents',
            vus: 50,
            duration: '4m',
            gracefulStop: '30s',
        },

        // 시나리오 2: 동시 가입/탈퇴 (memberCount 증감 경합)
        concurrent_join_leave: {
            executor: 'constant-vus',
            exec: 'concurrentJoinLeave',
            vus: 300,
            duration: '5m',
            startTime: '5m',
            gracefulStop: '30s',
        },

        // 시나리오 3: 가입 제한 스트레스 (userLimit 동시성)
        join_limit_stress: {
            executor: 'ramping-vus',
            exec: 'joinLimitStress',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 200 },
                { duration: '2m', target: 400 },
                { duration: '1m', target: 200 },
                { duration: '1m', target: 0 },
            ],
            startTime: '11m',
            gracefulRampDown: '30s',
        },

        // 시나리오 4: 전체 CRUD 혼합
        mixed_club_workload: {
            executor: 'ramping-vus',
            exec: 'mixedClubWorkload',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 80 },
                { duration: '2m', target: 150 },
                { duration: '1m', target: 0 },
            ],
            startTime: '17m',
            gracefulRampDown: '30s',
        },
    },

    thresholds: {
        club_create_duration: ['p(95)<1000'],
        club_join_duration: ['p(95)<500'],
        club_leave_duration: ['p(95)<500'],
        member_count_contention_rate: ['rate<0.1'],
        http_req_duration: ['p(95)<1000'],
        http_req_failed: ['rate<0.05'],
        errors: ['rate<0.1'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('club_test_users', function () {
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
    for (let i = 1; i <= 10000; i++) {
        ids.push(i);
    }
    return ids;
});

const categories = new SharedArray('club_categories', function () {
    return ['CULTURE', 'EXERCISE', 'TRAVEL', 'MUSIC', 'CRAFT', 'SOCIAL', 'LANGUAGE', 'FINANCE'];
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

function getRandomClubId() {
    return clubIds[Math.floor(Math.random() * clubIds.length)];
}

function getRandomCategory() {
    return categories[Math.floor(Math.random() * categories.length)];
}

// ============================================
// 시나리오 1: 클럽 생성 + 이벤트 발행 오버헤드
// ============================================
export function clubCreateWithEvents() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Club Create with Events', () => {
        const payload = JSON.stringify({
            name: `LoadTest Club ${__VU}-${__ITER}-${Date.now()}`,
            userLimit: 50,
            description: `Load test club created by VU ${__VU}`,
            city: '서울',
            district: '강남구',
            interestId: (((__VU + __ITER) % 8) + 1),
        });

        const res = http.post(
            `${BASE_URL}/clubs`,
            payload,
            { headers }
        );

        check(res, {
            'club create: status 200 or 201': (r) => r.status === 200 || r.status === 201,
        });

        clubCreateDuration.add(res.timings.duration);

        if (res.status >= 500) {
            eventPublishErrors.add(1);
            if (__ITER % 5 === 0) {
                console.warn(`[Club Create Error] status=${res.status}, duration=${res.timings.duration.toFixed(0)}ms`);
            }
        }

        errorRate.add(res.status !== 200 && res.status !== 201);
    });

    sleep(2);
}

// ============================================
// 시나리오 2: 동시 가입/탈퇴 (memberCount 경합)
// ============================================
export function concurrentJoinLeave() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    // 10개 클럽에 집중하여 memberCount 경합 유도
    const hotClubId = (__VU % 10) + 1;

    group('Concurrent Join/Leave', () => {
        const action = Math.random();

        if (action < 0.6) {
            // 60%: 가입
            const res = http.post(
                `${BASE_URL}/clubs/${hotClubId}/join`,
                null,
                { headers }
            );

            check(res, {
                'club join: accepted': (r) =>
                    r.status === 200 || r.status === 400 || r.status === 404 || r.status === 409,
            });

            clubJoinDuration.add(res.timings.duration);

            if (res.status === 409 || res.status === 500) {
                memberCountContentionRate.add(1);
            } else {
                memberCountContentionRate.add(0);
            }
        } else {
            // 40%: 탈퇴
            const res = http.del(
                `${BASE_URL}/clubs/${hotClubId}/leave`,
                null,
                { headers }
            );

            check(res, {
                'club leave: accepted': (r) =>
                    r.status === 200 || r.status === 400 || r.status === 404,
            });

            clubLeaveDuration.add(res.timings.duration);
        }
    });

    sleep(0.3);
}

// ============================================
// 시나리오 3: 가입 제한 스트레스 (userLimit 동시성)
// ============================================
export function joinLimitStress() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    // userLimit이 작은 클럽들에 동시 가입 시도
    const limitedClubId = (__VU % 50) + 1;

    const res = http.post(
        `${BASE_URL}/clubs/${limitedClubId}/join`,
        null,
        { headers }
    );

    check(res, {
        'join limit: accepted status': (r) =>
            r.status === 200 || r.status === 400 || r.status === 404 || r.status === 409,
    });

    clubJoinDuration.add(res.timings.duration);

    if (res.status === 400) {
        joinLimitExceeded.add(1);
    }

    sleep(0.3);
}

// ============================================
// 시나리오 4: 전체 CRUD 혼합
// ============================================
export function mixedClubWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Mixed Club Workload', () => {
        const action = Math.random();

        if (action < 0.1) {
            // 10%: 생성
            const payload = JSON.stringify({
                name: `Mixed Club ${__VU}-${Date.now()}`,
                userLimit: 30,
                description: 'Mixed workload club',
                city: '서울',
                district: '강남구',
                interestId: (((__VU + __ITER) % 8) + 1),
            });
            const res = http.post(`${BASE_URL}/clubs`, payload, { headers });
            clubCreateDuration.add(res.timings.duration);
        } else if (action < 0.5) {
            // 40%: 가입
            const clubId = getRandomClubId();
            const res = http.post(`${BASE_URL}/clubs/${clubId}/join`, null, { headers });
            clubJoinDuration.add(res.timings.duration);
        } else if (action < 0.8) {
            // 30%: 탈퇴
            const clubId = getRandomClubId();
            const res = http.del(`${BASE_URL}/clubs/${clubId}/leave`, null, { headers });
            clubLeaveDuration.add(res.timings.duration);
        } else {
            // 20%: 검색 (부수 부하)
            const res = http.get(
                `${BASE_URL}/search/recommendations`,
                { headers }
            );
            check(res, { 'mixed search: status 200': (r) => r.status === 200 });
        }
    });

    sleep(0.5);
}

// ============================================
// 테스트 라이프사이클
// ============================================
export function setup() {
    console.log('=== Club Domain Load Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log(`Test Users: ${testUsers.length}`);
    console.log('');
    console.log('Testing 4 scenarios:');
    console.log('1. Club Create with Events (0-4m, 50 VU)');
    console.log('2. Concurrent Join/Leave (5-10m, 300 VU)');
    console.log('3. Join Limit Stress (11-16m, 0->400 VU)');
    console.log('4. Mixed Club Workload (17-21m, 0->150 VU)');
    console.log('');
    console.log('Total Duration: ~21 minutes');
    console.log('=====================================');
}

export function teardown(data) {
    console.log('');
    console.log('=== Club Domain Load Test Completed ===');
    console.log('Review metrics: club_create_duration, club_join_duration,');
    console.log('member_count_contention_rate, join_limit_exceeded');
    console.log('========================================');
}
