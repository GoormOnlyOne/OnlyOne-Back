// =============================================================
// 피드 성능 부하 테스트
//
// Phase 1: 피드 조회 단독  (개인 피드 / 인기 피드 / 클럽 피드 목록)
// Phase 2: 피드 상호작용    (좋아요 토글 / 댓글 작성)
// Phase 3: 혼합 (읽기 + 쓰기 동시)
// Phase 4: 피드 상세 + 댓글 조회
// Phase 5: 스파이크 (한계점)
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL } from './lib/common.js';

// init 단계에서 매핑 파일 로드
const USER_CLUBS = JSON.parse(open('./lib/user-feed-clubs.json'));
const CLUB_FEEDS = JSON.parse(open('./lib/club-feeds.json'));

// ============================================
// 커스텀 메트릭 — Phase별 분리
// ============================================

// Phase 1: 피드 조회
const p1PersonalFeedDur   = new Trend('p1_personal_feed_duration', true);
const p1PopularFeedDur    = new Trend('p1_popular_feed_duration', true);
const p1ClubFeedListDur   = new Trend('p1_club_feed_list_duration', true);
const p1SuccessRate       = new Rate('p1_success_rate');
const p1Errors            = new Counter('p1_5xx_errors');

// Phase 2: 상호작용
const p2LikeToggleDur     = new Trend('p2_like_toggle_duration', true);
const p2CommentCreateDur  = new Trend('p2_comment_create_duration', true);
const p2SuccessRate       = new Rate('p2_success_rate');
const p2Errors            = new Counter('p2_5xx_errors');

// Phase 3: 혼합
const p3ReadDur           = new Trend('p3_read_duration', true);
const p3WriteDur          = new Trend('p3_write_duration', true);
const p3SuccessRate       = new Rate('p3_success_rate');
const p3Errors            = new Counter('p3_5xx_errors');

// Phase 4: 상세 조회
const p4FeedDetailDur     = new Trend('p4_feed_detail_duration', true);
const p4CommentListDur    = new Trend('p4_comment_list_duration', true);
const p4SuccessRate       = new Rate('p4_success_rate');
const p4Errors            = new Counter('p4_5xx_errors');

// Phase 5: 스파이크
const p5ReadDur           = new Trend('p5_read_duration', true);
const p5WriteDur          = new Trend('p5_write_duration', true);
const p5SuccessRate       = new Rate('p5_success_rate');
const p5Errors            = new Counter('p5_5xx_errors');

// 통합
const apiSuccessRate      = new Rate('api_success_rate');
const serverErrors        = new Counter('server_5xx_errors');
const dbSlowQueries       = new Counter('db_slow_queries');

// ============================================
// 테스트 유저 (1~1000)
// ============================================
const TEST_USERS_COUNT = 1000;

function getUser(vuId) {
    const userId = ((vuId - 1) % TEST_USERS_COUNT) + 1;
    return {
        userId,
        kakaoId: 10000000 + userId,
        status: 'ACTIVE',
        role: 'ROLE_USER',
    };
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

function recordResult(res, elapsed, successRate, errCounter, slowThreshold) {
    const ok = res.status >= 200 && res.status < 400;
    successRate.add(ok ? 1 : 0);
    apiSuccessRate.add(ok ? 1 : 0);
    if (res.status >= 500) { errCounter.add(1); serverErrors.add(1); }
    if (elapsed > (slowThreshold || 300)) dbSlowQueries.add(1);
}

// ============================================
// 시나리오 (5-Phase, 총 ~9분)
// ============================================
export const options = {
    scenarios: {
        phase1_read: {
            executor: 'ramping-vus',
            exec: 'feedReadTest',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 30 },
                { duration: '30s', target: 100 },
                { duration: '30s', target: 300 },
                { duration: '30s', target: 300 },
                { duration: '20s', target: 0 },
            ],
            startTime: '0s',
            tags: { phase: 'read_only' },
        },
        phase2_interaction: {
            executor: 'ramping-vus',
            exec: 'feedInteractionTest',
            startVUs: 0,
            stages: [
                { duration: '20s', target: 30 },
                { duration: '30s', target: 100 },
                { duration: '30s', target: 200 },
                { duration: '20s', target: 0 },
            ],
            startTime: '2m30s',
            tags: { phase: 'interaction' },
        },
        phase3_mixed: {
            executor: 'ramping-vus',
            exec: 'mixedTest',
            startVUs: 0,
            stages: [
                { duration: '20s', target: 50 },
                { duration: '40s', target: 150 },
                { duration: '30s', target: 300 },
                { duration: '20s', target: 0 },
            ],
            startTime: '4m20s',
            tags: { phase: 'mixed' },
        },
        phase4_detail: {
            executor: 'ramping-vus',
            exec: 'feedDetailTest',
            startVUs: 0,
            stages: [
                { duration: '20s', target: 50 },
                { duration: '30s', target: 150 },
                { duration: '30s', target: 300 },
                { duration: '20s', target: 0 },
            ],
            startTime: '6m20s',
            tags: { phase: 'detail' },
        },
        phase5_spike: {
            executor: 'ramping-vus',
            exec: 'spikeTest',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 100 },
                { duration: '10s', target: 400 },
                { duration: '30s', target: 400 },
                { duration: '10s', target: 0 },
            ],
            startTime: '8m10s',
            tags: { phase: 'spike' },
        },
    },

    thresholds: {
        'p1_personal_feed_duration':  ['p(95)<500'],
        'p1_popular_feed_duration':   ['p(95)<500'],
        'p1_club_feed_list_duration': ['p(95)<300'],
        'p2_like_toggle_duration':    ['p(95)<300'],
        'p2_comment_create_duration': ['p(95)<500'],
        'p4_feed_detail_duration':    ['p(95)<500'],
        'p1_success_rate':            ['rate>0.95'],
        'p2_success_rate':            ['rate>0.95'],
        'p3_success_rate':            ['rate>0.90'],
    },
};

// ============================================
// setup
// ============================================
export function setup() {
    console.log('=== 피드 성능 부하 테스트 시작 ===');
    console.log(`BASE_URL: ${BASE_URL}`);
    console.log('Phase 1 (0~2:20)  : 피드 조회 단독    → 최대 300 VU');
    console.log('Phase 2 (2:30~4:10): 좋아요/댓글       → 최대 200 VU');
    console.log('Phase 3 (4:20~6:10): 혼합 부하         → 최대 300 VU');
    console.log('Phase 4 (6:20~8:00): 피드 상세+댓글    → 최대 300 VU');
    console.log('Phase 5 (8:10~9:10): 스파이크           → 400 VU');
    console.log('=========================================');

    const health = http.get(`${BASE_URL}/actuator/health`);
    if (health.status !== 200) {
        console.error('서버 헬스체크 실패!');
    }

    const totalUsers = Object.keys(USER_CLUBS).length;
    const totalClubMappings = Object.values(USER_CLUBS).reduce((sum, c) => sum + c.length, 0);
    const totalClubs = Object.keys(CLUB_FEEDS).length;
    console.log(`유저-클럽 매핑: ${totalUsers}명, 총 ${totalClubMappings}건`);
    console.log(`클럽-피드 매핑: ${totalClubs}개 클럽`);
}

// ============================================
// Phase 1: 피드 조회 테스트
// ============================================
export function feedReadTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const r = Math.random();
    if (r < 0.35) {
        // 개인 피드 (최신)
        const page = Math.floor(Math.random() * 3);
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds?page=${page}&limit=20`, {
            headers: hdrs,
            tags: { name: 'personal_feed' },
        });
        const elapsed = Date.now() - start;
        p1PersonalFeedDur.add(elapsed);
        recordResult(res, elapsed, p1SuccessRate, p1Errors, 500);
        check(res, { 'P1 PersonalFeed 200': (r) => r.status === 200 });

    } else if (r < 0.65) {
        // 인기 피드
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&limit=20`, {
            headers: hdrs,
            tags: { name: 'popular_feed' },
        });
        const elapsed = Date.now() - start;
        p1PopularFeedDur.add(elapsed);
        recordResult(res, elapsed, p1SuccessRate, p1Errors, 500);
        check(res, { 'P1 PopularFeed 200': (r) => r.status === 200 });

    } else {
        // 클럽 피드 목록
        const clubId = getClubForUser(user.userId);
        if (!clubId) { p1SuccessRate.add(1); sleep(0.1); return; }
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=20`, {
            headers: hdrs,
            tags: { name: 'club_feed_list' },
        });
        const elapsed = Date.now() - start;
        p1ClubFeedListDur.add(elapsed);
        recordResult(res, elapsed, p1SuccessRate, p1Errors, 300);
        check(res, { 'P1 ClubFeedList 200': (r) => r.status === 200 });
    }

    sleep(Math.random() * 0.3 + 0.1);
}

// ============================================
// Phase 2: 좋아요/댓글 테스트
// ============================================
export function feedInteractionTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const clubId = getClubForUser(user.userId);
    if (!clubId) { sleep(0.2); return; }
    const feedId = getFeedInClub(clubId);
    if (!feedId) { sleep(0.2); return; }

    if (Math.random() < 0.6) {
        // 좋아요 토글
        const start = Date.now();
        const res = http.put(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`, null, {
            headers: hdrs,
            tags: { name: 'like_toggle' },
        });
        const elapsed = Date.now() - start;
        p2LikeToggleDur.add(elapsed);
        recordResult(res, elapsed, p2SuccessRate, p2Errors, 300);
        check(res, { 'P2 LikeToggle 200': (r) => r.status === 200 });
    } else {
        // 댓글 작성
        const start = Date.now();
        const payload = JSON.stringify({
            content: `k6 comment by user${user.userId}`,
        });
        const res = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/comments`, payload, {
            headers: hdrs,
            tags: { name: 'comment_create' },
        });
        const elapsed = Date.now() - start;
        p2CommentCreateDur.add(elapsed);
        recordResult(res, elapsed, p2SuccessRate, p2Errors, 500);
        check(res, { 'P2 CommentCreate 201': (r) => r.status === 201 });
    }

    sleep(Math.random() * 0.3 + 0.2);
}

// ============================================
// Phase 3: 혼합 테스트
// ============================================
export function mixedTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const r = Math.random();
    if (r < 0.30) {
        // 개인 피드 조회
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds?page=0&limit=20`, {
            headers: hdrs,
            tags: { name: 'personal_feed' },
        });
        const elapsed = Date.now() - start;
        p3ReadDur.add(elapsed);
        recordResult(res, elapsed, p3SuccessRate, p3Errors, 500);

    } else if (r < 0.55) {
        // 클럽 피드 목록
        const clubId = getClubForUser(user.userId);
        if (!clubId) { p3SuccessRate.add(1); sleep(0.1); return; }
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=20`, {
            headers: hdrs,
            tags: { name: 'club_feed_list' },
        });
        const elapsed = Date.now() - start;
        p3ReadDur.add(elapsed);
        recordResult(res, elapsed, p3SuccessRate, p3Errors, 300);

    } else if (r < 0.80) {
        // 좋아요 토글
        const clubId = getClubForUser(user.userId);
        if (!clubId) { p3SuccessRate.add(1); sleep(0.1); return; }
        const feedId = getFeedInClub(clubId);
        if (!feedId) { p3SuccessRate.add(1); sleep(0.1); return; }
        const start = Date.now();
        const res = http.put(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`, null, {
            headers: hdrs,
            tags: { name: 'like_toggle' },
        });
        const elapsed = Date.now() - start;
        p3WriteDur.add(elapsed);
        recordResult(res, elapsed, p3SuccessRate, p3Errors, 300);

    } else {
        // 댓글 작성
        const clubId = getClubForUser(user.userId);
        if (!clubId) { p3SuccessRate.add(1); sleep(0.1); return; }
        const feedId = getFeedInClub(clubId);
        if (!feedId) { p3SuccessRate.add(1); sleep(0.1); return; }
        const start = Date.now();
        const payload = JSON.stringify({
            content: `k6 mixed comment user${user.userId}`,
        });
        const res = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/comments`, payload, {
            headers: hdrs,
            tags: { name: 'comment_create' },
        });
        const elapsed = Date.now() - start;
        p3WriteDur.add(elapsed);
        recordResult(res, elapsed, p3SuccessRate, p3Errors, 500);
    }

    sleep(Math.random() * 0.3 + 0.1);
}

// ============================================
// Phase 4: 피드 상세 + 댓글 조회
// ============================================
export function feedDetailTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const clubId = getClubForUser(user.userId);
    if (!clubId) { sleep(0.2); return; }
    const feedId = getFeedInClub(clubId);
    if (!feedId) { sleep(0.2); return; }

    if (Math.random() < 0.6) {
        // 피드 상세
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}`, {
            headers: hdrs,
            tags: { name: 'feed_detail' },
        });
        const elapsed = Date.now() - start;
        p4FeedDetailDur.add(elapsed);
        recordResult(res, elapsed, p4SuccessRate, p4Errors, 500);
        check(res, { 'P4 FeedDetail 200': (r) => r.status === 200 });
    } else {
        // 댓글 목록
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds/${feedId}/comments?page=0&limit=20`, {
            headers: hdrs,
            tags: { name: 'comment_list' },
        });
        const elapsed = Date.now() - start;
        p4CommentListDur.add(elapsed);
        recordResult(res, elapsed, p4SuccessRate, p4Errors, 300);
        check(res, { 'P4 CommentList 200': (r) => r.status === 200 });
    }

    sleep(Math.random() * 0.3 + 0.1);
}

// ============================================
// Phase 5: 스파이크 테스트
// ============================================
export function spikeTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const r = Math.random();
    if (r < 0.35) {
        // 개인 피드
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds?page=0&limit=20`, {
            headers: hdrs,
            tags: { name: 'personal_feed' },
        });
        const elapsed = Date.now() - start;
        p5ReadDur.add(elapsed);
        recordResult(res, elapsed, p5SuccessRate, p5Errors, 500);
    } else if (r < 0.60) {
        // 인기 피드
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&limit=20`, {
            headers: hdrs,
            tags: { name: 'popular_feed' },
        });
        const elapsed = Date.now() - start;
        p5ReadDur.add(elapsed);
        recordResult(res, elapsed, p5SuccessRate, p5Errors, 500);
    } else if (r < 0.85) {
        // 좋아요
        const clubId = getClubForUser(user.userId);
        if (!clubId) { p5SuccessRate.add(1); sleep(0.1); return; }
        const feedId = getFeedInClub(clubId);
        if (!feedId) { p5SuccessRate.add(1); sleep(0.1); return; }
        const start = Date.now();
        const res = http.put(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}/likes`, null, {
            headers: hdrs,
            tags: { name: 'like_toggle' },
        });
        const elapsed = Date.now() - start;
        p5WriteDur.add(elapsed);
        recordResult(res, elapsed, p5SuccessRate, p5Errors, 300);
    } else {
        // 피드 상세
        const clubId = getClubForUser(user.userId);
        if (!clubId) { p5SuccessRate.add(1); sleep(0.1); return; }
        const feedId = getFeedInClub(clubId);
        if (!feedId) { p5SuccessRate.add(1); sleep(0.1); return; }
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds/${feedId}`, {
            headers: hdrs,
            tags: { name: 'feed_detail' },
        });
        const elapsed = Date.now() - start;
        p5ReadDur.add(elapsed);
        recordResult(res, elapsed, p5SuccessRate, p5Errors, 500);
    }

    sleep(Math.random() * 0.2);
}

// ============================================
// 결과 요약
// ============================================
export function handleSummary(data) {
    const m = data.metrics;
    const val = (metric, key) => metric?.values?.[key];
    const fmt = (v) => v != null ? v.toFixed(0) : '-';
    const pct = (v) => v != null ? (v * 100).toFixed(1) : '-';

    const sections = [
        {
            name: 'Phase 1: 피드 조회 (최대 300 VU)',
            personal: val(m.p1_personal_feed_duration, 'p(95)'),
            personalAvg: val(m.p1_personal_feed_duration, 'avg'),
            popular: val(m.p1_popular_feed_duration, 'p(95)'),
            popularAvg: val(m.p1_popular_feed_duration, 'avg'),
            clubList: val(m.p1_club_feed_list_duration, 'p(95)'),
            clubListAvg: val(m.p1_club_feed_list_duration, 'avg'),
            rate: val(m.p1_success_rate, 'rate'),
            err5xx: val(m.p1_5xx_errors, 'count'),
        },
        {
            name: 'Phase 2: 좋아요/댓글 (최대 200 VU)',
            like: val(m.p2_like_toggle_duration, 'p(95)'),
            likeAvg: val(m.p2_like_toggle_duration, 'avg'),
            comment: val(m.p2_comment_create_duration, 'p(95)'),
            commentAvg: val(m.p2_comment_create_duration, 'avg'),
            rate: val(m.p2_success_rate, 'rate'),
            err5xx: val(m.p2_5xx_errors, 'count'),
        },
        {
            name: 'Phase 3: 혼합 (최대 300 VU)',
            read: val(m.p3_read_duration, 'p(95)'),
            write: val(m.p3_write_duration, 'p(95)'),
            rate: val(m.p3_success_rate, 'rate'),
            err5xx: val(m.p3_5xx_errors, 'count'),
        },
        {
            name: 'Phase 4: 피드 상세 (최대 300 VU)',
            detail: val(m.p4_feed_detail_duration, 'p(95)'),
            detailAvg: val(m.p4_feed_detail_duration, 'avg'),
            commentList: val(m.p4_comment_list_duration, 'p(95)'),
            commentListAvg: val(m.p4_comment_list_duration, 'avg'),
            rate: val(m.p4_success_rate, 'rate'),
            err5xx: val(m.p4_5xx_errors, 'count'),
        },
        {
            name: 'Phase 5: 스파이크 (최대 400 VU)',
            read: val(m.p5_read_duration, 'p(95)'),
            write: val(m.p5_write_duration, 'p(95)'),
            rate: val(m.p5_success_rate, 'rate'),
            err5xx: val(m.p5_5xx_errors, 'count'),
        },
    ];

    console.log('\n╔══════════════════════════════════════════════════════════════╗');
    console.log('║              피드 성능 부하 테스트 결과 (Phase별)               ║');
    console.log('╠══════════════════════════════════════════════════════════════╣');

    // Phase 1
    const s1 = sections[0];
    console.log(`║  ── ${s1.name.padEnd(42)}──  ║`);
    console.log(`║    개인피드    p95: ${fmt(s1.personal).padStart(7)}ms  avg: ${fmt(s1.personalAvg).padStart(7)}ms          ║`);
    console.log(`║    인기피드    p95: ${fmt(s1.popular).padStart(7)}ms  avg: ${fmt(s1.popularAvg).padStart(7)}ms          ║`);
    console.log(`║    클럽피드    p95: ${fmt(s1.clubList).padStart(7)}ms  avg: ${fmt(s1.clubListAvg).padStart(7)}ms          ║`);
    console.log(`║    성공률: ${pct(s1.rate).padStart(5)}%  │  5xx: ${String(s1.err5xx || 0).padStart(5)}건                     ║`);
    console.log('║                                                              ║');

    // Phase 2
    const s2 = sections[1];
    console.log(`║  ── ${s2.name.padEnd(42)}──  ║`);
    console.log(`║    좋아요토글  p95: ${fmt(s2.like).padStart(7)}ms  avg: ${fmt(s2.likeAvg).padStart(7)}ms          ║`);
    console.log(`║    댓글작성    p95: ${fmt(s2.comment).padStart(7)}ms  avg: ${fmt(s2.commentAvg).padStart(7)}ms          ║`);
    console.log(`║    성공률: ${pct(s2.rate).padStart(5)}%  │  5xx: ${String(s2.err5xx || 0).padStart(5)}건                     ║`);
    console.log('║                                                              ║');

    // Phase 3
    const s3 = sections[2];
    console.log(`║  ── ${s3.name.padEnd(42)}──  ║`);
    console.log(`║    읽기        p95: ${fmt(s3.read).padStart(7)}ms                              ║`);
    console.log(`║    쓰기        p95: ${fmt(s3.write).padStart(7)}ms                              ║`);
    console.log(`║    성공률: ${pct(s3.rate).padStart(5)}%  │  5xx: ${String(s3.err5xx || 0).padStart(5)}건                     ║`);
    console.log('║                                                              ║');

    // Phase 4
    const s4 = sections[3];
    console.log(`║  ── ${s4.name.padEnd(42)}──  ║`);
    console.log(`║    피드상세    p95: ${fmt(s4.detail).padStart(7)}ms  avg: ${fmt(s4.detailAvg).padStart(7)}ms          ║`);
    console.log(`║    댓글목록    p95: ${fmt(s4.commentList).padStart(7)}ms  avg: ${fmt(s4.commentListAvg).padStart(7)}ms          ║`);
    console.log(`║    성공률: ${pct(s4.rate).padStart(5)}%  │  5xx: ${String(s4.err5xx || 0).padStart(5)}건                     ║`);
    console.log('║                                                              ║');

    // Phase 5
    const s5 = sections[4];
    console.log(`║  ── ${s5.name.padEnd(42)}──  ║`);
    console.log(`║    읽기        p95: ${fmt(s5.read).padStart(7)}ms                              ║`);
    console.log(`║    쓰기        p95: ${fmt(s5.write).padStart(7)}ms                              ║`);
    console.log(`║    성공률: ${pct(s5.rate).padStart(5)}%  │  5xx: ${String(s5.err5xx || 0).padStart(5)}건                     ║`);

    // 통합
    const allRate = val(m.api_success_rate, 'rate') || 0;
    const allSlow = val(m.db_slow_queries, 'count') || 0;
    const all5xx = val(m.server_5xx_errors, 'count') || 0;
    console.log('╠══════════════════════════════════════════════════════════════╣');
    console.log(`║  API 성공률: ${pct(allRate).padStart(5)}% │ 슬로우: ${String(allSlow).padStart(5)}건 │ 5xx: ${String(all5xx).padStart(5)}건  ║`);
    console.log('╚══════════════════════════════════════════════════════════════╝');

    // 병목 경고
    const warnings = [];
    if (s1.personal > 500)  warnings.push(`[P1] 개인피드 p95 ${fmt(s1.personal)}ms > 500ms`);
    if (s1.popular > 500)   warnings.push(`[P1] 인기피드 p95 ${fmt(s1.popular)}ms > 500ms`);
    if (s1.clubList > 300)  warnings.push(`[P1] 클럽피드 p95 ${fmt(s1.clubList)}ms > 300ms`);
    if (s2.like > 300)      warnings.push(`[P2] 좋아요 p95 ${fmt(s2.like)}ms > 300ms`);
    if (s2.comment > 500)   warnings.push(`[P2] 댓글 p95 ${fmt(s2.comment)}ms > 500ms`);
    if (s4.detail > 500)    warnings.push(`[P4] 피드상세 p95 ${fmt(s4.detail)}ms > 500ms`);
    if (s1.rate < 0.95)     warnings.push(`[P1] 성공률 ${pct(s1.rate)}% < 95%`);
    if (s2.rate < 0.95)     warnings.push(`[P2] 성공률 ${pct(s2.rate)}% < 95%`);

    if (warnings.length > 0) {
        console.log('\n병목점 경고:');
        warnings.forEach(w => console.log(`  ${w}`));
    } else {
        console.log('\n주요 병목점 미발견 — 모든 Phase 정상');
    }

    return {
        'stdout': '',
        '/results/feed-perf-result.json': JSON.stringify(data, null, 2),
    };
}
