import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { generateJWT, BASE_URL, headers as authHeaders } from './lib/common.js';

// ============================================
// Club 도메인 병목 탐지 테스트 (~8분)
// 전략: VU별 고유 유저 + 멤버십 상태 추적 → 4xx 최소화
// ============================================

const errorRate = new Rate('errors');
const clubDetailDuration = new Trend('club_detail_duration', true);
const clubJoinDuration = new Trend('club_join_duration', true);
const clubLeaveDuration = new Trend('club_leave_duration', true);
const memberCountContention = new Rate('member_count_contention');
const joinServerErrors = new Counter('join_server_errors');
const leaveServerErrors = new Counter('leave_server_errors');
const detailServerErrors = new Counter('detail_server_errors');

export const options = {
    scenarios: {
        // Phase 1: Hot Club (10개 집중) — 200 VU, 2.5분
        hot_club_contention: {
            executor: 'ramping-vus',
            exec: 'hotClubContention',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 200 },
                { duration: '1m30s', target: 200 },
                { duration: '30s', target: 0 },
            ],
            startTime: '0s',
            gracefulRampDown: '10s',
        },
        // Phase 2: Wide Club (10000개 분산) — 300 VU, 2.5분
        wide_club_load: {
            executor: 'ramping-vus',
            exec: 'wideClubLoad',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 300 },
                { duration: '1m30s', target: 300 },
                { duration: '30s', target: 0 },
            ],
            startTime: '3m',
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
            startTime: '6m',
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        club_detail_duration: ['p(95)<500', 'p(50)<100'],
        club_join_duration: ['p(95)<500', 'p(50)<200'],
        club_leave_duration: ['p(95)<500', 'p(50)<200'],
        errors: ['rate<0.1'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('club_users', function () {
    const users = [];
    for (let i = 1; i <= 2000; i++) {
        users.push({ userId: i, kakaoId: 10000000 + i, status: 'ACTIVE', role: 'ROLE_USER' });
    }
    return users;
});

// ============================================
// 유틸리티
// ============================================
function reqOpts(token) {
    return { headers: authHeaders(token) };
}

// VU별 멤버십 상태 캐시 (clubId → 'MEMBER'|'LEADER'|false)
const membership = {};

// 클럽 상세 조회로 현재 멤버 여부 감지 (VU당 클럽별 1회)
function detectMembership(clubId, opts) {
    if (membership[clubId] !== undefined) return;
    const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, opts);
    clubDetailDuration.add(res.timings.duration);
    if (res.status === 200) {
        try {
            const body = JSON.parse(res.body);
            const role = body.data && body.data.clubRole;
            membership[clubId] = (role && role !== 'GUEST') ? role : false;
        } catch (e) {
            membership[clubId] = false;
        }
    } else {
        membership[clubId] = false;
    }
}

// 상태 기반 Join↔Leave 토글
function toggleMembership(clubId, opts) {
    // LEADER는 탈퇴 불가 → detail만 측정
    if (membership[clubId] === 'LEADER') return;

    if (membership[clubId]) {
        // 현재 멤버 → 탈퇴
        const res = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, opts);
        clubLeaveDuration.add(res.timings.duration);
        errorRate.add(res.status >= 500);
        if (res.status >= 500) leaveServerErrors.add(1);
        if (res.status === 200 || res.status === 400) membership[clubId] = false;
    } else {
        // 비멤버 → 가입
        const res = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/join`, null, opts);
        clubJoinDuration.add(res.timings.duration);
        errorRate.add(res.status >= 500);
        memberCountContention.add(res.status >= 500 ? 1 : 0);
        if (res.status >= 500) joinServerErrors.add(1);
        if (res.status === 200 || res.status === 400) membership[clubId] = 'MEMBER';
    }
}

// ============================================
// Phase 1: Hot Club (10개 집중, memberCount 경합)
// ============================================
export function hotClubContention() {
    const user = testUsers[(__VU - 1) % testUsers.length];
    const token = generateJWT(user);
    const opts = reqOpts(token);
    const hotClubId = (__VU % 10) + 1;

    detectMembership(hotClubId, opts);

    group('hot_club', () => {
        // 30%: Detail 조회
        if (Math.random() < 0.3) {
            const res = http.get(`${BASE_URL}/api/v1/clubs/${hotClubId}`, opts);
            check(res, { 'hot detail: success': (r) => r.status === 200 });
            clubDetailDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) detailServerErrors.add(1);
        }
        // Join↔Leave 토글
        toggleMembership(hotClubId, opts);
    });

    sleep(0.3);
}

// ============================================
// Phase 2: Wide Club (10000개 분산)
// ============================================
export function wideClubLoad() {
    const user = testUsers[(__VU - 1) % testUsers.length];
    const token = generateJWT(user);
    const opts = reqOpts(token);
    const clubId = ((__VU * 7 + __ITER * 13) % 200000) + 1;

    detectMembership(clubId, opts);

    group('wide_club', () => {
        if (Math.random() < 0.3) {
            const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, opts);
            check(res, { 'wide detail: success': (r) => r.status === 200 });
            clubDetailDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) detailServerErrors.add(1);
        }
        toggleMembership(clubId, opts);
    });

    sleep(0.3);
}

// ============================================
// Phase 3: 고부하 혼합 (400 VU)
// ============================================
export function highLoadMixed() {
    const user = testUsers[(__VU - 1) % testUsers.length];
    const token = generateJWT(user);
    const opts = reqOpts(token);
    const action = Math.random();

    group('high_load', () => {
        if (action < 0.35) {
            // 35%: Detail
            const clubId = action < 0.2
                ? (__VU % 20) + 1
                : ((__VU * 7 + __ITER * 13) % 200000) + 1;
            const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, opts);
            clubDetailDuration.add(res.timings.duration);
            errorRate.add(res.status >= 500);
            if (res.status >= 500) detailServerErrors.add(1);
        } else {
            // 65%: Join↔Leave 토글
            const clubId = action < 0.65
                ? (__VU % 20) + 1
                : ((__VU * 7 + __ITER * 13) % 200000) + 1;
            detectMembership(clubId, opts);
            toggleMembership(clubId, opts);
        }
    });

    sleep(0.2);
}

// ============================================
// Lifecycle
// ============================================
export function setup() {
    console.log('=== Club Bottleneck Test (~8min) ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('');
    console.log('Phase 1 (0-2.5m):   Hot Club (10 clubs) — 0→200 VU, sleep 0.3s');
    console.log('Phase 2 (3-5.5m):   Wide Club (200000 clubs) — 0→300 VU, sleep 0.3s');
    console.log('Phase 3 (6-8m):     High Load Mixed — 0→400 VU, sleep 0.2s');
    console.log('');
    console.log('Strategy: VU별 고유 유저 + 상태 추적 (4xx 최소화)');
    console.log('==============================================');

    // Smoke test
    const user = testUsers[999];
    const token = generateJWT(user);
    const opts = reqOpts(token);
    const res = http.get(`${BASE_URL}/api/v1/clubs/50000`, opts);
    console.log(`Smoke (detail club 5000): status=${res.status}, ${res.timings.duration.toFixed(0)}ms`);
}

export function teardown() {
    console.log('');
    console.log('=== Club Bottleneck Test Complete ===');
    console.log('Key metrics:');
    console.log('  club_detail_duration — p50 vs p95');
    console.log('  club_join_duration — lock contention');
    console.log('  club_leave_duration — lock contention');
    console.log('  member_count_contention — 500 error rate');
    console.log('=====================================');
}
