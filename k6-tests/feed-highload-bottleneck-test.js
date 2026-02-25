// =============================================================
// 피드 도메인 — 고부하 병목 탐지 테스트
//
// 데이터: feed 60만, comment 295만, like 512만, repost 10만
//
// Phase 1: 인기피드 정렬 병목 (LOG+TIMESTAMPDIFF 풀스캔)
// Phase 2: 개인피드 IN절 폭발 (유저당 20+ 클럽)
// Phase 3: 리포스트 카운트 집계 + 피드 상세 댓글 전체 로드
// Phase 4: 좋아요 동시성 경합 (핫피드 집중)
// Phase 5: 전 API 혼합 극한 부하 (600 VU)
// Phase 6: 딥 페이지네이션 (page 50+)
// =============================================================

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL } from './lib/common.js';

// init 단계: open()은 1회만 실행됨 (k6 특성상 VU간 공유)
const USER_CLUBS     = JSON.parse(open('./lib/user-feed-clubs.json'));
const CLUB_FEEDS     = JSON.parse(open('./lib/club-feeds.json'));
const HOT_FEEDS      = JSON.parse(open('./lib/hot-feeds.json'));
const HOT_FEED_CLUBS = JSON.parse(open('./lib/hot-feed-clubs.json'));

// ── 메트릭 ──

// Phase 1: 인기피드 정렬
const p1PopularDur     = new Trend('p1_popular_duration', true);
const p1PopularDeep    = new Trend('p1_popular_deep_page', true);
const p1SuccessRate    = new Rate('p1_success_rate');
const p1Errors         = new Counter('p1_errors');
const p1SlowCount      = new Counter('p1_slow_queries');

// Phase 2: 개인피드 IN절
const p2PersonalDur    = new Trend('p2_personal_duration', true);
const p2SuccessRate    = new Rate('p2_success_rate');
const p2Errors         = new Counter('p2_errors');
const p2SlowCount      = new Counter('p2_slow_queries');

// Phase 3: 리포스트+상세
const p3DetailDur      = new Trend('p3_detail_duration', true);
const p3CommentListDur = new Trend('p3_comment_list_duration', true);
const p3ClubFeedDur    = new Trend('p3_club_feed_duration', true);
const p3SuccessRate    = new Rate('p3_success_rate');
const p3Errors         = new Counter('p3_errors');

// Phase 4: 좋아요 경합
const p4LikeDur        = new Trend('p4_like_duration', true);
const p4LikeConflict   = new Counter('p4_like_conflict');
const p4SuccessRate    = new Rate('p4_success_rate');
const p4Errors         = new Counter('p4_errors');

// Phase 5: 혼합 극한
const p5ReadDur        = new Trend('p5_read_duration', true);
const p5WriteDur       = new Trend('p5_write_duration', true);
const p5SuccessRate    = new Rate('p5_success_rate');
const p5Errors         = new Counter('p5_errors');

// Phase 6: 딥 페이지네이션
const p6DeepPageDur    = new Trend('p6_deep_page_duration', true);
const p6SuccessRate    = new Rate('p6_success_rate');
const p6Errors         = new Counter('p6_errors');

// 통합
const apiSuccessRate   = new Rate('api_success_rate');
const serverErrors     = new Counter('server_5xx_errors');

// ── 유틸 ──

const TEST_USERS_COUNT = 1000;

function getUser(vuId) {
    const userId = ((vuId - 1) % TEST_USERS_COUNT) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

function getClubForUser(userId) {
    const clubs = USER_CLUBS[String(userId)];
    if (!clubs || clubs.length === 0) return null;
    return clubs[Math.floor(Math.random() * clubs.length)];
}

function getFeedInClub(clubId) {
    const feeds = CLUB_FEEDS[String(clubId)];
    if (!feeds || feeds.length === 0) return null;
    return feeds[Math.floor(Math.random() * feeds.length)];
}

function randomHotFeed() {
    const feedId = HOT_FEEDS[Math.floor(Math.random() * HOT_FEEDS.length)];
    const clubId = HOT_FEED_CLUBS[String(feedId)];
    return { feedId, clubId: clubId || null };
}

function record(res, successRate, errCounter) {
    const ok = res.status >= 200 && res.status < 400;
    successRate.add(ok ? 1 : 0);
    apiSuccessRate.add(ok ? 1 : 0);
    if (res.status >= 500) { errCounter.add(1); serverErrors.add(1); }
}

// ── VU 배율 (환경변수 VU_SCALE로 조절, 기본 1.0) ──
//   VU_SCALE=1   → 기본 (P1:400, P5:400)
//   VU_SCALE=1.5 → 1.5배 (P1:600, P5:600)
//   VU_SCALE=2   → 2배   (P1:800, P5:800)
//   VU_SCALE=3   → 3배   (P1:1200, P5:1200)

const VU_SCALE = parseFloat(__ENV.VU_SCALE || '1');
const vu = (base) => Math.round(base * VU_SCALE);

// ── 시나리오 (총 ~13분) ──

export const options = {
    scenarios: {
        // Phase 1: 인기피드 정렬 병목 (0~3분)
        phase1_popular_sort: {
            executor: 'ramping-vus',
            exec: 'popularSortTest',
            startVUs: 0,
            stages: [
                { duration: '20s', target: vu(50) },
                { duration: '30s', target: vu(200) },
                { duration: '40s', target: vu(400) },
                { duration: '40s', target: vu(400) },
                { duration: '20s', target: 0 },
            ],
            startTime: '0s',
            tags: { phase: 'popular_sort' },
        },

        // Phase 2: 개인피드 IN절 폭발 (2:30~5분)
        phase2_personal_in: {
            executor: 'ramping-vus',
            exec: 'personalInClauseTest',
            startVUs: 0,
            stages: [
                { duration: '20s', target: vu(50) },
                { duration: '30s', target: vu(200) },
                { duration: '40s', target: vu(400) },
                { duration: '40s', target: vu(400) },
                { duration: '20s', target: 0 },
            ],
            startTime: '2m30s',
            tags: { phase: 'personal_in' },
        },

        // Phase 3: 리포스트+상세+댓글 (5:10~7:30)
        phase3_repost_detail: {
            executor: 'ramping-vus',
            exec: 'repostDetailTest',
            startVUs: 0,
            stages: [
                { duration: '20s', target: vu(50) },
                { duration: '30s', target: vu(150) },
                { duration: '40s', target: vu(300) },
                { duration: '30s', target: vu(300) },
                { duration: '20s', target: 0 },
            ],
            startTime: '5m10s',
            tags: { phase: 'repost_detail' },
        },

        // Phase 4: 좋아요 경합 — 핫피드 집중 (7:40~9:40)
        phase4_like_contention: {
            executor: 'ramping-vus',
            exec: 'likeContentionTest',
            startVUs: 0,
            stages: [
                { duration: '15s', target: vu(100) },
                { duration: '30s', target: vu(200) },
                { duration: '30s', target: vu(300) },
                { duration: '20s', target: vu(300) },
                { duration: '15s', target: 0 },
            ],
            startTime: '7m40s',
            tags: { phase: 'like_contention' },
        },

        // Phase 5: 전 API 혼합 극한 (9:50~12:00)
        phase5_extreme_mixed: {
            executor: 'ramping-vus',
            exec: 'extremeMixedTest',
            startVUs: 0,
            stages: [
                { duration: '15s', target: vu(100) },
                { duration: '20s', target: vu(200) },
                { duration: '30s', target: vu(400) },
                { duration: '30s', target: vu(400) },
                { duration: '15s', target: 0 },
            ],
            startTime: '9m50s',
            tags: { phase: 'extreme_mixed' },
        },

        // Phase 6: 딥 페이지네이션 (12:10~13:40)
        phase6_deep_pagination: {
            executor: 'ramping-vus',
            exec: 'deepPaginationTest',
            startVUs: 0,
            stages: [
                { duration: '15s', target: vu(50) },
                { duration: '30s', target: vu(200) },
                { duration: '20s', target: vu(200) },
                { duration: '15s', target: 0 },
            ],
            startTime: '12m10s',
            tags: { phase: 'deep_pagination' },
        },
    },

    thresholds: {
        'p1_popular_duration':      ['p(95)<800'],
        'p1_popular_deep_page':     ['p(95)<2000'],
        'p2_personal_duration':     ['p(95)<500'],
        'p3_detail_duration':       ['p(95)<500'],
        'p3_comment_list_duration': ['p(95)<500'],
        'p4_like_duration':         ['p(95)<300'],
        'p5_read_duration':         ['p(95)<1000'],
        'p5_write_duration':        ['p(95)<500'],
        'p6_deep_page_duration':    ['p(95)<2000'],
        'p1_success_rate':          ['rate>0.90'],
        'p2_success_rate':          ['rate>0.90'],
        'p4_success_rate':          ['rate>0.90'],
        'p5_success_rate':          ['rate>0.85'],
    },
};

// ── setup ──

export function setup() {
    console.log('╔══════════════════════════════════════════════════════════════╗');
    console.log(`║  피드 고부하 병목 탐지 (6-Phase, ~13분) VU_SCALE=${VU_SCALE}x       ║`);
    console.log('╠══════════════════════════════════════════════════════════════╣');
    console.log(`║  P1  0:00~2:30  인기피드 정렬 병목     → 최대 ${String(vu(400)).padStart(4)} VU    ║`);
    console.log(`║  P2  2:30~5:00  개인피드 IN절 폭발     → 최대 ${String(vu(400)).padStart(4)} VU    ║`);
    console.log(`║  P3  5:10~7:30  리포스트+상세+댓글     → 최대 ${String(vu(300)).padStart(4)} VU    ║`);
    console.log(`║  P4  7:40~9:40  좋아요 동시성 경합     → 최대 ${String(vu(300)).padStart(4)} VU    ║`);
    console.log(`║  P5  9:50~12:00 전 API 혼합 극한       → 최대 ${String(vu(400)).padStart(4)} VU    ║`);
    console.log(`║  P6 12:10~13:40 딥 페이지네이션         → 최대 ${String(vu(200)).padStart(4)} VU    ║`);
    console.log('╚══════════════════════════════════════════════════════════════╝');

    const health = http.get(`${BASE_URL}/actuator/health`);
    if (health.status !== 200) console.error('서버 헬스체크 실패!');

    console.log(`유저-클럽 매핑: ${Object.keys(USER_CLUBS).length}명`);
    console.log(`클럽-피드 매핑: ${Object.keys(CLUB_FEEDS).length}개 클럽`);
    console.log(`핫 피드: ${HOT_FEEDS.length}개`);
}

// ═══════════════════════════════════════════
// Phase 1: 인기피드 정렬 병목
//   LOG(GREATEST()) + TIMESTAMPDIFF 연산을 row마다 수행
//   커버링 인덱스 효과 vs CPU-bound 정렬 비교
// ═══════════════════════════════════════════
export function popularSortTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const r = Math.random();

    if (r < 0.5) {
        // 첫 페이지 (캐시 워밍 확인)
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&limit=20`, {
            headers: hdrs,
            tags: { name: 'popular_p0' },
        });
        const elapsed = Date.now() - start;
        p1PopularDur.add(elapsed);
        record(res, p1SuccessRate, p1Errors);
        if (elapsed > 500) p1SlowCount.add(1);
        check(res, { 'P1 popular p0 200': (r) => r.status === 200 });

    } else if (r < 0.8) {
        // 2~5페이지 (OFFSET 증가에 따른 성능 변화)
        const page = Math.floor(Math.random() * 4) + 2;
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=${page}&limit=20`, {
            headers: hdrs,
            tags: { name: 'popular_mid' },
        });
        const elapsed = Date.now() - start;
        p1PopularDur.add(elapsed);
        record(res, p1SuccessRate, p1Errors);
        if (elapsed > 500) p1SlowCount.add(1);

    } else {
        // 딥 페이지 (10~30) — 정렬 비용 극대화
        const page = Math.floor(Math.random() * 20) + 10;
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=${page}&limit=20`, {
            headers: hdrs,
            tags: { name: 'popular_deep' },
        });
        const elapsed = Date.now() - start;
        p1PopularDeep.add(elapsed);
        record(res, p1SuccessRate, p1Errors);
        if (elapsed > 1000) p1SlowCount.add(1);
    }

    sleep(Math.random() * 0.2 + 0.05);
}

// ═══════════════════════════════════════════
// Phase 2: 개인피드 — IN절 폭발
//   유저당 20+ 클럽 → WHERE club_id IN (c1,c2,...c20+)
//   resolveAccessibleClubIds 2회 호출 문제
// ═══════════════════════════════════════════
export function personalInClauseTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const r = Math.random();

    if (r < 0.4) {
        // 첫 페이지
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds?page=0&limit=20`, {
            headers: hdrs,
            tags: { name: 'personal_p0' },
        });
        const elapsed = Date.now() - start;
        p2PersonalDur.add(elapsed);
        record(res, p2SuccessRate, p2Errors);
        if (elapsed > 300) p2SlowCount.add(1);
        check(res, { 'P2 personal p0 200': (r) => r.status === 200 });

    } else if (r < 0.7) {
        // 중간 페이지
        const page = Math.floor(Math.random() * 5) + 1;
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds?page=${page}&limit=20`, {
            headers: hdrs,
            tags: { name: 'personal_mid' },
        });
        const elapsed = Date.now() - start;
        p2PersonalDur.add(elapsed);
        record(res, p2SuccessRate, p2Errors);
        if (elapsed > 300) p2SlowCount.add(1);

    } else {
        // 큰 페이지 사이즈 (50) — IN절 + 결과셋 부하
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds?page=0&limit=50`, {
            headers: hdrs,
            tags: { name: 'personal_large' },
        });
        const elapsed = Date.now() - start;
        p2PersonalDur.add(elapsed);
        record(res, p2SuccessRate, p2Errors);
        if (elapsed > 500) p2SlowCount.add(1);
    }

    sleep(Math.random() * 0.2 + 0.05);
}

// ═══════════════════════════════════════════
// Phase 3: 리포스트 카운트 + 피드 상세 + 댓글 전체 로드
//   countDirectRepostsIn GROUP BY → 리포스트 10만건
//   getFeedDetail 댓글 → 페이지네이션 없이 전체 로드
// ═══════════════════════════════════════════
export function repostDetailTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const clubId = getClubForUser(user.userId);
    if (!clubId) { sleep(0.1); return; }

    const r = Math.random();

    if (r < 0.35) {
        // 클럽 피드 목록 (리포스트 카운트 집계 포함)
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=20`, {
            headers: hdrs,
            tags: { name: 'club_feed_repost' },
        });
        const elapsed = Date.now() - start;
        p3ClubFeedDur.add(elapsed);
        record(res, p3SuccessRate, p3Errors);

    } else if (r < 0.65) {
        // 핫 피드 상세 (댓글 100+ 전체 로드)
        const hot = randomHotFeed();
        if (!hot.clubId) { sleep(0.1); return; }
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${hot.clubId}/feeds/${hot.feedId}`, {
            headers: hdrs,
            tags: { name: 'hot_feed_detail' },
        });
        const elapsed = Date.now() - start;
        p3DetailDur.add(elapsed);
        record(res, p3SuccessRate, p3Errors);

    } else {
        // 일반 피드 상세 + 댓글
        const feedId = getFeedInClub(clubId);
        if (!feedId) { sleep(0.1); return; }

        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}`, {
            headers: hdrs,
            tags: { name: 'feed_detail' },
        });
        const elapsed = Date.now() - start;
        p3DetailDur.add(elapsed);
        record(res, p3SuccessRate, p3Errors);

        // 댓글 목록 별도 조회
        if (res.status === 200) {
            const cs = Date.now();
            const cr = http.get(`${BASE_URL}/api/v1/feeds/${feedId}/comments?page=0&limit=20`, {
                headers: hdrs,
                tags: { name: 'comment_list' },
            });
            p3CommentListDur.add(Date.now() - cs);
            record(cr, p3SuccessRate, p3Errors);
        }
    }

    sleep(Math.random() * 0.2 + 0.1);
}

// ═══════════════════════════════════════════
// Phase 4: 좋아요 동시성 경합
//   소수의 핫피드에 다수 VU가 동시 좋아요 토글
//   Redis Lua + Stream Consumer 병목 탐지
// ═══════════════════════════════════════════
export function likeContentionTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const clubId = getClubForUser(user.userId);
    if (!clubId) { sleep(0.1); return; }

    const r = Math.random();
    let feedId;

    let likeClubId = clubId;
    if (r < 0.7) {
        // 70%: 핫피드 집중 (경합 극대화)
        const hot = randomHotFeed();
        if (!hot.clubId) { sleep(0.1); return; }
        feedId = hot.feedId;
        likeClubId = hot.clubId;
    } else {
        // 30%: 일반 피드
        feedId = getFeedInClub(clubId);
        if (!feedId) { sleep(0.1); return; }
    }

    const start = Date.now();
    const res = http.put(`${BASE_URL}/api/v1/clubs/${likeClubId}/feeds/${feedId}/likes`, null, {
        headers: hdrs,
        tags: { name: 'like_contention' },
    });
    const elapsed = Date.now() - start;
    p4LikeDur.add(elapsed);
    record(res, p4SuccessRate, p4Errors);

    if (res.status === 409 || res.status === 429) {
        p4LikeConflict.add(1);
    }

    check(res, { 'P4 like ok': (r) => r.status === 200 || r.status === 404 });

    sleep(Math.random() * 0.1 + 0.02);
}

// ═══════════════════════════════════════════
// Phase 5: 전 API 혼합 극한 (600 VU)
//   읽기 60% / 쓰기 40%로 실 서비스 부하 시뮬레이션
// ═══════════════════════════════════════════
export function extremeMixedTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const r = Math.random();

    if (r < 0.20) {
        // 개인피드
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'personal_feed' },
        });
        p5ReadDur.add(Date.now() - start);
        record(res, p5SuccessRate, p5Errors);

    } else if (r < 0.35) {
        // 인기피드
        const page = Math.floor(Math.random() * 5);
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=${page}&limit=20`, {
            headers: hdrs, tags: { name: 'popular_feed' },
        });
        p5ReadDur.add(Date.now() - start);
        record(res, p5SuccessRate, p5Errors);

    } else if (r < 0.50) {
        // 클럽 피드 목록
        const clubId = getClubForUser(user.userId);
        if (!clubId) { p5SuccessRate.add(1); sleep(0.05); return; }
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'club_feed_list' },
        });
        p5ReadDur.add(Date.now() - start);
        record(res, p5SuccessRate, p5Errors);

    } else if (r < 0.60) {
        // 피드 상세
        const clubId = getClubForUser(user.userId);
        if (!clubId) { p5SuccessRate.add(1); sleep(0.05); return; }
        const feedId = getFeedInClub(clubId);
        if (!feedId) { p5SuccessRate.add(1); sleep(0.05); return; }
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}`, {
            headers: hdrs, tags: { name: 'feed_detail' },
        });
        p5ReadDur.add(Date.now() - start);
        record(res, p5SuccessRate, p5Errors);

    } else if (r < 0.80) {
        // 좋아요 토글
        const clubId = getClubForUser(user.userId);
        if (!clubId) { p5SuccessRate.add(1); sleep(0.05); return; }
        let likeClubId = clubId;
        let feedId;
        if (Math.random() < 0.5) {
            const hot = randomHotFeed();
            if (!hot.clubId) { p5SuccessRate.add(1); sleep(0.05); return; }
            feedId = hot.feedId;
            likeClubId = hot.clubId;
        } else {
            feedId = getFeedInClub(clubId);
            if (!feedId) { p5SuccessRate.add(1); sleep(0.05); return; }
        }
        const start = Date.now();
        const res = http.put(`${BASE_URL}/api/v1/clubs/${likeClubId}/feeds/${feedId}/likes`, null, {
            headers: hdrs, tags: { name: 'like_toggle' },
        });
        p5WriteDur.add(Date.now() - start);
        record(res, p5SuccessRate, p5Errors);

    } else {
        // 댓글 작성
        const clubId = getClubForUser(user.userId);
        if (!clubId) { p5SuccessRate.add(1); sleep(0.05); return; }
        const feedId = getFeedInClub(clubId);
        if (!feedId) { p5SuccessRate.add(1); sleep(0.05); return; }
        const payload = JSON.stringify({ content: `stress ${user.userId}-${Date.now()}` });
        const start = Date.now();
        const res = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/comments`, payload, {
            headers: hdrs, tags: { name: 'comment_create' },
        });
        p5WriteDur.add(Date.now() - start);
        record(res, p5SuccessRate, p5Errors);
    }

    sleep(Math.random() * 0.1 + 0.02);
}

// ═══════════════════════════════════════════
// Phase 6: 딥 페이지네이션
//   OFFSET이 커질수록 MySQL이 앞의 row를 모두 스캔
//   page 50~200에서 성능 저하 확인
// ═══════════════════════════════════════════
export function deepPaginationTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const r = Math.random();

    if (r < 0.3) {
        // 개인피드 딥 페이지
        const page = Math.floor(Math.random() * 150) + 50;
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds?page=${page}&limit=20`, {
            headers: hdrs, tags: { name: 'personal_deep' },
        });
        p6DeepPageDur.add(Date.now() - start);
        record(res, p6SuccessRate, p6Errors);

    } else if (r < 0.6) {
        // 인기피드 딥 페이지
        const page = Math.floor(Math.random() * 100) + 50;
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=${page}&limit=20`, {
            headers: hdrs, tags: { name: 'popular_deep' },
        });
        p6DeepPageDur.add(Date.now() - start);
        record(res, p6SuccessRate, p6Errors);

    } else {
        // 클럽 피드 딥 페이지
        const clubId = getClubForUser(user.userId);
        if (!clubId) { sleep(0.1); return; }
        const page = Math.floor(Math.random() * 50) + 20;
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=${page}&limit=20`, {
            headers: hdrs, tags: { name: 'club_deep' },
        });
        p6DeepPageDur.add(Date.now() - start);
        record(res, p6SuccessRate, p6Errors);
    }

    sleep(Math.random() * 0.2 + 0.05);
}

// ── 결과 요약 ──

export function handleSummary(data) {
    const m = data.metrics;
    const val = (metric, key) => metric?.values?.[key];
    const fmt = (v) => v != null ? v.toFixed(0) : '-';
    const pct = (v) => v != null ? (v * 100).toFixed(1) : '-';

    console.log('\n╔══════════════════════════════════════════════════════════════════════╗');
    console.log('║               피드 고부하 병목 탐지 결과                               ║');
    console.log('╠══════════════════════════════════════════════════════════════════════╣');

    // P1
    console.log('║  P1: 인기피드 정렬 (LOG+TIMESTAMPDIFF) — 최대 400 VU               ║');
    console.log(`║    일반페이지  p95: ${fmt(val(m.p1_popular_duration, 'p(95)')).padStart(7)}ms  avg: ${fmt(val(m.p1_popular_duration, 'avg')).padStart(7)}ms          ║`);
    console.log(`║    딥페이지    p95: ${fmt(val(m.p1_popular_deep_page, 'p(95)')).padStart(7)}ms  avg: ${fmt(val(m.p1_popular_deep_page, 'avg')).padStart(7)}ms          ║`);
    console.log(`║    성공률: ${pct(val(m.p1_success_rate, 'rate')).padStart(5)}%  슬로우: ${String(val(m.p1_slow_queries, 'count') || 0).padStart(5)}건                    ║`);
    console.log('║                                                                      ║');

    // P2
    console.log('║  P2: 개인피드 IN절 폭발 (20+ 클럽) — 최대 400 VU                   ║');
    console.log(`║    개인피드    p95: ${fmt(val(m.p2_personal_duration, 'p(95)')).padStart(7)}ms  avg: ${fmt(val(m.p2_personal_duration, 'avg')).padStart(7)}ms          ║`);
    console.log(`║    성공률: ${pct(val(m.p2_success_rate, 'rate')).padStart(5)}%  슬로우: ${String(val(m.p2_slow_queries, 'count') || 0).padStart(5)}건                    ║`);
    console.log('║                                                                      ║');

    // P3
    console.log('║  P3: 리포스트+상세+댓글 — 최대 300 VU                               ║');
    console.log(`║    피드상세    p95: ${fmt(val(m.p3_detail_duration, 'p(95)')).padStart(7)}ms  avg: ${fmt(val(m.p3_detail_duration, 'avg')).padStart(7)}ms          ║`);
    console.log(`║    댓글목록    p95: ${fmt(val(m.p3_comment_list_duration, 'p(95)')).padStart(7)}ms  avg: ${fmt(val(m.p3_comment_list_duration, 'avg')).padStart(7)}ms          ║`);
    console.log(`║    클럽피드    p95: ${fmt(val(m.p3_club_feed_duration, 'p(95)')).padStart(7)}ms  avg: ${fmt(val(m.p3_club_feed_duration, 'avg')).padStart(7)}ms          ║`);
    console.log(`║    성공률: ${pct(val(m.p3_success_rate, 'rate')).padStart(5)}%                                               ║`);
    console.log('║                                                                      ║');

    // P4
    console.log('║  P4: 좋아요 동시성 경합 (핫피드 집중) — 최대 300 VU                 ║');
    console.log(`║    좋아요      p95: ${fmt(val(m.p4_like_duration, 'p(95)')).padStart(7)}ms  avg: ${fmt(val(m.p4_like_duration, 'avg')).padStart(7)}ms          ║`);
    console.log(`║    성공률: ${pct(val(m.p4_success_rate, 'rate')).padStart(5)}%  경합: ${String(val(m.p4_like_conflict, 'count') || 0).padStart(5)}건                      ║`);
    console.log('║                                                                      ║');

    // P5
    console.log('║  P5: 전 API 혼합 극한 — 최대 400 VU                                ║');
    console.log(`║    읽기        p95: ${fmt(val(m.p5_read_duration, 'p(95)')).padStart(7)}ms  avg: ${fmt(val(m.p5_read_duration, 'avg')).padStart(7)}ms          ║`);
    console.log(`║    쓰기        p95: ${fmt(val(m.p5_write_duration, 'p(95)')).padStart(7)}ms  avg: ${fmt(val(m.p5_write_duration, 'avg')).padStart(7)}ms          ║`);
    console.log(`║    성공률: ${pct(val(m.p5_success_rate, 'rate')).padStart(5)}%                                               ║`);
    console.log('║                                                                      ║');

    // P6
    console.log('║  P6: 딥 페이지네이션 (page 50~200) — 최대 200 VU                   ║');
    console.log(`║    딥페이지    p95: ${fmt(val(m.p6_deep_page_duration, 'p(95)')).padStart(7)}ms  avg: ${fmt(val(m.p6_deep_page_duration, 'avg')).padStart(7)}ms          ║`);
    console.log(`║    성공률: ${pct(val(m.p6_success_rate, 'rate')).padStart(5)}%                                               ║`);

    // 통합
    const allRate = val(m.api_success_rate, 'rate') || 0;
    const all5xx = val(m.server_5xx_errors, 'count') || 0;
    console.log('╠══════════════════════════════════════════════════════════════════════╣');
    console.log(`║  API 성공률: ${pct(allRate).padStart(5)}% │ 5xx: ${String(all5xx).padStart(5)}건                                ║`);
    console.log('╚══════════════════════════════════════════════════════════════════════╝');

    // 병목 경고
    const warnings = [];
    const p1p95 = val(m.p1_popular_duration, 'p(95)');
    const p1dp95 = val(m.p1_popular_deep_page, 'p(95)');
    const p2p95 = val(m.p2_personal_duration, 'p(95)');
    const p3d95 = val(m.p3_detail_duration, 'p(95)');
    const p3c95 = val(m.p3_comment_list_duration, 'p(95)');
    const p4p95 = val(m.p4_like_duration, 'p(95)');
    const p5r95 = val(m.p5_read_duration, 'p(95)');
    const p5w95 = val(m.p5_write_duration, 'p(95)');
    const p6p95 = val(m.p6_deep_page_duration, 'p(95)');

    if (p1p95 > 800)  warnings.push(`[병목] P1 인기피드 정렬 p95=${fmt(p1p95)}ms > 800ms → LOG+TIMESTAMPDIFF CPU 바운드`);
    if (p1dp95 > 2000) warnings.push(`[병목] P1 인기피드 딥페이지 p95=${fmt(p1dp95)}ms > 2000ms → OFFSET 풀스캔`);
    if (p2p95 > 500)  warnings.push(`[병목] P2 개인피드 IN절 p95=${fmt(p2p95)}ms > 500ms → 클럽 수 비례 성능 저하`);
    if (p3d95 > 500)  warnings.push(`[병목] P3 피드상세 p95=${fmt(p3d95)}ms > 500ms → 댓글 전체 로드 or 리포스트 카운트`);
    if (p3c95 > 500)  warnings.push(`[병목] P3 댓글목록 p95=${fmt(p3c95)}ms > 500ms → 인덱스 미스 or N+1`);
    if (p4p95 > 300)  warnings.push(`[병목] P4 좋아요 p95=${fmt(p4p95)}ms > 300ms → Redis Lua 경합 or Stream 지연`);
    if (p5r95 > 1000) warnings.push(`[병목] P5 읽기 p95=${fmt(p5r95)}ms > 1000ms → DB 커넥션풀 포화`);
    if (p5w95 > 500)  warnings.push(`[병목] P5 쓰기 p95=${fmt(p5w95)}ms > 500ms → 트랜잭션 경합`);
    if (p6p95 > 2000) warnings.push(`[병목] P6 딥페이지 p95=${fmt(p6p95)}ms > 2000ms → OFFSET 기반 페이지네이션 한계`);

    const p1rate = val(m.p1_success_rate, 'rate');
    const p4rate = val(m.p4_success_rate, 'rate');
    const p5rate = val(m.p5_success_rate, 'rate');
    if (p1rate < 0.90) warnings.push(`[경고] P1 성공률 ${pct(p1rate)}% < 90%`);
    if (p4rate < 0.90) warnings.push(`[경고] P4 성공률 ${pct(p4rate)}% < 90%`);
    if (p5rate < 0.85) warnings.push(`[경고] P5 성공률 ${pct(p5rate)}% < 85%`);

    if (warnings.length > 0) {
        console.log('\n병목점 탐지 결과:');
        warnings.forEach(w => console.log(`  ${w}`));
    } else {
        console.log('\n주요 병목점 미발견 — 모든 Phase 정상');
    }

    // 최적화 제안
    console.log('\n── 병목 대응 최적화 포인트 ──');
    if (p1p95 > 500) {
        console.log('  [P1] 인기피드: Materialized View 또는 Redis 캐시로 정렬 결과 사전 계산');
    }
    if (p2p95 > 300) {
        console.log('  [P2] 개인피드: resolveAccessibleClubIds 결과 캐싱 / 2회→1회 호출 통합');
    }
    if (p3d95 > 300) {
        console.log('  [P3] 피드상세: 댓글 페이지네이션 적용 / 리포스트 카운트 캐싱');
    }
    if (p4p95 > 200) {
        console.log('  [P4] 좋아요: Redis Pipeline 배치 처리 / Lua 스크립트 최적화');
    }
    if (p6p95 > 1000) {
        console.log('  [P6] 딥페이지: Cursor 기반 페이지네이션(Keyset Pagination) 전환');
    }

    return {
        'stdout': '',
    };
}
