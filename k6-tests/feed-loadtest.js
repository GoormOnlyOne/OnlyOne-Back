// =============================================================
// 피드 도메인 통합 부하 테스트
// =============================================================
// 실행: MSYS_NO_PATHCONV=1 docker run --rm -i --network=host \
//   -v "$(pwd)/k6-tests:/scripts" grafana/k6 run /scripts/feed-loadtest.js
//
// 사전 준비:
//   1. seed-postgres.sql 실행 (1000 유저, 클럽/피드/댓글/좋아요 시드)
//   2. seed-mongo-feed.js 실행 (MongoDB 피드 도큐먼트 동기화)
//
// 흡수된 테스트:
//   - feed-bottleneck-test.js    → Phase 2 (Baseline 임계값)
//   - feed-comprehensive-test.js → Phase 3~6, 8~10
//   - feed-concurrency-test.js   → Phase 7, 9
//   - feed-like-test.js          → Phase 7
//
// Phase 구성 (~20분, 최대 1000 VUs):
// ┌────────┬────────────────────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                                │ VU   │ 시간  │
// ├────────┼────────────────────────────────────────┼──────┼───────┤
// │ 1      │ Warmup                                 │ 50   │ 30s   │
// │ 2      │ Baseline — 전 API 혼합                  │ 300  │ 2m    │
// │ 3      │ 클럽 피드 목록 조회                      │ 350  │ 2m    │
// │ 4      │ 피드 상세 조회 — 고댓글 피드 집중         │ 500  │ 2m    │
// │ 5      │ 개인 피드 IN절 병목                      │ 500  │ 2m    │
// │ 6      │ 댓글 목록 조회                           │ 350  │ 1.5m  │
// │ 7      │ 좋아요 토글 경합 집중                    │ 700  │ 1.5m  │
// │ 8      │ 쓰기 혼합 — 피드생성+댓글생성+리피드      │ 350  │ 2m    │
// │ 9      │ 극한 혼합 — 읽기70%+쓰기30%             │ 1000 │ 2m    │
// │ 10     │ Soak                                   │ 300  │ 3m    │
// │ 11     │ Cooldown                               │ 5    │ 30s   │
// └────────┴────────────────────────────────────────┴──────┴───────┘
//
// 인프라 튜닝 탐지 포인트:
//   - HikariCP: Phase 5,9에서 IN-clause 쿼리 커넥션 풀 고갈 여부
//   - InnoDB:   Phase 4에서 comment full load, Phase 7에서 like X-lock contention
//   - Redis:    Phase 7에서 Lua script throughput under 700VU
//   - Tomcat:   Phase 9에서 1000VU thread exhaustion
//   - JVM:      Phase 10에서 GC pause with ConcurrentHashMap cache
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, makeUser, getUserClubs, getRandomUserClub, MIN_CLUB } from './lib/common.js';
import { THRESHOLDS } from './lib/bottleneck.js';

// ============================================
// 테스트 데이터
// ============================================
const VALID_USER_COUNT = parseInt(__ENV.USER_COUNT || '100000');
const MAIN_CLUB_IDS = [64, 159, 381, 501, 747];
const SUB_CLUB_IDS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
const ALL_CLUB_IDS = [...MAIN_CLUB_IDS, ...SUB_CLUB_IDS];

// 댓글 테스트용 피드 (시드 데이터 기준 피드당 평균 ~3건)
// feedId = clubId + n * 50000 (n=0,1,2)
const HIGH_COMMENT_FEEDS = [
    { feedId: 64, clubId: 64, comments: 3 },
    { feedId: 50064, clubId: 64, comments: 3 },
    { feedId: 100064, clubId: 64, comments: 3 },
    { feedId: 159, clubId: 159, comments: 3 },
    { feedId: 100159, clubId: 159, comments: 3 },
    { feedId: 50159, clubId: 159, comments: 3 },
];

// 좋아요 핫스팟 피드 (경합 테스트용)
// feedId = clubId + n * 50000 (n=0,1,2)
const HOT_FEEDS = [
    { feedId: 64, clubId: 64 },
    { feedId: 159, clubId: 159 },
    { feedId: 381, clubId: 381 },
    { feedId: 501, clubId: 501 },
    { feedId: 747, clubId: 747 },
    { feedId: 50064, clubId: 64 },
    { feedId: 100064, clubId: 64 },
    { feedId: 50159, clubId: 159 },
];

// feedId = clubId + N * 50000 (N=0~19, 시드 데이터 기준)
function getRandomFeedForClub(clubId) {
    const offset = Math.floor(Math.random() * 20);
    return clubId + offset * 50000;
}

// ============================================
// 커스텀 메트릭 — 엔드포인트별
// ============================================
const feedClubListDur    = new Trend('feed_club_list_duration', true);
const feedDetailDur      = new Trend('feed_detail_duration', true);
const feedPersonalDur    = new Trend('feed_personal_duration', true);
const feedPopularDur     = new Trend('feed_popular_duration', true);
const feedCommentListDur = new Trend('feed_comment_list_duration', true);
const feedLikeDur        = new Trend('feed_like_duration', true);
const feedCreateDur      = new Trend('feed_create_duration', true);
const feedCommentCreateDur = new Trend('feed_comment_create_duration', true);
const feedRefeedDur      = new Trend('feed_refeed_duration', true);

// ============================================
// 커스텀 메트릭 — Phase별 성공률
// ============================================
const phase2Success  = new Rate('feed_phase2_success');
const phase3Success  = new Rate('feed_phase3_success');
const phase4Success  = new Rate('feed_phase4_success');
const phase5Success  = new Rate('feed_phase5_success');
const phase6Success  = new Rate('feed_phase6_success');
const phase7Success  = new Rate('feed_phase7_success');
const phase8Success  = new Rate('feed_phase8_success');
const phase9Success  = new Rate('feed_phase9_success');
const phase10Success = new Rate('feed_phase10_success');

const totalErrors = new Counter('feed_total_errors');

// ============================================
// 시나리오 설정
// ============================================
export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: 50,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: '1_warmup' },
        },

        // Phase 2: Baseline — 전 API 혼합 (bottleneck 임계값 포함)
        baseline: {
            executor: 'constant-vus',
            vus: 300,
            duration: '120s',
            startTime: '35s',
            exec: 'baseline',
            tags: { phase: '2_baseline' },
        },

        // Phase 3: 클럽 피드 목록 조회 — 350 VUs
        club_list: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 350 },
                { duration: '80s', target: 350 },
                { duration: '20s', target: 0 },
            ],
            startTime: '160s',
            exec: 'clubFeedList',
            tags: { phase: '3_club_list' },
        },

        // Phase 4: 피드 상세 조회 — 고댓글 피드 집중 500 VUs
        feed_detail: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 500 },
                { duration: '80s', target: 500 },
                { duration: '20s', target: 0 },
            ],
            startTime: '285s',
            exec: 'feedDetail',
            tags: { phase: '4_feed_detail' },
        },

        // Phase 5: 개인 피드 IN절 병목 — 500 VUs
        personal_feed: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 500 },
                { duration: '80s', target: 500 },
                { duration: '20s', target: 0 },
            ],
            startTime: '410s',
            exec: 'personalFeed',
            tags: { phase: '5_personal_feed' },
        },

        // Phase 6: 댓글 목록 조회 — 350 VUs
        comment_list: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 350 },
                { duration: '60s', target: 350 },
                { duration: '15s', target: 0 },
            ],
            startTime: '535s',
            exec: 'commentList',
            tags: { phase: '6_comment_list' },
        },

        // Phase 7: 좋아요 토글 경합 집중 — 700 VUs
        like_toggle: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 700 },
                { duration: '60s', target: 700 },
                { duration: '15s', target: 0 },
            ],
            startTime: '630s',
            exec: 'likeToggle',
            tags: { phase: '7_like_toggle' },
        },

        // Phase 8: 쓰기 혼합 — 피드생성+댓글생성+리피드 350 VUs
        write_mix: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 350 },
                { duration: '80s', target: 350 },
                { duration: '20s', target: 0 },
            ],
            startTime: '725s',
            exec: 'writeMix',
            tags: { phase: '8_write_mix' },
        },

        // Phase 9: 극한 혼합 — 읽기70%+쓰기30% 1000 VUs
        extreme_mix: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '20s', target: 1000 },
                { duration: '80s', target: 1000 },
                { duration: '20s', target: 0 },
            ],
            startTime: '850s',
            exec: 'extremeMix',
            tags: { phase: '9_extreme' },
        },

        // Phase 10: Soak — 중간 부하 장시간
        soak: {
            executor: 'constant-vus',
            vus: 300,
            duration: '180s',
            startTime: '975s',
            exec: 'soakTest',
            tags: { phase: '10_soak' },
        },

        // Phase 11: Cooldown
        cooldown: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            startTime: '1160s',
            exec: 'warmup',
            tags: { phase: '11_cooldown' },
        },
    },

    thresholds: {
        // ── 글로벌 ──
        http_req_failed: ['rate<0.05'],

        // ── 엔드포인트별 (bottleneck 임계값) ──
        'feed_club_list_duration':    [`p(95)<${THRESHOLDS.NORMAL}`],     // 500ms
        'feed_detail_duration':       [`p(95)<${THRESHOLDS.SLOW}`],       // 1000ms
        'feed_personal_duration':     [`p(95)<${THRESHOLDS.SLOW}`],       // 1000ms
        'feed_popular_duration':      [`p(95)<${THRESHOLDS.SLOW}`],       // 1000ms
        'feed_comment_list_duration': [`p(95)<${THRESHOLDS.SLOW}`],       // 1000ms
        'feed_like_duration':         [`p(95)<${THRESHOLDS.FAST}`],       // 200ms
        'feed_create_duration':       [`p(95)<${THRESHOLDS.NORMAL}`],     // 500ms
        'feed_comment_create_duration': [`p(95)<${THRESHOLDS.NORMAL}`],   // 500ms
        'feed_refeed_duration':       [`p(95)<${THRESHOLDS.NORMAL}`],     // 500ms

        // ── Phase별 성공률 ──
        'feed_phase2_success':  ['rate>0.98'],
        'feed_phase3_success':  ['rate>0.98'],
        'feed_phase4_success':  ['rate>0.95'],
        'feed_phase5_success':  ['rate>0.95'],
        'feed_phase6_success':  ['rate>0.95'],
        'feed_phase7_success':  ['rate>0.95'],
        'feed_phase8_success':  ['rate>0.90'],
        'feed_phase9_success':  ['rate>0.85'],
        'feed_phase10_success': ['rate>0.98'],
    },
};

// ============================================
// 유저 유틸 (makeUser는 common.js에서 import)
// ============================================
function randomUser() {
    const userId = Math.floor(Math.random() * VALID_USER_COUNT) + 1;
    return makeUser(userId);
}

function vuUser(vuId) {
    const userId = ((vuId - 1) % VALID_USER_COUNT) + 1;
    return makeUser(userId);
}

function randomClub() {
    return ALL_CLUB_IDS[Math.floor(Math.random() * ALL_CLUB_IDS.length)];
}

function randomMainClub() {
    return MAIN_CLUB_IDS[Math.floor(Math.random() * MAIN_CLUB_IDS.length)];
}

// ============================================
// Phase 1 & 11: Warmup / Cooldown
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    const clubId = randomMainClub();

    http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=5`, {
        headers: headers(token), tags: { name: 'warmup_club_list' },
    });
    http.get(`${BASE_URL}/api/v1/feeds?page=0&limit=5`, {
        headers: headers(token), tags: { name: 'warmup_personal' },
    });
    sleep(0.3);
}

// ============================================
// Phase 2: Baseline — 전 API 혼합 + bottleneck 임계값
// ============================================
export function baseline() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const clubId = randomClub();

    // 클럽 피드 목록
    const clubListRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=20`, {
        headers: hdrs, tags: { name: 'bl_club_list' },
    });
    feedClubListDur.add(clubListRes.timings.duration);
    phase2Success.add(clubListRes.status === 200);
    if (clubListRes.status !== 200) totalErrors.add(1);
    sleep(0.2);

    // 피드 상세 조회
    const detailClub = randomMainClub();
    const feedId = getRandomFeedForClub(detailClub);
    const detailRes = http.get(`${BASE_URL}/api/v1/clubs/${detailClub}/feeds/${feedId}`, {
        headers: hdrs, tags: { name: 'bl_detail' },
    });
    feedDetailDur.add(detailRes.timings.duration);
    phase2Success.add(detailRes.status === 200 || detailRes.status === 404);
    sleep(0.2);

    // 개인 피드
    const personalRes = http.get(`${BASE_URL}/api/v1/feeds?page=0&limit=20`, {
        headers: hdrs, tags: { name: 'bl_personal' },
    });
    feedPersonalDur.add(personalRes.timings.duration);
    phase2Success.add(personalRes.status === 200);
    sleep(0.2);

    // 인기 피드
    const popularRes = http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&limit=20`, {
        headers: hdrs, tags: { name: 'bl_popular' },
    });
    feedPopularDur.add(popularRes.timings.duration);
    phase2Success.add(popularRes.status === 200);
    sleep(0.2);

    // 좋아요 토글 (50%)
    if (Math.random() < 0.5) {
        const likeClubId = randomMainClub();
        const likeFeedId = getRandomFeedForClub(likeClubId);
        const likeRes = http.put(`${BASE_URL}/api/v1/clubs/${likeClubId}/feeds/${likeFeedId}/likes`, null, {
            headers: hdrs, tags: { name: 'bl_like' },
        });
        feedLikeDur.add(likeRes.timings.duration);
        phase2Success.add(likeRes.status === 200 || likeRes.status === 404);
    }
    sleep(0.2);

    // 댓글 목록 (30%)
    if (Math.random() < 0.3) {
        const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
        const commentRes = http.get(`${BASE_URL}/api/v1/feeds/${hc.feedId}/comments?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'bl_comments' },
        });
        feedCommentListDur.add(commentRes.timings.duration);
        phase2Success.add(commentRes.status === 200);
    }

    sleep(0.3);
}

// ============================================
// Phase 3: 클럽 피드 목록 조회 — 350 VUs
// ============================================
export function clubFeedList() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const clubId = randomClub();
    const page = Math.floor(Math.random() * 3);

    const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=${page}&limit=20`, {
        headers: hdrs, tags: { name: 'cl_club_list' },
    });
    feedClubListDur.add(res.timings.duration);
    phase3Success.add(res.status === 200);
    if (res.status !== 200) totalErrors.add(1);

    // 2페이지 추가 조회 (50%)
    if (res.status === 200 && Math.random() < 0.5) {
        const res2 = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=${page + 1}&limit=20`, {
            headers: hdrs, tags: { name: 'cl_club_list_p2' },
        });
        feedClubListDur.add(res2.timings.duration);
        phase3Success.add(res2.status === 200);
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 4: 피드 상세 조회 — 고댓글 피드 집중 500 VUs
// ============================================
export function feedDetail() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 70% 고댓글 피드, 30% 일반 피드
    let clubId, feedId;
    if (Math.random() < 0.7) {
        const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
        clubId = hc.clubId;
        feedId = hc.feedId;
    } else {
        clubId = randomMainClub();
        feedId = getRandomFeedForClub(clubId);
    }

    const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}`, {
        headers: hdrs, tags: { name: 'fd_detail' },
    });
    feedDetailDur.add(res.timings.duration);
    phase4Success.add(res.status === 200 || res.status === 404);
    if (res.status >= 500) totalErrors.add(1);

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 5: 개인 피드 IN절 병목 — 500 VUs
// ============================================
export function personalFeed() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const page = Math.floor(Math.random() * 5);

    const res = http.get(`${BASE_URL}/api/v1/feeds?page=${page}&limit=20`, {
        headers: hdrs, tags: { name: 'pf_personal' },
    });
    feedPersonalDur.add(res.timings.duration);
    phase5Success.add(res.status === 200);
    if (res.status !== 200) totalErrors.add(1);

    // 인기 피드도 병행 조회 (30%)
    if (Math.random() < 0.3) {
        const popRes = http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'pf_popular' },
        });
        feedPopularDur.add(popRes.timings.duration);
        phase5Success.add(popRes.status === 200);
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 6: 댓글 목록 조회 — 350 VUs
// ============================================
export function commentList() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 고댓글 피드 위주 — 깊은 페이지도 테스트
    const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
    const page = Math.floor(Math.random() * 10);

    const res = http.get(`${BASE_URL}/api/v1/feeds/${hc.feedId}/comments?page=${page}&limit=20`, {
        headers: hdrs, tags: { name: 'cm_comment_list' },
    });
    feedCommentListDur.add(res.timings.duration);
    phase6Success.add(res.status === 200);
    if (res.status !== 200) totalErrors.add(1);

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 7: 좋아요 토글 경합 집중 — 700 VUs
// ============================================
export function likeToggle() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 80% 핫피드 집중, 20% 일반 분산
    let clubId, feedId;
    if (Math.random() < 0.8) {
        const hot = HOT_FEEDS[Math.floor(Math.random() * HOT_FEEDS.length)];
        clubId = hot.clubId;
        feedId = hot.feedId;
    } else {
        clubId = randomMainClub();
        feedId = getRandomFeedForClub(clubId);
    }

    const res = http.put(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`, null, {
        headers: hdrs, tags: { name: 'lt_like_toggle' },
    });
    feedLikeDur.add(res.timings.duration);
    phase7Success.add(res.status === 200 || res.status === 404);
    if (res.status >= 500) totalErrors.add(1);

    sleep(0.02 + Math.random() * 0.05);
}

// ============================================
// Phase 8: 쓰기 혼합 — 피드생성+댓글생성+리피드 350 VUs
// ============================================
export function writeMix() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let ok = false;
    let dur = 0;

    if (roll < 0.40) {
        // 40%: 피드 생성 — 유저가 속한 클럽에 작성
        const clubId = getRandomUserClub(user.userId);
        const res = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/feeds`,
            JSON.stringify({
                feedUrls: ['https://example.com/test-image.jpg'],
                content: `k6 load test feed ${Date.now()}`,
            }),
            { headers: hdrs, tags: { name: 'wm_create_feed' } }
        );
        dur = res.timings.duration;
        ok = res.status >= 200 && res.status < 500;
        feedCreateDur.add(dur);
    } else if (roll < 0.80) {
        // 40%: 댓글 생성 — 유저가 속한 클럽의 피드에 작성
        const clubId = getRandomUserClub(user.userId);
        const commentFeedId = getRandomFeedForClub(clubId);
        const res = http.post(
            `${BASE_URL}/api/v1/clubs/${clubId}/feeds/${commentFeedId}/comments`,
            JSON.stringify({ content: `k6 comment ${Date.now()}` }),
            { headers: hdrs, tags: { name: 'wm_create_comment' } }
        );
        dur = res.timings.duration;
        ok = res.status >= 200 && res.status < 500;
        feedCommentCreateDur.add(dur);
    } else {
        // 20%: 리피드 — 유저가 속한 클럽으로 리피드
        const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
        const targetClub = getRandomUserClub(user.userId);
        const res = http.post(
            `${BASE_URL}/api/v1/feeds/${hc.feedId}/${targetClub}`,
            JSON.stringify({ content: `k6 refeed ${Date.now()}` }),
            { headers: hdrs, tags: { name: 'wm_refeed' } }
        );
        dur = res.timings.duration;
        ok = res.status >= 200 && res.status < 500;
        feedRefeedDur.add(dur);
    }

    phase8Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.2 + Math.random() * 0.3);
}

// ============================================
// Phase 9: 극한 혼합 — 읽기70%+쓰기30% 1000 VUs
// ============================================
export function extremeMix() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let ok = false;
    let dur = 0;

    if (roll < 0.20) {
        // 20%: 개인 피드
        const res = http.get(`${BASE_URL}/api/v1/feeds?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'ex_personal' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        feedPersonalDur.add(dur);
    } else if (roll < 0.35) {
        // 15%: 인기 피드
        const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'ex_popular' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        feedPopularDur.add(dur);
    } else if (roll < 0.50) {
        // 15%: 피드 상세
        const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
        const res = http.get(`${BASE_URL}/api/v1/clubs/${hc.clubId}/feeds/${hc.feedId}`, {
            headers: hdrs, tags: { name: 'ex_detail' },
        });
        dur = res.timings.duration;
        ok = res.status === 200 || res.status === 404;
        feedDetailDur.add(dur);
    } else if (roll < 0.60) {
        // 10%: 클럽 피드 목록
        const clubId = randomClub();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'ex_club_list' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        feedClubListDur.add(dur);
    } else if (roll < 0.70) {
        // 10%: 댓글 목록
        const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
        const res = http.get(`${BASE_URL}/api/v1/feeds/${hc.feedId}/comments?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'ex_comments' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        feedCommentListDur.add(dur);
    } else if (roll < 0.82) {
        // 12%: 좋아요 토글
        const clubId = randomMainClub();
        const feedId = getRandomFeedForClub(clubId);
        const res = http.put(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`, null, {
            headers: hdrs, tags: { name: 'ex_like' },
        });
        dur = res.timings.duration;
        ok = res.status === 200 || res.status === 404;
        feedLikeDur.add(dur);
    } else if (roll < 0.92) {
        // 10%: 댓글 작성 — 유저가 속한 클럽의 피드에 작성
        const commentClub = getRandomUserClub(user.userId);
        const commentFeedId = getRandomFeedForClub(commentClub);
        const res = http.post(
            `${BASE_URL}/api/v1/clubs/${commentClub}/feeds/${commentFeedId}/comments`,
            JSON.stringify({ content: `extreme ${Date.now()}` }),
            { headers: hdrs, tags: { name: 'ex_write_comment' } }
        );
        dur = res.timings.duration;
        ok = res.status >= 200 && res.status < 500;
        feedCommentCreateDur.add(dur);
    } else {
        // 8%: 피드 생성 — 유저가 속한 클럽에 작성
        const writeClub = getRandomUserClub(user.userId);
        const res = http.post(`${BASE_URL}/api/v1/clubs/${writeClub}/feeds`,
            JSON.stringify({
                feedUrls: ['https://example.com/img.jpg'],
                content: `extreme ${Date.now()}`,
            }),
            { headers: hdrs, tags: { name: 'ex_write_feed' } }
        );
        dur = res.timings.duration;
        ok = res.status >= 200 && res.status < 500;
        feedCreateDur.add(dur);
    }

    phase9Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.02 + Math.random() * 0.05);
}

// ============================================
// Phase 10: Soak — 300 VUs 3분 안정성
// ============================================
export function soakTest() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let ok = false;
    let dur = 0;

    if (roll < 0.25) {
        // 25%: 클럽 피드 목록
        const clubId = randomClub();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'soak_club_list' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        feedClubListDur.add(dur);
    } else if (roll < 0.45) {
        // 20%: 개인 피드
        const res = http.get(`${BASE_URL}/api/v1/feeds?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'soak_personal' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        feedPersonalDur.add(dur);
    } else if (roll < 0.60) {
        // 15%: 피드 상세
        const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
        const res = http.get(`${BASE_URL}/api/v1/clubs/${hc.clubId}/feeds/${hc.feedId}`, {
            headers: hdrs, tags: { name: 'soak_detail' },
        });
        dur = res.timings.duration;
        ok = res.status === 200 || res.status === 404;
        feedDetailDur.add(dur);
    } else if (roll < 0.75) {
        // 15%: 좋아요 토글
        const clubId = randomMainClub();
        const feedId = getRandomFeedForClub(clubId);
        const res = http.put(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`, null, {
            headers: hdrs, tags: { name: 'soak_like' },
        });
        dur = res.timings.duration;
        ok = res.status === 200 || res.status === 404;
        feedLikeDur.add(dur);
    } else if (roll < 0.85) {
        // 10%: 댓글 목록
        const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
        const res = http.get(`${BASE_URL}/api/v1/feeds/${hc.feedId}/comments?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'soak_comments' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        feedCommentListDur.add(dur);
    } else if (roll < 0.93) {
        // 8%: 인기 피드
        const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'soak_popular' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        feedPopularDur.add(dur);
    } else {
        // 7%: 댓글 작성 — 유저가 속한 클럽의 피드에 작성
        const commentClub = getRandomUserClub(user.userId);
        const commentFeedId = getRandomFeedForClub(commentClub);
        const res = http.post(
            `${BASE_URL}/api/v1/clubs/${commentClub}/feeds/${commentFeedId}/comments`,
            JSON.stringify({ content: `soak ${Date.now()}` }),
            { headers: hdrs, tags: { name: 'soak_write_comment' } }
        );
        dur = res.timings.duration;
        ok = res.status >= 200 && res.status < 500;
        feedCommentCreateDur.add(dur);
    }

    phase10Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.05 + Math.random() * 0.15);
}

// ============================================
// default function (fallback)
// ============================================
export default function () {
    warmup();
}
