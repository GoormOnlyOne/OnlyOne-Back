// =============================================================
// 피드 도메인 통합 부하 테스트
// =============================================================
// 실행: MSYS_NO_PATHCONV=1 docker run --rm -i --network=host \
//   -v "$(pwd)/k6-tests:/scripts" grafana/k6 run /scripts/feed-loadtest.js
//
// 사전 준비:
//   1. seed-all-domains.sql 실행 (100K 유저, 50K 클럽, 10M 피드 시드)
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
import { generateJWT, headers, BASE_URL, makeUser, getUserClubs, getRandomUserClub, MIN_CLUB, vu, dur, startAfter, TOTAL_USERS } from '../lib/common.js';
import { THRESHOLDS } from '../lib/bottleneck.js';

// ============================================
// 테스트 데이터
// ============================================
const VALID_USER_COUNT = parseInt(__ENV.USER_COUNT || '') || TOTAL_USERS;

// 클럽 ID는 환경변수 또는 common.js의 MIN_CLUB 기반으로 동적 생성
// MAIN_CLUB_IDS: 핫스팟 클럽 (좋아요/댓글 경합 집중)
// ALL_CLUB_IDS: setup()에서 feedId를 수집할 클럽 범위 (50개 — 넓은 커버리지)
const MAIN_CLUB_IDS = __ENV.MAIN_CLUB_IDS
    ? __ENV.MAIN_CLUB_IDS.split(',').map(Number)
    : [MIN_CLUB, MIN_CLUB + 1, MIN_CLUB + 2, MIN_CLUB + 3, MIN_CLUB + 4];
const SUB_CLUB_IDS = Array.from({ length: 45 }, (_, i) => MIN_CLUB + 5 + i * 1111);
const ALL_CLUB_IDS = [...MAIN_CLUB_IDS, ...SUB_CLUB_IDS];

// ── 피드 ID는 setup()에서 API로 실제 ID를 수집 (아래 참조) ──
// setup()이 반환한 data.feedMap[clubId] 배열에서 랜덤 선택
let _feedMap = {};

// 댓글/좋아요 핫스팟 — setup()에서 채워짐
let HIGH_COMMENT_FEEDS = [];
let HOT_FEEDS = [];

function initData(data) {
    if (data && data.feedMap && Object.keys(_feedMap).length === 0) {
        _feedMap = data.feedMap;
        if (data.commentFeeds && data.commentFeeds.length > 0) HIGH_COMMENT_FEEDS = data.commentFeeds;
        if (data.hotFeeds && data.hotFeeds.length > 0) HOT_FEEDS = data.hotFeeds;
    }
}

function getRandomFeedForClub(clubId) {
    const feeds = _feedMap[clubId];
    if (feeds && feeds.length > 0) {
        return feeds[Math.floor(Math.random() * feeds.length)];
    }
    // 해당 클럽에 피드가 없으면 다른 클럽에서 가져옴 (setup 실패 대비)
    for (const key of Object.keys(_feedMap)) {
        if (_feedMap[key] && _feedMap[key].length > 0) {
            return _feedMap[key][Math.floor(Math.random() * _feedMap[key].length)];
        }
    }
    return null;
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
// Phase 타이밍 (초 단위, dur()/startAfter()로 스케일링)
// ============================================
const P1 = 30, P2 = 120, P3 = 120, P4 = 120, P5 = 120, P6 = 90;
const P7 = 90, P8 = 120, P9 = 120, P10 = 180, P11 = 30;

// ============================================
// 시나리오 설정
// ============================================
export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: vu(50),
            duration: dur(P1),
            exec: 'warmup',
            tags: { phase: '1_warmup' },
        },

        // Phase 2: Baseline — 전 API 혼합 (bottleneck 임계값 포함)
        baseline: {
            executor: 'constant-vus',
            vus: vu(300),
            duration: dur(P2),
            startTime: startAfter([P1]),
            exec: 'baseline',
            tags: { phase: '2_baseline' },
        },

        // Phase 3: 클럽 피드 목록 조회 — 350 VUs
        club_list: {
            executor: 'ramping-vus',
            startVUs: vu(10),
            stages: [
                { duration: dur(20), target: vu(350) },
                { duration: dur(80), target: vu(350) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2]),
            exec: 'clubFeedList',
            tags: { phase: '3_club_list' },
        },

        // Phase 4: 피드 상세 조회 — 고댓글 피드 집중 500 VUs
        feed_detail: {
            executor: 'ramping-vus',
            startVUs: vu(10),
            stages: [
                { duration: dur(20), target: vu(500) },
                { duration: dur(80), target: vu(500) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3]),
            exec: 'feedDetail',
            tags: { phase: '4_feed_detail' },
        },

        // Phase 5: 개인 피드 IN절 병목 — 500 VUs
        personal_feed: {
            executor: 'ramping-vus',
            startVUs: vu(10),
            stages: [
                { duration: dur(20), target: vu(500) },
                { duration: dur(80), target: vu(500) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4]),
            exec: 'personalFeed',
            tags: { phase: '5_personal_feed' },
        },

        // Phase 6: 댓글 목록 조회 — 350 VUs
        comment_list: {
            executor: 'ramping-vus',
            startVUs: vu(10),
            stages: [
                { duration: dur(15), target: vu(350) },
                { duration: dur(60), target: vu(350) },
                { duration: dur(15), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5]),
            exec: 'commentList',
            tags: { phase: '6_comment_list' },
        },

        // Phase 7: 좋아요 토글 경합 집중 — 700 VUs
        like_toggle: {
            executor: 'ramping-vus',
            startVUs: vu(10),
            stages: [
                { duration: dur(15), target: vu(700) },
                { duration: dur(60), target: vu(700) },
                { duration: dur(15), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5, P6]),
            exec: 'likeToggle',
            tags: { phase: '7_like_toggle' },
        },

        // Phase 8: 쓰기 혼합 — 피드생성+댓글생성+리피드 350 VUs
        write_mix: {
            executor: 'ramping-vus',
            startVUs: vu(10),
            stages: [
                { duration: dur(20), target: vu(350) },
                { duration: dur(80), target: vu(350) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7]),
            exec: 'writeMix',
            tags: { phase: '8_write_mix' },
        },

        // Phase 9: 극한 혼합 — 읽기70%+쓰기30% 1000 VUs
        extreme_mix: {
            executor: 'ramping-vus',
            startVUs: vu(20),
            stages: [
                { duration: dur(20), target: vu(1000) },
                { duration: dur(80), target: vu(1000) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7, P8]),
            exec: 'extremeMix',
            tags: { phase: '9_extreme' },
        },

        // Phase 10: Soak — 중간 부하 장시간
        soak: {
            executor: 'constant-vus',
            vus: vu(300),
            duration: dur(P10),
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7, P8, P9]),
            exec: 'soakTest',
            tags: { phase: '10_soak' },
        },

        // Phase 11: Cooldown
        cooldown: {
            executor: 'constant-vus',
            vus: vu(5),
            duration: dur(P11),
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7, P8, P9, P10]),
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
// setup() — 클럽별 실제 feedId를 API로 수집
// ============================================
export function setup() {
    const adminUser = makeUser(1);
    const token = generateJWT(adminUser);
    const hdrs = headers(token);

    const feedMap = {};
    const commentFeeds = [];
    const hotFeeds = [];

    for (const clubId of ALL_CLUB_IDS) {
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'setup_feed_list' },
        });
        if (res.status === 200) {
            try {
                const body = JSON.parse(res.body);
                const feedList = (body.data && body.data.content) || (body.data && body.data.feeds) || body.data || [];
                const ids = Array.isArray(feedList) ? feedList.map(f => f.feedId || f.feed_id || f.id).filter(Boolean) : [];
                feedMap[clubId] = ids;

                // 처음 3개는 댓글 테스트용, 전부 좋아요 핫스팟
                ids.slice(0, 3).forEach(fid => commentFeeds.push({ feedId: fid, clubId, comments: 3 }));
                ids.slice(0, 2).forEach(fid => hotFeeds.push({ feedId: fid, clubId }));
            } catch (e) {
                console.warn(`setup: clubId=${clubId} 피드 파싱 실패`);
                feedMap[clubId] = [];
            }
        } else {
            console.warn(`setup: clubId=${clubId} 피드 목록 조회 실패 (status=${res.status})`);
            feedMap[clubId] = [];
        }
    }

    return { feedMap, commentFeeds, hotFeeds };
}

// ============================================
export function warmup(data) {
    initData(data);
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
export function baseline(data) {
    initData(data);
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
    if (feedId) {
        const detailRes = http.get(`${BASE_URL}/api/v1/clubs/${detailClub}/feeds/${feedId}`, {
            headers: hdrs, tags: { name: 'bl_detail' },
        });
        feedDetailDur.add(detailRes.timings.duration);
        phase2Success.add(detailRes.status === 200);
        if (detailRes.status !== 200) totalErrors.add(1);
    }
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
        if (likeFeedId) {
            const likeRes = http.put(`${BASE_URL}/api/v1/clubs/${likeClubId}/feeds/${likeFeedId}/likes`, null, {
                headers: hdrs, tags: { name: 'bl_like' },
            });
            feedLikeDur.add(likeRes.timings.duration);
            phase2Success.add(likeRes.status === 200);
        }
    }
    sleep(0.2);

    // 댓글 목록 (30%)
    if (Math.random() < 0.3 && HIGH_COMMENT_FEEDS.length > 0) {
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
export function clubFeedList(data) {
    initData(data);
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
export function feedDetail(data) {
    initData(data);
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 70% 고댓글 피드, 30% 일반 피드
    let clubId, feedId;
    if (Math.random() < 0.7 && HIGH_COMMENT_FEEDS.length > 0) {
        const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
        clubId = hc.clubId;
        feedId = hc.feedId;
    } else {
        clubId = randomMainClub();
        feedId = getRandomFeedForClub(clubId);
    }

    if (!feedId) { sleep(0.1); return; }

    const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}`, {
        headers: hdrs, tags: { name: 'fd_detail' },
    });
    feedDetailDur.add(res.timings.duration);
    phase4Success.add(res.status === 200);
    if (res.status !== 200) totalErrors.add(1);

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 5: 개인 피드 IN절 병목 — 500 VUs
// ============================================
export function personalFeed(data) {
    initData(data);
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
export function commentList(data) {
    initData(data);
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 고댓글 피드 위주 — 깊은 페이지도 테스트
    if (HIGH_COMMENT_FEEDS.length === 0) { sleep(0.1); return; }
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
export function likeToggle(data) {
    initData(data);
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 80% 핫피드 집중, 20% 일반 분산
    let clubId, feedId;
    if (Math.random() < 0.8 && HOT_FEEDS.length > 0) {
        const hot = HOT_FEEDS[Math.floor(Math.random() * HOT_FEEDS.length)];
        clubId = hot.clubId;
        feedId = hot.feedId;
    } else {
        clubId = randomMainClub();
        feedId = getRandomFeedForClub(clubId);
    }

    if (!feedId) { sleep(0.02); return; }

    const res = http.put(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`, null, {
        headers: hdrs, tags: { name: 'lt_like_toggle' },
    });
    feedLikeDur.add(res.timings.duration);
    phase7Success.add(res.status === 200);
    if (res.status !== 200) totalErrors.add(1);

    sleep(0.02 + Math.random() * 0.05);
}

// ============================================
// Phase 8: 쓰기 혼합 — 피드생성+댓글생성+리피드 350 VUs
// ============================================
export function writeMix(data) {
    initData(data);
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
        ok = res.status === 201;
        feedCreateDur.add(dur);
    } else if (roll < 0.80) {
        // 40%: 댓글 생성 — 유저가 속한 클럽의 피드에 작성
        const clubId = getRandomUserClub(user.userId);
        const commentFeedId = getRandomFeedForClub(clubId);
        if (!commentFeedId) { sleep(0.2); return; }
        const res = http.post(
            `${BASE_URL}/api/v1/clubs/${clubId}/feeds/${commentFeedId}/comments`,
            JSON.stringify({ content: `k6 comment ${Date.now()}` }),
            { headers: hdrs, tags: { name: 'wm_create_comment' } }
        );
        dur = res.timings.duration;
        ok = res.status === 201;
        feedCommentCreateDur.add(dur);
    } else {
        // 20%: 리피드 — 유저가 속한 클럽으로 리피드
        if (HIGH_COMMENT_FEEDS.length === 0) { sleep(0.2); return; }
        const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
        const targetClub = getRandomUserClub(user.userId);
        const res = http.post(
            `${BASE_URL}/api/v1/feeds/${hc.feedId}/${targetClub}`,
            JSON.stringify({ content: `k6 refeed ${Date.now()}` }),
            { headers: hdrs, tags: { name: 'wm_refeed' } }
        );
        dur = res.timings.duration;
        // 409 = 이미 리피드됨 (unique 제약) → 정상 동작으로 간주
        ok = res.status === 201 || res.status === 409;
        feedRefeedDur.add(dur);
    }

    phase8Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.2 + Math.random() * 0.3);
}

// ============================================
// Phase 9: 극한 혼합 — 읽기70%+쓰기30% 1000 VUs
// ============================================
export function extremeMix(data) {
    initData(data);
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
        if (HIGH_COMMENT_FEEDS.length === 0) { sleep(0.02); return; }
        const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
        const res = http.get(`${BASE_URL}/api/v1/clubs/${hc.clubId}/feeds/${hc.feedId}`, {
            headers: hdrs, tags: { name: 'ex_detail' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
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
        if (HIGH_COMMENT_FEEDS.length === 0) { sleep(0.02); return; }
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
        if (!feedId) { sleep(0.02); return; }
        const res = http.put(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`, null, {
            headers: hdrs, tags: { name: 'ex_like' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        feedLikeDur.add(dur);
    } else if (roll < 0.92) {
        // 10%: 댓글 작성 — 유저가 속한 클럽의 피드에 작성
        const commentClub = getRandomUserClub(user.userId);
        const commentFeedId = getRandomFeedForClub(commentClub);
        if (!commentFeedId) { sleep(0.02); return; }
        const res = http.post(
            `${BASE_URL}/api/v1/clubs/${commentClub}/feeds/${commentFeedId}/comments`,
            JSON.stringify({ content: `extreme ${Date.now()}` }),
            { headers: hdrs, tags: { name: 'ex_write_comment' } }
        );
        dur = res.timings.duration;
        ok = res.status === 201 || res.status === 409;
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
        ok = res.status === 201 || res.status === 409;
        feedCreateDur.add(dur);
    }

    phase9Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.02 + Math.random() * 0.05);
}

// ============================================
// Phase 10: Soak — 300 VUs 3분 안정성
// ============================================
export function soakTest(data) {
    initData(data);
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
        if (HIGH_COMMENT_FEEDS.length === 0) { sleep(0.05); return; }
        const hc = HIGH_COMMENT_FEEDS[Math.floor(Math.random() * HIGH_COMMENT_FEEDS.length)];
        const res = http.get(`${BASE_URL}/api/v1/clubs/${hc.clubId}/feeds/${hc.feedId}`, {
            headers: hdrs, tags: { name: 'soak_detail' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        feedDetailDur.add(dur);
    } else if (roll < 0.75) {
        // 15%: 좋아요 토글
        const clubId = randomMainClub();
        const feedId = getRandomFeedForClub(clubId);
        if (!feedId) { sleep(0.05); return; }
        const res = http.put(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`, null, {
            headers: hdrs, tags: { name: 'soak_like' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        feedLikeDur.add(dur);
    } else if (roll < 0.85) {
        // 10%: 댓글 목록
        if (HIGH_COMMENT_FEEDS.length === 0) { sleep(0.05); return; }
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
        if (!commentFeedId) { sleep(0.05); return; }
        const res = http.post(
            `${BASE_URL}/api/v1/clubs/${commentClub}/feeds/${commentFeedId}/comments`,
            JSON.stringify({ content: `soak ${Date.now()}` }),
            { headers: hdrs, tags: { name: 'soak_write_comment' } }
        );
        dur = res.timings.duration;
        ok = res.status === 201 || res.status === 409;
        feedCommentCreateDur.add(dur);
    }

    phase10Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.05 + Math.random() * 0.15);
}

// ============================================
// default function (fallback)
// ============================================
export default function (data) {
    warmup(data);
}

// ============================================
// handleSummary — 피드 부하 테스트 결과 리포트
// ============================================
export function handleSummary(data) {
    const line = '─'.repeat(60);

    let summary = `
╔════════════════════════════════════════════════════════════╗
║              피드 도메인 부하 테스트 결과                    ║
╚════════════════════════════════════════════════════════════╝
`;

    const metrics = [
        ['클럽피드 목록',       'feed_club_list_duration'],
        ['피드 상세',           'feed_detail_duration'],
        ['개인 피드',           'feed_personal_duration'],
        ['인기 피드',           'feed_popular_duration'],
        ['댓글 목록',           'feed_comment_list_duration'],
        ['좋아요 토글',         'feed_like_duration'],
        ['피드 생성',           'feed_create_duration'],
        ['댓글 생성',           'feed_comment_create_duration'],
        ['리피드',              'feed_refeed_duration'],
    ];

    summary += `\n${line}\n`;
    summary += `${'API'.padEnd(22)} ${'p50'.padStart(8)} ${'p95'.padStart(8)} ${'p99'.padStart(8)} ${'max'.padStart(8)}  ${'avg'.padStart(8)}\n`;
    summary += `${line}\n`;

    for (const [label, key] of metrics) {
        const m = data.metrics[key];
        if (m && m.values) {
            const v = m.values;
            summary += `${label.padEnd(22)} ${fmt(v['p(50)'])} ${fmt(v['p(95)'])} ${fmt(v['p(99)'])} ${fmt(v['max'])}  ${fmt(v['avg'])}\n`;
        }
    }
    summary += `${line}\n`;

    // Phase별 성공률
    const phases = [
        ['Phase 2 Baseline',    'feed_phase2_success'],
        ['Phase 3 ClubList',    'feed_phase3_success'],
        ['Phase 4 Detail',      'feed_phase4_success'],
        ['Phase 5 Personal',    'feed_phase5_success'],
        ['Phase 6 Comments',    'feed_phase6_success'],
        ['Phase 7 Like',        'feed_phase7_success'],
        ['Phase 8 WriteMix',    'feed_phase8_success'],
        ['Phase 9 Extreme',     'feed_phase9_success'],
        ['Phase 10 Soak',       'feed_phase10_success'],
    ];

    summary += `\n${'Phase'.padEnd(22)} ${'성공률'.padStart(10)}\n`;
    summary += `${line}\n`;
    for (const [label, key] of phases) {
        const m = data.metrics[key];
        if (m && m.values) {
            const rate = (m.values['rate'] * 100).toFixed(2) + '%';
            summary += `${label.padEnd(22)} ${rate.padStart(10)}\n`;
        }
    }
    summary += `${line}\n`;

    // 에러 카운트
    const errMetric = data.metrics['feed_total_errors'];
    summary += `\n총 에러: ${errMetric ? errMetric.values.count : 0}\n`;

    // Thresholds PASS/FAIL
    let passCount = 0, failCount = 0;
    if (data.metrics) {
        for (const [, val] of Object.entries(data.metrics)) {
            if (val.thresholds) {
                for (const [, th] of Object.entries(val.thresholds)) {
                    if (th.ok) passCount++;
                    else failCount++;
                }
            }
        }
    }
    summary += `Thresholds: ${passCount} PASS / ${failCount} FAIL\n`;

    console.log(summary);

    return { 'stdout': summary };
}

function fmt(ms) {
    if (ms === undefined || ms === null) return 'N/A'.padStart(8);
    if (ms < 1000) return (ms.toFixed(0) + 'ms').padStart(8);
    return ((ms / 1000).toFixed(2) + 's').padStart(8);
}
