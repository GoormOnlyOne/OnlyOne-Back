import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// Feed 도메인 병목 탐지 테스트 (축약 ~7분)
// 목적: N+1, popular 정렬, like 경합, personal feed 병목
// ============================================

const errorRate = new Rate('errors');
const feedListDuration = new Trend('feed_list_duration', true);
const feedDetailDuration = new Trend('feed_detail_duration', true);
const likeToggleDuration = new Trend('like_toggle_duration', true);
const popularFeedDuration = new Trend('popular_feed_duration', true);
const personalFeedDuration = new Trend('personal_feed_duration', true);
const commentWriteDuration = new Trend('comment_write_duration', true);
const feedNPlusOne = new Rate('feed_n_plus_one');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // Phase 1: Feed List N+1 탐지 + Detail — 200 VU, 2분
        feed_read_test: {
            executor: 'ramping-vus',
            exec: 'feedReadTest',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 200 },
                { duration: '1m', target: 200 },
                { duration: '30s', target: 0 },
            ],
            startTime: '0s',
            gracefulRampDown: '10s',
        },

        // Phase 2: Like 토글 + Popular + Personal — 300 VU, 2.5분
        like_popular_test: {
            executor: 'ramping-vus',
            exec: 'likePopularTest',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 300 },
                { duration: '1m30s', target: 300 },
                { duration: '30s', target: 0 },
            ],
            startTime: '2m30s',
            gracefulRampDown: '10s',
        },

        // Phase 3: 고부하 혼합 — 400 VU, 2분
        high_load_mixed: {
            executor: 'ramping-vus',
            exec: 'highLoadMixed',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 400 },
                { duration: '1m', target: 400 },
                { duration: '30s', target: 0 },
            ],
            startTime: '5m30s',
            gracefulRampDown: '10s',
        },
    },

    thresholds: {
        feed_list_duration: ['p(95)<500', 'p(50)<200'],
        feed_detail_duration: ['p(95)<300'],
        like_toggle_duration: ['p(95)<200'],
        popular_feed_duration: ['p(95)<800'],
        personal_feed_duration: ['p(95)<500'],
        http_req_failed: ['rate<0.05'],
        errors: ['rate<0.1'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('feed_users', function () {
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
    for (let i = 1; i <= 10000; i++) ids.push(i);
    return ids;
});

const feedIds = new SharedArray('feed_ids', function () {
    const ids = [];
    for (let i = 1; i <= 100000; i++) ids.push(i);
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
function randomFeed() { return feedIds[Math.floor(Math.random() * feedIds.length)]; }

// ============================================
// Phase 1: Feed List (N+1 탐지) + Detail
// ============================================
export function feedReadTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const opts = headers(token);
    const clubId = randomClub();

    group('feed_read', () => {
        const action = Math.random();

        if (action < 0.5) {
            // 50%: Feed List (size=20) — N+1 탐지
            const res = http.get(
                `${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&size=20`,
                opts
            );
            check(res, { 'feed list: ok': (r) => r.status === 200 });
            feedListDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);

            // N+1 감지: 20개 아이템에 200ms 이상이면 의심
            if (res.timings.duration > 200) {
                feedNPlusOne.add(1);
                if (__ITER % 50 === 0) {
                    console.warn(`[N+1?] club=${clubId}, list(20): ${res.timings.duration.toFixed(0)}ms`);
                }
            } else {
                feedNPlusOne.add(0);
            }
        } else if (action < 0.80) {
            // 30%: Feed Detail
            const feedId = randomFeed();
            const res = http.get(
                `${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}`,
                opts
            );
            check(res, { 'feed detail: ok': (r) => r.status === 200 || r.status === 404 });
            if (res.status === 200) {
                feedDetailDuration.add(res.timings.duration);
            }
        } else {
            // 20%: Feed List (size=50) — N+1 스케일링 확인
            const res = http.get(
                `${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&size=50`,
                opts
            );
            check(res, { 'feed list 50: ok': (r) => r.status === 200 });
            feedListDuration.add(res.timings.duration);

            if (res.timings.duration > 500 && __ITER % 30 === 0) {
                console.warn(`[N+1.Large] club=${clubId}, list(50): ${res.timings.duration.toFixed(0)}ms`);
            }
        }
    });

    sleep(0.3);
}

// ============================================
// Phase 2: Like 토글 + Popular + Personal
// ============================================
export function likePopularTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const opts = headers(token);
    const action = Math.random();

    group('like_popular', () => {
        if (action < 0.40) {
            // 40%: Like 토글
            const clubId = randomClub();
            const feedId = randomFeed();
            const res = http.put(
                `${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`,
                null, opts
            );
            check(res, { 'like: ok': (r) => r.status === 200 || r.status === 404 });
            likeToggleDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);

            if (res.timings.duration > 200 && __ITER % 100 === 0) {
                console.warn(`[Like.Slow] feed=${feedId}, ${res.timings.duration.toFixed(0)}ms`);
            }
        } else if (action < 0.65) {
            // 25%: Popular Feed
            const page = Math.floor(Math.random() * 3);
            const res = http.get(
                `${BASE_URL}/api/v1/feeds/popular?page=${page}&size=20`,
                opts
            );
            check(res, { 'popular: ok': (r) => r.status === 200 });
            popularFeedDuration.add(res.timings.duration);

            if (res.timings.duration > 800 && __ITER % 30 === 0) {
                console.warn(`[Popular.Slow] page=${page}, ${res.timings.duration.toFixed(0)}ms`);
            }
        } else {
            // 35%: Personal Feed (resolveAccessibleClubIds 병목)
            const page = Math.floor(Math.random() * 3);
            const res = http.get(
                `${BASE_URL}/api/v1/feeds?page=${page}&size=20`,
                opts
            );
            check(res, { 'personal: ok': (r) => r.status === 200 });
            personalFeedDuration.add(res.timings.duration);

            if (res.timings.duration > 500 && __ITER % 50 === 0) {
                console.warn(`[Personal.Slow] page=${page}, ${res.timings.duration.toFixed(0)}ms`);
            }
        }
    });

    sleep(0.2);
}

// ============================================
// Phase 3: 고부하 혼합 (400 VU)
// 30% list, 25% like, 20% popular, 15% personal, 10% comment
// ============================================
export function highLoadMixed() {
    const user = randomUser();
    const token = generateJWT(user);
    const opts = headers(token);
    const clubId = randomClub();
    const feedId = randomFeed();
    const action = Math.random();

    group('high_load', () => {
        if (action < 0.30) {
            const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&size=20`, opts);
            feedListDuration.add(res.timings.duration);
        } else if (action < 0.55) {
            const res = http.put(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`, null, opts);
            likeToggleDuration.add(res.timings.duration);
        } else if (action < 0.75) {
            const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&size=20`, opts);
            popularFeedDuration.add(res.timings.duration);
        } else if (action < 0.90) {
            const res = http.get(`${BASE_URL}/api/v1/feeds?page=0&size=20`, opts);
            personalFeedDuration.add(res.timings.duration);
        } else {
            const payload = JSON.stringify({ content: `BotTest comment ${__VU}-${Date.now()}` });
            const res = http.post(
                `${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/comments`,
                payload, opts
            );
            commentWriteDuration.add(res.timings.duration);
        }
    });

    sleep(0.15);
}

// ============================================
// Lifecycle
// ============================================
export function setup() {
    console.log('=== Feed Bottleneck Detection Test (~7min) ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('');
    console.log('Phase 1 (0-2m):     Feed List+Detail (N+1 탐지) — 0→200 VU');
    console.log('Phase 2 (2.5-5m):   Like+Popular+Personal — 0→300 VU');
    console.log('Phase 3 (5.5-7.5m): High Load Mixed — 0→400 VU');
    console.log('');
    console.log('Thresholds: list p95<500ms, like p95<200ms, popular p95<800ms');
    console.log('==============================================');

    const user = testUsers[0];
    const token = generateJWT(user);
    const smokeRes = http.get(`${BASE_URL}/api/v1/clubs/1/feeds?page=0&size=5`, headers(token));
    console.log(`Smoke test: status=${smokeRes.status}, body_len=${smokeRes.body.length}`);
    if (smokeRes.status !== 200) {
        console.error('Smoke test FAILED! Check if test data exists.');
    }
}

export function teardown() {
    console.log('');
    console.log('=== Feed Bottleneck Test Complete ===');
    console.log('Key metrics to review:');
    console.log('  feed_list_duration — N+1 on images/likes/comments?');
    console.log('  popular_feed_duration — LOG+TIMESTAMPDIFF full scan?');
    console.log('  personal_feed_duration — resolveAccessibleClubIds 3 DB trips?');
    console.log('  like_toggle_duration — Redis Lua + DB overhead?');
    console.log('  feed_n_plus_one — N+1 detection rate');
    console.log('=====================================');
}
