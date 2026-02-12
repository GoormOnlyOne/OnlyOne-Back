import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// Feed 도메인 부하 테스트
// 대상 병목: N+1 쿼리, Redis Lua XADD, Popular 정렬
// 총 소요시간: ~39분
// ============================================

// 커스텀 메트릭
const errorRate = new Rate('errors');
const feedListDuration = new Trend('feed_list_duration');
const likeToggleDuration = new Trend('like_toggle_duration');
const popularFeedDuration = new Trend('popular_feed_duration');
const personalFeedDuration = new Trend('personal_feed_duration');
const commentWriteDuration = new Trend('comment_write_duration');
const commentListDuration = new Trend('comment_list_duration');
const feedDetailDuration = new Trend('feed_detail_duration');
const feedNPlusOneDetected = new Rate('feed_n_plus_one_detected');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // 시나리오 1: Feed N+1 쿼리 테스트 (페이지 크기별 응답시간 스케일링)
        feed_n_plus_one_test: {
            executor: 'constant-vus',
            exec: 'feedNPlusOneTest',
            vus: 200,
            duration: '5m',
            gracefulStop: '30s',
        },

        // 시나리오 2: 좋아요 토글 경합 (Redis Lua XADD 스트림 무한 증가)
        like_toggle_contention: {
            executor: 'ramping-arrival-rate',
            exec: 'likeToggleContention',
            startRate: 100,
            timeUnit: '1s',
            preAllocatedVUs: 100,
            maxVUs: 500,
            stages: [
                { duration: '1m', target: 100 },
                { duration: '2m', target: 1000 },
                { duration: '2m', target: 2000 },
                { duration: '1m', target: 500 },
            ],
            startTime: '6m',
            gracefulStop: '30s',
        },

        // 시나리오 3: 인기 피드 스트레스 (LOG+TIMESTAMPDIFF 풀스캔)
        popular_feed_stress: {
            executor: 'ramping-vus',
            exec: 'popularFeedStress',
            startVUs: 0,
            stages: [
                { duration: '2m', target: 200 },
                { duration: '3m', target: 500 },
                { duration: '2m', target: 300 },
                { duration: '1m', target: 0 },
            ],
            startTime: '13m',
            gracefulRampDown: '30s',
        },

        // 시나리오 4: 개인화 피드 대량 조회 (resolveAccessibleClubIds 3회 DB 왕복)
        personal_feed_bulk: {
            executor: 'constant-vus',
            exec: 'personalFeedBulk',
            vus: 150,
            duration: '5m',
            startTime: '22m',
            gracefulStop: '30s',
        },

        // 시나리오 5: 댓글 쓰기 집중 (CascadeType.ALL orphanRemoval)
        comment_write_heavy: {
            executor: 'constant-vus',
            exec: 'commentWriteHeavy',
            vus: 100,
            duration: '3m',
            startTime: '28m',
            gracefulStop: '30s',
        },

        // 시나리오 6: 읽기/쓰기 복합 부하
        mixed_feed_workload: {
            executor: 'ramping-vus',
            exec: 'mixedFeedWorkload',
            startVUs: 0,
            stages: [
                { duration: '2m', target: 200 },
                { duration: '3m', target: 400 },
                { duration: '1m', target: 200 },
                { duration: '1m', target: 0 },
            ],
            startTime: '32m',
            gracefulRampDown: '30s',
        },
    },

    thresholds: {
        feed_list_duration: ['p(95)<400'],
        like_toggle_duration: ['p(95)<100'],
        popular_feed_duration: ['p(95)<800'],
        personal_feed_duration: ['p(95)<500'],
        feed_n_plus_one_detected: ['rate<0.1'],
        http_req_duration: ['p(95)<1000'],
        http_req_failed: ['rate<0.05'],
        errors: ['rate<0.05'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('feed_test_users', function () {
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

const clubIds = new SharedArray('feed_club_ids', function () {
    const ids = [];
    for (let i = 1; i <= 10000; i++) {
        ids.push(i);
    }
    return ids;
});

const feedIds = new SharedArray('feed_feed_ids', function () {
    const ids = [];
    for (let i = 1; i <= 100000; i++) {
        ids.push(i);
    }
    return ids;
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

function getRandomFeedId() {
    return feedIds[Math.floor(Math.random() * feedIds.length)];
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
// 시나리오 1: Feed N+1 쿼리 테스트
// 페이지 크기 5/10/20/50 순차 요청 → 50 items 응답시간이 5 items의 5배 초과 시 N+1 확인
// ============================================
export function feedNPlusOneTest() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const clubId = getRandomClubId();

    group('Feed N+1 Query Detection', () => {
        const sizes = [5, 10, 20, 50];
        const durations = {};

        for (const size of sizes) {
            const res = http.get(
                `${BASE_URL}/clubs/${clubId}/feeds?page=0&size=${size}`,
                { headers }
            );

            check(res, {
                [`feed list size=${size}: status 200`]: (r) => r.status === 200,
            });

            durations[size] = res.timings.duration;
            feedListDuration.add(res.timings.duration);
            sleep(0.5);
        }

        // N+1 감지: size=50 응답시간이 size=5의 5배 초과 시
        if (durations[5] > 0 && durations[50] > durations[5] * 5) {
            feedNPlusOneDetected.add(1);
            if (__ITER % 20 === 0) {
                console.warn(`[N+1 Detected] size=5: ${durations[5].toFixed(0)}ms, size=50: ${durations[50].toFixed(0)}ms, ratio: ${(durations[50] / durations[5]).toFixed(1)}x`);
            }
        } else {
            feedNPlusOneDetected.add(0);
        }
    });

    sleep(1);
}

// ============================================
// 시나리오 2: 좋아요 토글 경합 (Redis Lua XADD 스트림)
// ============================================
export function likeToggleContention() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const clubId = getRandomClubId();
    const feedId = getRandomFeedId();

    const res = http.put(
        `${BASE_URL}/clubs/${clubId}/feeds/${feedId}/likes`,
        null,
        { headers }
    );

    check(res, {
        'like toggle: status 200 or 404': (r) => r.status === 200 || r.status === 404,
    });

    likeToggleDuration.add(res.timings.duration);
    errorRate.add(res.status !== 200 && res.status !== 404);
}

// ============================================
// 시나리오 3: 인기 피드 스트레스 (LOG+TIMESTAMPDIFF ORDER BY)
// ============================================
export function popularFeedStress() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Popular Feed Stress', () => {
        const pages = [0, 1, 2];
        for (const page of pages) {
            const res = http.get(
                `${BASE_URL}/feeds/popular?page=${page}&size=20`,
                { headers }
            );

            check(res, {
                [`popular feed page=${page}: status 200`]: (r) => r.status === 200,
            });

            popularFeedDuration.add(res.timings.duration);

            if (res.timings.duration > 800) {
                if (__ITER % 50 === 0) {
                    console.warn(`[Popular Feed Slow] page=${page}, duration=${res.timings.duration.toFixed(0)}ms`);
                }
            }

            sleep(0.3);
        }
    });

    sleep(1);
}

// ============================================
// 시나리오 4: 개인화 피드 대량 조회 (resolveAccessibleClubIds 3회 DB 왕복)
// ============================================
export function personalFeedBulk() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Personal Feed Bulk', () => {
        // 개인화 피드 조회
        const res = http.get(
            `${BASE_URL}/feeds?page=0&size=20`,
            { headers }
        );

        validateResponse(res, 200, 'personal_feed');
        personalFeedDuration.add(res.timings.duration);
        sleep(1);

        // 두 번째 페이지
        const res2 = http.get(
            `${BASE_URL}/feeds?page=1&size=20`,
            { headers }
        );

        validateResponse(res2, 200, 'personal_feed_page2');
        personalFeedDuration.add(res2.timings.duration);
        sleep(1);

        // 피드 상세 조회
        const clubId = getRandomClubId();
        const feedId = getRandomFeedId();
        const res3 = http.get(
            `${BASE_URL}/clubs/${clubId}/feeds/${feedId}`,
            { headers }
        );

        check(res3, {
            'feed detail: status 200 or 404': (r) => r.status === 200 || r.status === 404,
        });
        feedDetailDuration.add(res3.timings.duration);
    });

    sleep(1);
}

// ============================================
// 시나리오 5: 댓글 쓰기 집중 (CascadeType.ALL orphanRemoval)
// ============================================
export function commentWriteHeavy() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const clubId = getRandomClubId();
    const feedId = getRandomFeedId();

    group('Comment Write Heavy', () => {
        // 댓글 작성
        const commentPayload = JSON.stringify({
            content: `Load test comment from VU ${__VU} iter ${__ITER} at ${Date.now()}`,
        });

        const res = http.post(
            `${BASE_URL}/clubs/${clubId}/feeds/${feedId}/comments`,
            commentPayload,
            { headers }
        );

        check(res, {
            'comment create: status 200 or 201 or 404': (r) =>
                r.status === 200 || r.status === 201 || r.status === 404,
        });
        commentWriteDuration.add(res.timings.duration);
        sleep(0.5);

        // 댓글 목록 조회
        const res2 = http.get(
            `${BASE_URL}/feeds/${feedId}/comments?page=0&size=20`,
            { headers }
        );

        check(res2, {
            'comment list: status 200 or 404': (r) => r.status === 200 || r.status === 404,
        });
        commentListDuration.add(res2.timings.duration);
    });

    sleep(1);
}

// ============================================
// 시나리오 6: 읽기/쓰기 복합 부하
// ============================================
export function mixedFeedWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const clubId = getRandomClubId();
    const feedId = getRandomFeedId();

    group('Mixed Feed Workload', () => {
        const action = Math.random();

        if (action < 0.3) {
            // 30%: 피드 목록 조회
            const res = http.get(
                `${BASE_URL}/clubs/${clubId}/feeds?page=0&size=20`,
                { headers }
            );
            validateResponse(res, 200, 'mixed_feed_list');
            feedListDuration.add(res.timings.duration);
        } else if (action < 0.5) {
            // 20%: 인기 피드
            const res = http.get(
                `${BASE_URL}/feeds/popular?page=0&size=20`,
                { headers }
            );
            validateResponse(res, 200, 'mixed_popular');
            popularFeedDuration.add(res.timings.duration);
        } else if (action < 0.7) {
            // 20%: 좋아요 토글
            const res = http.put(
                `${BASE_URL}/clubs/${clubId}/feeds/${feedId}/likes`,
                null,
                { headers }
            );
            check(res, {
                'mixed like: status 200 or 404': (r) => r.status === 200 || r.status === 404,
            });
            likeToggleDuration.add(res.timings.duration);
        } else if (action < 0.85) {
            // 15%: 개인화 피드
            const res = http.get(
                `${BASE_URL}/feeds?page=0&size=20`,
                { headers }
            );
            validateResponse(res, 200, 'mixed_personal');
            personalFeedDuration.add(res.timings.duration);
        } else {
            // 15%: 댓글 작성
            const payload = JSON.stringify({
                content: `Mixed workload comment ${Date.now()}`,
            });
            const res = http.post(
                `${BASE_URL}/clubs/${clubId}/feeds/${feedId}/comments`,
                payload,
                { headers }
            );
            check(res, {
                'mixed comment: status 200 or 201 or 404': (r) =>
                    r.status === 200 || r.status === 201 || r.status === 404,
            });
            commentWriteDuration.add(res.timings.duration);
        }
    });

    sleep(0.5);
}

// ============================================
// 테스트 라이프사이클
// ============================================
export function setup() {
    console.log('=== Feed Domain Load Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log(`Test Users: ${testUsers.length}`);
    console.log('');
    console.log('Testing 6 scenarios:');
    console.log('1. Feed N+1 Query Detection (0-5m, 200 VU)');
    console.log('2. Like Toggle Contention (6-12m, 100->2000/s)');
    console.log('3. Popular Feed Stress (13-21m, 0->500 VU)');
    console.log('4. Personal Feed Bulk (22-27m, 150 VU)');
    console.log('5. Comment Write Heavy (28-31m, 100 VU)');
    console.log('6. Mixed Feed Workload (32-39m, 0->400 VU)');
    console.log('');
    console.log('Total Duration: ~39 minutes');
    console.log('=====================================');
}

export function teardown(data) {
    console.log('');
    console.log('=== Feed Domain Load Test Completed ===');
    console.log('Review metrics: feed_list_duration, like_toggle_duration,');
    console.log('popular_feed_duration, feed_n_plus_one_detected');
    console.log('========================================');
}
