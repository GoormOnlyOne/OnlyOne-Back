import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// 크로스 도메인 사용자 여정 부하 테스트
// 여정 A: 신규 가입, 여정 B: 일상 활동, 여정 C: 리더 관리
// 총 소요시간: ~41분
// ============================================

// 커스텀 메트릭
const errorRate = new Rate('errors');
const journeyOnboardingDuration = new Trend('journey_onboarding_duration');
const journeyDailyDuration = new Trend('journey_daily_duration');
const journeyLeaderDuration = new Trend('journey_leader_duration');
const journeyCompletionRate = new Rate('journey_completion_rate');
const journeyStepFailures = new Counter('journey_step_failures');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // 시나리오 1: 신규 유저 온보딩 플로우
        journey_onboarding: {
            executor: 'ramping-vus',
            exec: 'journeyOnboarding',
            startVUs: 0,
            stages: [
                { duration: '2m', target: 100 },
                { duration: '5m', target: 200 },
                { duration: '2m', target: 100 },
                { duration: '1m', target: 0 },
            ],
            gracefulRampDown: '30s',
        },

        // 시나리오 2: 일상 활동 패턴
        journey_daily_active: {
            executor: 'constant-vus',
            exec: 'journeyDailyActive',
            vus: 300,
            duration: '10m',
            startTime: '11m',
            gracefulStop: '30s',
        },

        // 시나리오 3: 리더 관리 플로우
        journey_leader: {
            executor: 'constant-vus',
            exec: 'journeyLeader',
            vus: 50,
            duration: '8m',
            startTime: '22m',
            gracefulStop: '30s',
        },

        // 시나리오 4: 랜덤 여정 혼합
        mixed_journeys: {
            executor: 'constant-vus',
            exec: 'mixedJourneys',
            vus: 200,
            duration: '10m',
            startTime: '31m',
            gracefulStop: '30s',
        },
    },

    thresholds: {
        journey_completion_rate: ['rate>0.85'],
        journey_onboarding_duration: ['p(95)<15000'],
        journey_daily_duration: ['p(95)<15000'],
        journey_leader_duration: ['p(95)<15000'],
        http_req_duration: ['p(95)<1000'],
        http_req_failed: ['rate<0.05'],
        errors: ['rate<0.1'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('journey_test_users', function () {
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

const leaderUsers = new SharedArray('journey_leader_users', function () {
    // 리더 유저는 club 생성자 (userId 1-50 정도)
    const users = [];
    for (let i = 1; i <= 50; i++) {
        users.push({
            userId: i,
            kakaoId: 10000000 + i,
            status: 'ACTIVE',
            role: 'ROLE_USER',
        });
    }
    return users;
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

function getLeaderUser() {
    return leaderUsers[Math.floor(Math.random() * leaderUsers.length)];
}

function stepCheck(response, name, expectedStatuses) {
    const success = check(response, {
        [`${name}: accepted status`]: (r) => expectedStatuses.includes(r.status),
    });
    if (!success) {
        journeyStepFailures.add(1);
    }
    return success;
}

// ============================================
// 여정 A: 신규 가입 플로우
// auth/me → search → join club → browse feeds → like → chat → join schedule
// ============================================
export function journeyOnboarding() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const startTime = Date.now();
    let stepsCompleted = 0;
    const totalSteps = 7;

    group('Journey A: Onboarding', () => {
        // Step 1: auth/me 확인
        const res1 = http.get(`${BASE_URL}/api/v1/auth/me`, { headers });
        if (stepCheck(res1, 'auth/me', [200])) stepsCompleted++;
        sleep(1);

        // Step 2: 검색으로 클럽 찾기
        const res2 = http.get(
            `${BASE_URL}/api/v1/search/recommendations`,
            { headers }
        );
        if (stepCheck(res2, 'search recommendations', [200])) stepsCompleted++;
        sleep(1);

        // Step 3: 클럽 가입
        const clubId = ((__VU % 10000) + 1);
        const res3 = http.post(
            `${BASE_URL}/api/v1/clubs/${clubId}/join`,
            null,
            { headers }
        );
        if (stepCheck(res3, 'join club', [200, 400, 409])) stepsCompleted++;
        sleep(1);

        // Step 4: 피드 목록 조회
        const res4 = http.get(
            `${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&size=20`,
            { headers }
        );
        if (stepCheck(res4, 'browse feeds', [200])) stepsCompleted++;
        sleep(1);

        // Step 5: 좋아요
        const feedId = ((__VU * 10 + __ITER) % 100000) + 1;
        const res5 = http.put(
            `${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`,
            null,
            { headers }
        );
        if (stepCheck(res5, 'like feed', [200, 404])) stepsCompleted++;
        sleep(0.5);

        // Step 6: 채팅방 조회
        const res6 = http.get(
            `${BASE_URL}/api/v1/clubs/${clubId}/chat`,
            { headers }
        );
        if (stepCheck(res6, 'get chat rooms', [200])) stepsCompleted++;
        sleep(1);

        // Step 7: 일정 참여
        const scheduleId = ((__VU % 5000) + 1);
        const scheduleClubId = ((scheduleId - 1) % 10000) + 1;
        const res7 = http.request(
            'PATCH',
            `${BASE_URL}/api/v1/clubs/${scheduleClubId}/schedules/${scheduleId}/users`,
            null,
            { headers }
        );
        if (stepCheck(res7, 'join schedule', [200, 400, 404, 409])) stepsCompleted++;
    });

    const duration = Date.now() - startTime;
    journeyOnboardingDuration.add(duration);

    const completed = stepsCompleted >= totalSteps * 0.7;  // 70% 이상 완료 시 성공
    journeyCompletionRate.add(completed ? 1 : 0);

    if (!completed && __ITER % 10 === 0) {
        console.warn(`[Onboarding] Incomplete: ${stepsCompleted}/${totalSteps} steps, ${duration}ms`);
    }

    sleep(2);
}

// ============================================
// 여정 B: 일상 활동 패턴
// unread-count → feeds → popular → likes(3x) → comment → chat → mypage
// ============================================
export function journeyDailyActive() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const startTime = Date.now();
    let stepsCompleted = 0;
    const totalSteps = 8;
    const clubId = ((user.userId - 1) % 10000) + 1;

    group('Journey B: Daily Active', () => {
        // Step 1: 읽지 않은 알림 확인
        const res1 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
        if (stepCheck(res1, 'unread count', [200])) stepsCompleted++;
        sleep(0.5);

        // Step 2: 피드 목록
        const res2 = http.get(
            `${BASE_URL}/api/v1/feeds?page=0&size=20`,
            { headers }
        );
        if (stepCheck(res2, 'feed list', [200])) stepsCompleted++;
        sleep(1);

        // Step 3: 인기 피드
        const res3 = http.get(
            `${BASE_URL}/api/v1/feeds/popular?page=0&size=20`,
            { headers }
        );
        if (stepCheck(res3, 'popular feeds', [200])) stepsCompleted++;
        sleep(1);

        // Step 4-6: 좋아요 3회
        for (let i = 0; i < 3; i++) {
            const feedId = ((user.userId * 10 + i) % 100000) + 1;
            const res = http.put(
                `${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`,
                null,
                { headers }
            );
            if (stepCheck(res, `like ${i + 1}`, [200, 404])) stepsCompleted++;
            sleep(0.3);
        }

        // Step 7: 댓글 작성
        const feedId = ((user.userId * 7) % 100000) + 1;
        const commentPayload = JSON.stringify({
            content: `Daily comment from user ${user.userId}`,
        });
        const res7 = http.post(
            `${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/comments`,
            commentPayload,
            { headers }
        );
        if (stepCheck(res7, 'write comment', [200, 201, 404])) stepsCompleted++;
        sleep(0.5);

        // Step 8: 마이페이지
        const res8 = http.get(`${BASE_URL}/api/v1/users/mypage`, { headers });
        if (stepCheck(res8, 'mypage', [200])) stepsCompleted++;
    });

    const duration = Date.now() - startTime;
    journeyDailyDuration.add(duration);

    const completed = stepsCompleted >= totalSteps * 0.7;
    journeyCompletionRate.add(completed ? 1 : 0);

    sleep(2);
}

// ============================================
// 여정 C: 리더 관리 플로우
// my clubs → create schedule → create feed → check participants → update
// ============================================
export function journeyLeader() {
    const user = getLeaderUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const startTime = Date.now();
    let stepsCompleted = 0;
    const totalSteps = 5;
    const clubId = user.userId;  // 리더 userId == clubId (테스트 데이터 기준)

    group('Journey C: Leader', () => {
        // Step 1: 내 클럽 검색
        const res1 = http.get(
            `${BASE_URL}/api/v1/search/user`,
            { headers }
        );
        if (stepCheck(res1, 'my clubs', [200])) stepsCompleted++;
        sleep(1);

        // Step 2: 일정 생성
        const futureDate = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
        const schedulePayload = JSON.stringify({
            name: `Leader Schedule ${__VU}-${__ITER}`,
            location: '테스트 장소',
            scheduleTime: futureDate.toISOString().replace('Z', ''),
            cost: 10000,
            userLimit: 20,
        });
        const res2 = http.post(
            `${BASE_URL}/api/v1/clubs/${clubId}/schedules`,
            schedulePayload,
            { headers }
        );
        if (stepCheck(res2, 'create schedule', [200, 201, 403])) stepsCompleted++;
        sleep(1);

        // Step 3: 피드 작성
        const feedPayload = JSON.stringify({
            content: `Leader feed post from user ${user.userId}`,
            type: 'ORIGINAL',
        });
        const res3 = http.post(
            `${BASE_URL}/api/v1/clubs/${clubId}/feeds`,
            feedPayload,
            { headers }
        );
        if (stepCheck(res3, 'create feed', [200, 201, 403])) stepsCompleted++;
        sleep(1);

        // Step 4: 일정 참여자 확인
        const scheduleId = ((user.userId - 1) % 5000) + 1;
        const res4 = http.get(
            `${BASE_URL}/api/v1/clubs/${clubId}/schedules/${scheduleId}/users`,
            { headers }
        );
        if (stepCheck(res4, 'check participants', [200, 404])) stepsCompleted++;
        sleep(1);

        // Step 5: 클럽 정보 수정
        const updatePayload = JSON.stringify({
            description: `Updated by leader at ${Date.now()}`,
        });
        const res5 = http.request(
            'PATCH',
            `${BASE_URL}/api/v1/clubs/${clubId}`,
            updatePayload,
            { headers }
        );
        if (stepCheck(res5, 'update club', [200, 403])) stepsCompleted++;
    });

    const duration = Date.now() - startTime;
    journeyLeaderDuration.add(duration);

    const completed = stepsCompleted >= totalSteps * 0.6;
    journeyCompletionRate.add(completed ? 1 : 0);

    sleep(3);
}

// ============================================
// 시나리오 4: 랜덤 여정 혼합
// ============================================
export function mixedJourneys() {
    const action = Math.random();

    if (action < 0.4) {
        journeyDailyActive();
    } else if (action < 0.8) {
        journeyOnboarding();
    } else {
        journeyLeader();
    }
}

// ============================================
// 테스트 라이프사이클
// ============================================
export function setup() {
    console.log('=== User Journey Load Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log(`Test Users: ${testUsers.length}`);
    console.log('');
    console.log('Testing 4 scenarios:');
    console.log('1. Journey Onboarding (0-10m, 0->200 VU)');
    console.log('2. Journey Daily Active (11-21m, 300 VU)');
    console.log('3. Journey Leader (22-30m, 50 VU)');
    console.log('4. Mixed Journeys (31-41m, 200 VU)');
    console.log('');
    console.log('Total Duration: ~41 minutes');
    console.log('======================================');
}

export function teardown(data) {
    console.log('');
    console.log('=== User Journey Load Test Completed ===');
    console.log('Review metrics: journey_completion_rate,');
    console.log('journey_onboarding_duration, journey_daily_duration');
    console.log('=========================================');
}
