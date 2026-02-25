// =============================================================
// 알림/SSE 병목점 탐지 부하 테스트
//
// Phase 1: SSE 연결 단독 (커넥션 관리 / Virtual Thread 병목)
// Phase 2: REST API 단독 (DB 커넥션 풀 / 쿼리 병목)
// Phase 3: SSE + REST 혼합 (리소스 경합)
// Phase 4: 스파이크 (한계점 돌파)
//
// 유의: SseAuthenticationFilter 제거됨
//       → JwtAuthenticationFilter에서 SSE 경로는 쿠키 fallback 지원
//       → k6에서는 Authorization 헤더로 JWT 전달
// =============================================================

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend, Gauge } from 'k6/metrics';
import {
    generateJWT, headers, sseHeaders,
    BASE_URL, parseSSEEvents, fetchNotificationIds,
} from './lib/common.js';

// ============================================
// 커스텀 메트릭 — Phase별 분리
// ============================================

// SSE (통합 — Phase 1에서만 주로 사용)
const sseConnectDuration  = new Trend('sse_connect_duration', true);
const sseConnectErrors    = new Counter('sse_connect_errors');
const sseConnectSuccess   = new Rate('sse_connect_success_rate');

// ── Phase 2: REST 단독 메트릭 ──
const p2ListDuration      = new Trend('p2_list_duration', true);
const p2UnreadDuration    = new Trend('p2_unread_duration', true);
const p2ReadDuration      = new Trend('p2_read_duration', true);
const p2ReadAllDuration   = new Trend('p2_read_all_duration', true);
const p2DeleteDuration    = new Trend('p2_delete_duration', true);
const p2SuccessRate       = new Rate('p2_success_rate');
const p2Errors            = new Counter('p2_5xx_errors');

// ── Phase 3: 혼합 메트릭 ──
const p3ListDuration      = new Trend('p3_list_duration', true);
const p3UnreadDuration    = new Trend('p3_unread_duration', true);
const p3ReadDuration      = new Trend('p3_read_duration', true);
const p3ReadAllDuration   = new Trend('p3_read_all_duration', true);
const p3SuccessRate       = new Rate('p3_success_rate');
const p3Errors            = new Counter('p3_5xx_errors');

// ── Phase 4: 스파이크 메트릭 ──
const p4ListDuration      = new Trend('p4_list_duration', true);
const p4UnreadDuration    = new Trend('p4_unread_duration', true);
const p4SuccessRate       = new Rate('p4_success_rate');
const p4Errors            = new Counter('p4_5xx_errors');

// 통합 (전체 집계용)
const apiListDuration     = new Trend('api_list_duration', true);
const apiUnreadDuration   = new Trend('api_unread_count_duration', true);
const apiReadDuration     = new Trend('api_mark_read_duration', true);
const apiReadAllDuration  = new Trend('api_mark_all_read_duration', true);
const apiDeleteDuration   = new Trend('api_delete_duration', true);
const apiErrors           = new Counter('api_errors');
const apiSuccessRate      = new Rate('api_success_rate');
const dbSlowQueries       = new Counter('db_slow_queries');
const serverErrors        = new Counter('server_5xx_errors');

// ============================================
// 테스트 사용자 매핑
// ============================================
const TEST_USERS_START = 1;
const TEST_USERS_COUNT = 1000;

function getUser(vuId) {
    const idx = (vuId - 1) % TEST_USERS_COUNT;
    const userId = TEST_USERS_START + idx;
    return {
        userId,
        kakaoId: 10000000 + userId,
        status: 'ACTIVE',
        role: 'ROLE_USER',
    };
}

// ============================================
// 시나리오 (4-Phase, 총 ~9분)
// ============================================
export const options = {
    scenarios: {
        phase1_sse: {
            executor: 'ramping-vus',
            exec: 'sseConnectionTest',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50 },
                { duration: '30s', target: 100 },
                { duration: '30s', target: 200 },
                { duration: '30s', target: 300 },
                { duration: '20s', target: 0 },
            ],
            startTime: '0s',
            tags: { phase: 'sse_only' },
        },
        phase2_rest: {
            executor: 'ramping-vus',
            exec: 'restApiTest',
            startVUs: 0,
            stages: [
                { duration: '20s', target: 30 },
                { duration: '30s', target: 80 },
                { duration: '30s', target: 150 },
                { duration: '30s', target: 250 },
                { duration: '20s', target: 0 },
            ],
            startTime: '2m40s',
            tags: { phase: 'rest_only' },
        },
        phase3_combined: {
            executor: 'ramping-vus',
            exec: 'combinedTest',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50 },
                { duration: '60s', target: 150 },
                { duration: '30s', target: 0 },
            ],
            startTime: '5m20s',
            tags: { phase: 'combined' },
        },
        phase4_spike: {
            executor: 'ramping-vus',
            exec: 'spikeTest',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 100 },
                { duration: '10s', target: 400 },
                { duration: '30s', target: 400 },
                { duration: '10s', target: 0 },
            ],
            startTime: '7m30s',
            tags: { phase: 'spike' },
        },
    },

    thresholds: {
        // Phase 2 (REST 단독) 기준으로 threshold 판정
        'p2_list_duration':     ['p(95)<500'],
        'p2_unread_duration':   ['p(95)<200'],
        'p2_read_duration':     ['p(95)<300'],
        'p2_read_all_duration': ['p(95)<1000'],
        'p2_success_rate':      ['rate>0.95'],
        'sse_connect_success_rate': ['rate>0.90'],
    },
};

// ============================================
// setup
// ============================================
export function setup() {
    console.log('=== 알림/SSE 병목점 탐지 시작 ===');
    console.log(`BASE_URL: ${BASE_URL}`);
    console.log('Phase 1 (0~2:40)  : SSE 연결 단독 → 최대 300 VU');
    console.log('Phase 2 (2:40~5:20): REST API 단독 → 최대 250 VU');
    console.log('Phase 3 (5:20~7:30): 혼합 부하     → 최대 150 VU');
    console.log('Phase 4 (7:30~8:30): 스파이크       → 400 VU 급증');
    console.log('========================================');

    const health = http.get(`${BASE_URL}/actuator/health`);
    if (health.status !== 200) {
        console.error('서버 헬스체크 실패!');
    }
}

// ============================================
// Phase 1: SSE 연결 테스트
// ============================================
export function sseConnectionTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = sseHeaders(token);

    group('SSE 연결', () => {
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/sse/subscribe`, {
            headers: hdrs,
            timeout: '5s',
            responseType: 'text',
            tags: { name: 'sse_subscribe' },
        });
        const elapsed = Date.now() - start;
        sseConnectDuration.add(elapsed);

        const ok = res.status === 200 || res.status === 0;
        sseConnectSuccess.add(ok ? 1 : 0);
        if (!ok) {
            sseConnectErrors.add(1);
            if (res.status >= 500) serverErrors.add(1);
        }

        check(res, {
            'SSE 연결 성공': (r) => r.status === 200 || r.status === 0,
            'connected 이벤트 수신': (r) => {
                if (!r.body) return false;
                return r.body.includes('event:connected') || r.body.includes('connected');
            },
            '3초 이내 연결': () => elapsed < 3000,
        });

        if (res.body) {
            const events = parseSSEEvents(res.body);
            const notifs = events.filter(e => e.name === 'notification');
            if (notifs.length > 0) {
                check(null, { '놓친 알림 복구됨': () => true });
            }
        }
    });

    sleep(Math.random() * 2 + 1);
}

// ============================================
// Phase 2: REST API 단독 테스트
// ============================================
export function restApiTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const r = Math.random();
    if (r < 0.35)       testListP2(hdrs, token, user);
    else if (r < 0.55)  testUnreadP2(hdrs);
    else if (r < 0.75)  testMarkReadP2(hdrs, token);
    else if (r < 0.85)  testMarkAllReadP2(hdrs);
    else if (r < 0.95)  testDeleteP2(hdrs, token);
    else                 testAppEntryP2(hdrs);

    sleep(Math.random() * 0.5 + 0.2);
}

// ============================================
// Phase 3: 혼합 테스트
// ============================================
export function combinedTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);

    if (Math.random() < 0.3) {
        const sseHdrs = sseHeaders(token);
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/sse/subscribe`, {
            headers: sseHdrs,
            timeout: '3s',
            responseType: 'text',
            tags: { name: 'sse_subscribe' },
        });
        sseConnectDuration.add(Date.now() - start);
        sseConnectSuccess.add((res.status === 200 || res.status === 0) ? 1 : 0);
        if (res.status >= 500) serverErrors.add(1);

        sleep(0.3);
        testUnreadP3(headers(token));
    } else {
        const hdrs = headers(token);
        const r = Math.random();
        if (r < 0.4)       testListP3(hdrs, token, user);
        else if (r < 0.7)  testUnreadP3(hdrs);
        else if (r < 0.85) testMarkReadP3(hdrs, token);
        else                testMarkAllReadP3(hdrs);
    }

    sleep(Math.random() * 0.5 + 0.3);
}

// ============================================
// Phase 4: 스파이크 테스트
// ============================================
export function spikeTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);

    if (__VU % 3 === 0) {
        const sseHdrs = sseHeaders(token);
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/sse/subscribe`, {
            headers: sseHdrs,
            timeout: '5s',
            responseType: 'text',
            tags: { name: 'sse_subscribe_spike' },
        });
        sseConnectDuration.add(Date.now() - start);
        sseConnectSuccess.add((res.status === 200 || res.status === 0) ? 1 : 0);
        if (res.status >= 500) serverErrors.add(1);
    } else {
        const hdrs = headers(token);
        testListP4(hdrs, token, user);
        testUnreadP4(hdrs);
    }

    sleep(Math.random() * 0.3);
}

// ============================================
// Phase 2 REST 함수들 (p2_ 메트릭)
// ============================================
function testListP2(hdrs, token, user) {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
        headers: hdrs, tags: { name: 'notification_list' },
    });
    const elapsed = Date.now() - start;
    p2ListDuration.add(elapsed);
    apiListDuration.add(elapsed);

    const ok = res.status === 200;
    p2SuccessRate.add(ok ? 1 : 0);
    apiSuccessRate.add(ok ? 1 : 0);
    if (!ok) apiErrors.add(1);
    if (res.status >= 500) { serverErrors.add(1); p2Errors.add(1); }
    if (elapsed > 500) dbSlowQueries.add(1);

    check(res, {
        'P2 List 200': (r) => r.status === 200,
    });

    if (ok && Math.random() < 0.3) {
        try {
            const b = JSON.parse(res.body);
            if (b.data && b.data.nextCursor) {
                const s2 = Date.now();
                const r2 = http.get(
                    `${BASE_URL}/api/v1/notifications?size=20&cursor=${b.data.nextCursor}`,
                    { headers: hdrs, tags: { name: 'notification_list_page2' } }
                );
                const e2 = Date.now() - s2;
                p2ListDuration.add(e2);
                apiListDuration.add(e2);
                p2SuccessRate.add(r2.status === 200 ? 1 : 0);
                apiSuccessRate.add(r2.status === 200 ? 1 : 0);
            }
        } catch { /* ignore */ }
    }
}

function testUnreadP2(hdrs) {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: hdrs, tags: { name: 'notification_unread' },
    });
    const elapsed = Date.now() - start;
    p2UnreadDuration.add(elapsed);
    apiUnreadDuration.add(elapsed);

    p2SuccessRate.add(res.status === 200 ? 1 : 0);
    apiSuccessRate.add(res.status === 200 ? 1 : 0);
    if (res.status !== 200) apiErrors.add(1);
    if (res.status >= 500) { serverErrors.add(1); p2Errors.add(1); }
    if (elapsed > 200) dbSlowQueries.add(1);

    check(res, { 'P2 Unread 200': (r) => r.status === 200 });
}

function testMarkReadP2(hdrs, token) {
    const ids = fetchNotificationIds(token, 5);
    if (ids.length === 0) return;

    const targetId = ids[Math.floor(Math.random() * ids.length)];
    const start = Date.now();
    const res = http.put(`${BASE_URL}/api/v1/notifications/${targetId}/read`, null, {
        headers: hdrs, tags: { name: 'notification_read' },
    });
    const elapsed = Date.now() - start;
    p2ReadDuration.add(elapsed);
    apiReadDuration.add(elapsed);

    p2SuccessRate.add(res.status === 200 ? 1 : 0);
    apiSuccessRate.add(res.status === 200 ? 1 : 0);
    if (res.status >= 500) { serverErrors.add(1); p2Errors.add(1); }

    check(res, { 'P2 Read 200': (r) => r.status === 200 });
}

function testMarkAllReadP2(hdrs) {
    const start = Date.now();
    const res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
        headers: hdrs, tags: { name: 'notification_read_all' },
    });
    const elapsed = Date.now() - start;
    p2ReadAllDuration.add(elapsed);
    apiReadAllDuration.add(elapsed);

    p2SuccessRate.add(res.status === 200 ? 1 : 0);
    apiSuccessRate.add(res.status === 200 ? 1 : 0);
    if (res.status >= 500) { serverErrors.add(1); p2Errors.add(1); }
    if (elapsed > 1000) dbSlowQueries.add(1);

    check(res, { 'P2 ReadAll 200': (r) => r.status === 200 });
}

function testDeleteP2(hdrs, token) {
    const ids = fetchNotificationIds(token, 5);
    if (ids.length === 0) return;

    const targetId = ids[Math.floor(Math.random() * ids.length)];
    const start = Date.now();
    const res = http.del(`${BASE_URL}/api/v1/notifications/${targetId}`, null, {
        headers: hdrs, tags: { name: 'notification_delete' },
    });
    const elapsed = Date.now() - start;
    p2DeleteDuration.add(elapsed);
    apiDeleteDuration.add(elapsed);

    p2SuccessRate.add((res.status === 200 || res.status === 404) ? 1 : 0);
    apiSuccessRate.add((res.status === 200 || res.status === 404) ? 1 : 0);
    if (res.status >= 500) { serverErrors.add(1); p2Errors.add(1); }
}

function testAppEntryP2(hdrs) {
    const responses = http.batch([
        ['GET', `${BASE_URL}/api/v1/notifications?size=20`, null,
            { headers: hdrs, tags: { name: 'notification_list' } }],
        ['GET', `${BASE_URL}/api/v1/notifications/unread-count`, null,
            { headers: hdrs, tags: { name: 'notification_unread' } }],
    ]);
    for (const res of responses) {
        p2SuccessRate.add(res.status === 200 ? 1 : 0);
        apiSuccessRate.add(res.status === 200 ? 1 : 0);
        if (res.status >= 500) { serverErrors.add(1); p2Errors.add(1); }
    }
}

// ============================================
// Phase 3 REST 함수들 (p3_ 메트릭)
// ============================================
function testListP3(hdrs, token, user) {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
        headers: hdrs, tags: { name: 'notification_list' },
    });
    const elapsed = Date.now() - start;
    p3ListDuration.add(elapsed);
    apiListDuration.add(elapsed);
    p3SuccessRate.add(res.status === 200 ? 1 : 0);
    apiSuccessRate.add(res.status === 200 ? 1 : 0);
    if (res.status >= 500) { serverErrors.add(1); p3Errors.add(1); }
}

function testUnreadP3(hdrs) {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: hdrs, tags: { name: 'notification_unread' },
    });
    const elapsed = Date.now() - start;
    p3UnreadDuration.add(elapsed);
    apiUnreadDuration.add(elapsed);
    p3SuccessRate.add(res.status === 200 ? 1 : 0);
    apiSuccessRate.add(res.status === 200 ? 1 : 0);
    if (res.status >= 500) { serverErrors.add(1); p3Errors.add(1); }
}

function testMarkReadP3(hdrs, token) {
    const ids = fetchNotificationIds(token, 5);
    if (ids.length === 0) return;
    const targetId = ids[Math.floor(Math.random() * ids.length)];
    const start = Date.now();
    const res = http.put(`${BASE_URL}/api/v1/notifications/${targetId}/read`, null, {
        headers: hdrs, tags: { name: 'notification_read' },
    });
    const elapsed = Date.now() - start;
    p3ReadDuration.add(elapsed);
    apiReadDuration.add(elapsed);
    p3SuccessRate.add(res.status === 200 ? 1 : 0);
    apiSuccessRate.add(res.status === 200 ? 1 : 0);
    if (res.status >= 500) { serverErrors.add(1); p3Errors.add(1); }
}

function testMarkAllReadP3(hdrs) {
    const start = Date.now();
    const res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
        headers: hdrs, tags: { name: 'notification_read_all' },
    });
    const elapsed = Date.now() - start;
    p3ReadAllDuration.add(elapsed);
    apiReadAllDuration.add(elapsed);
    p3SuccessRate.add(res.status === 200 ? 1 : 0);
    apiSuccessRate.add(res.status === 200 ? 1 : 0);
    if (res.status >= 500) { serverErrors.add(1); p3Errors.add(1); }
}

// ============================================
// Phase 4 REST 함수들 (p4_ 메트릭)
// ============================================
function testListP4(hdrs, token, user) {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
        headers: hdrs, tags: { name: 'notification_list' },
    });
    const elapsed = Date.now() - start;
    p4ListDuration.add(elapsed);
    apiListDuration.add(elapsed);
    p4SuccessRate.add(res.status === 200 ? 1 : 0);
    apiSuccessRate.add(res.status === 200 ? 1 : 0);
    if (res.status >= 500) { serverErrors.add(1); p4Errors.add(1); }
}

function testUnreadP4(hdrs) {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: hdrs, tags: { name: 'notification_unread' },
    });
    const elapsed = Date.now() - start;
    p4UnreadDuration.add(elapsed);
    apiUnreadDuration.add(elapsed);
    p4SuccessRate.add(res.status === 200 ? 1 : 0);
    apiSuccessRate.add(res.status === 200 ? 1 : 0);
    if (res.status >= 500) { serverErrors.add(1); p4Errors.add(1); }
}

// ============================================
// 결과 요약 — Phase별 분리 출력
// ============================================
export function handleSummary(data) {
    const m = data.metrics;
    const val = (metric, key) => metric?.values?.[key];
    const fmt = (v) => v != null ? v.toFixed(0) : '-';
    const pct = (v) => v != null ? (v * 100).toFixed(1) : '-';

    const sections = [
        {
            name: 'Phase 2: REST 단독 (최대 250 VU)',
            list: val(m.p2_list_duration, 'p(95)'),
            unread: val(m.p2_unread_duration, 'p(95)'),
            read: val(m.p2_read_duration, 'p(95)'),
            readAll: val(m.p2_read_all_duration, 'p(95)'),
            rate: val(m.p2_success_rate, 'rate'),
            err5xx: val(m.p2_5xx_errors, 'count'),
            listAvg: val(m.p2_list_duration, 'avg'),
            unreadAvg: val(m.p2_unread_duration, 'avg'),
        },
        {
            name: 'Phase 3: SSE+REST 혼합 (최대 150 VU)',
            list: val(m.p3_list_duration, 'p(95)'),
            unread: val(m.p3_unread_duration, 'p(95)'),
            read: val(m.p3_read_duration, 'p(95)'),
            readAll: val(m.p3_read_all_duration, 'p(95)'),
            rate: val(m.p3_success_rate, 'rate'),
            err5xx: val(m.p3_5xx_errors, 'count'),
            listAvg: val(m.p3_list_duration, 'avg'),
            unreadAvg: val(m.p3_unread_duration, 'avg'),
        },
        {
            name: 'Phase 4: 스파이크 (최대 400 VU)',
            list: val(m.p4_list_duration, 'p(95)'),
            unread: val(m.p4_unread_duration, 'p(95)'),
            read: null,
            readAll: null,
            rate: val(m.p4_success_rate, 'rate'),
            err5xx: val(m.p4_5xx_errors, 'count'),
            listAvg: val(m.p4_list_duration, 'avg'),
            unreadAvg: val(m.p4_unread_duration, 'avg'),
        },
    ];

    const sseP95  = val(m.sse_connect_duration, 'p(95)') || 0;
    const sseRate = val(m.sse_connect_success_rate, 'rate') || 0;
    const sseErr  = val(m.sse_connect_errors, 'count') || 0;

    console.log('\n╔══════════════════════════════════════════════════════════════╗');
    console.log('║            알림/SSE 병목점 탐지 결과 (Phase별)                  ║');
    console.log('╠══════════════════════════════════════════════════════════════╣');
    console.log(`║  SSE 연결 p95: ${fmt(sseP95).padStart(6)}ms │ 성공률: ${pct(sseRate).padStart(5)}% │ 오류: ${String(sseErr).padStart(4)}건  ║`);
    console.log('╠══════════════════════════════════════════════════════════════╣');

    for (const s of sections) {
        console.log(`║  ── ${s.name.padEnd(42)}──  ║`);
        console.log(`║    목록조회  p95: ${fmt(s.list).padStart(7)}ms  avg: ${fmt(s.listAvg).padStart(7)}ms              ║`);
        console.log(`║    안읽음    p95: ${fmt(s.unread).padStart(7)}ms  avg: ${fmt(s.unreadAvg).padStart(7)}ms              ║`);
        if (s.read != null)
            console.log(`║    단건읽음  p95: ${fmt(s.read).padStart(7)}ms                                   ║`);
        if (s.readAll != null)
            console.log(`║    전체읽음  p95: ${fmt(s.readAll).padStart(7)}ms                                   ║`);
        console.log(`║    성공률: ${pct(s.rate).padStart(5)}%  │  5xx: ${String(s.err5xx || 0).padStart(5)}건                         ║`);
        console.log('║                                                              ║');
    }

    // 통합
    const allList = val(m.api_list_duration, 'p(95)') || 0;
    const allUnread = val(m.api_unread_count_duration, 'p(95)') || 0;
    const allRead = val(m.api_mark_read_duration, 'p(95)') || 0;
    const allReadAll = val(m.api_mark_all_read_duration, 'p(95)') || 0;
    const allRate = val(m.api_success_rate, 'rate') || 0;
    const allSlow = val(m.db_slow_queries, 'count') || 0;
    const all5xx = val(m.server_5xx_errors, 'count') || 0;

    console.log('╠══════════════════════════════════════════════════════════════╣');
    console.log(`║  ── 통합 (전체 Phase 합산)                                    ║`);
    console.log(`║    목록조회 p95: ${fmt(allList).padStart(7)}ms │ 안읽음 p95: ${fmt(allUnread).padStart(7)}ms          ║`);
    console.log(`║    단건읽음 p95: ${fmt(allRead).padStart(7)}ms │ 전체읽음 p95: ${fmt(allReadAll).padStart(7)}ms          ║`);
    console.log(`║    API 성공률: ${pct(allRate).padStart(5)}% │ 슬로우: ${String(allSlow).padStart(5)}건 │ 5xx: ${String(all5xx).padStart(5)}건  ║`);
    console.log('╚══════════════════════════════════════════════════════════════╝');

    // Phase 2 기준 병목 경고
    const p2 = sections[0];
    const warnings = [];

    if (sseP95 > 3000) warnings.push('[SSE] 연결 p95 > 3s → 세마포어 포화 또는 ConcurrentHashMap 경합');
    if (sseRate < 0.9)  warnings.push('[SSE] 성공률 < 90% → max-connections 한계');

    if (p2.list > 500)    warnings.push(`[P2] 목록조회 p95 ${fmt(p2.list)}ms > 500ms → 인덱스 미활용 또는 풀 대기`);
    if (p2.unread > 200)  warnings.push(`[P2] 안읽음 p95 ${fmt(p2.unread)}ms > 200ms → COUNT 풀스캔 또는 Redis 미스`);
    if (p2.read > 300)    warnings.push(`[P2] 단건읽음 p95 ${fmt(p2.read)}ms > 300ms → DB 커넥션 대기`);
    if (p2.readAll > 1000) warnings.push(`[P2] 전체읽음 p95 ${fmt(p2.readAll)}ms > 1s → 벌크 UPDATE 락 경합`);
    if (p2.rate < 0.95)   warnings.push(`[P2] 성공률 ${pct(p2.rate)}% < 95% → 서버 과부하`);

    if (warnings.length > 0) {
        console.log('\n병목점 경고:');
        warnings.forEach(w => console.log(`  ${w}`));
    } else {
        console.log('\nPhase 2 기준 주요 병목점 미발견 — 정상 부하에서 안정적');
    }

    return {
        'stdout': '',
        '/results/bottleneck-result.json': JSON.stringify(data, null, 2),
    };
}
