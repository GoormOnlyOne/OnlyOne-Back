// =============================================================
// 알림 도메인 통합 부하 테스트
// =============================================================
// 실행: MSYS_NO_PATHCONV=1 docker run --rm -i --network=host \
//   -v "$(pwd)/k6-tests:/scripts" grafana/k6 run /scripts/notification-loadtest.js
//
// 사전 준비:
//   1. seed-mongo-notifications.js 실행 (1000 유저, 유저당 ~200건)
//   2. application-local.yml → app.notification.storage=mongodb
//
// 흡수된 테스트:
//   - notification-bottleneck-test.js   → Phase 2 (Baseline 임계값)
//   - notification-highload-test.js     → Phase 3~6, 8~9
//   - notification-extreme-test.js      → Phase 7
//   - notification-endurance-test.js    → Phase 10
//   - notification-endurance-light.js   → Phase 10에 흡수
//   - notification-delivery-comparison  → 제외 (xk6-sse 확장 필요)
//
// Phase 구성 (~20분, 최대 1000 VUs):
// ┌────────┬─────────────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                         │ VU   │ 시간  │
// ├────────┼─────────────────────────────────┼──────┼───────┤
// │ 1      │ Warmup                          │ 50   │ 30s   │
// │ 2      │ Baseline — 전 API 혼합           │ 300  │ 2m    │
// │ 3      │ Read Storm — 목록 + unread       │ 500  │ 2m    │
// │ 4      │ Write Storm — 읽음 + 삭제        │ 400  │ 2m    │
// │ 5      │ Hot User Deep Paging             │ 200  │ 1.5m  │
// │ 6      │ SSE Flood                        │ 300  │ 1.5m  │
// │ 7      │ Extreme Mix — 1000 VU 극한       │ 1000 │ 2m    │
// │ 8      │ Spike — 순간 폭증                │ 1000 │ 1.5m  │
// │ 9      │ Double Spike — 회복 후 재폭증     │ 800  │ 2m    │
// │ 10     │ Soak — 중간 부하 장시간           │ 300  │ 3m    │
// │ 11     │ Cooldown                         │ 5    │ 30s   │
// └────────┴─────────────────────────────────┴──────┴───────┘
//
// 인프라 튜닝 탐지 포인트:
//   - HikariCP: Phase 3,7,8에서 커넥션 풀 고갈 여부
//   - InnoDB:   Phase 4,7에서 row lock 경합, buffer pool miss
//   - Tomcat:   Phase 6에서 SSE 장기 연결 + REST 동시 수용
//   - Redis:    Phase 2에서 unread 카운터 INCR/DECR 성능
//   - JVM:      Phase 10에서 GC pause 누적, 메모리 누수
// =============================================================

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, sseHeaders, BASE_URL, fetchNotificationIds, connectSSE } from './lib/common.js';
import { THRESHOLDS } from './lib/bottleneck.js';

// ============================================
// 테스트 데이터
// ============================================
const USER_COUNT = parseInt(__ENV.USER_COUNT || '100000');
const HOT_USER_MAX = 10;   // user 1~10은 1,100건 보유

// ============================================
// 커스텀 메트릭 — 엔드포인트별
// ============================================
const notiListDur     = new Trend('noti_list_duration', true);
const notiUnreadDur   = new Trend('noti_unread_duration', true);
const notiMarkDur     = new Trend('noti_mark_duration', true);
const notiDeleteDur   = new Trend('noti_delete_duration', true);
const notiMarkAllDur  = new Trend('noti_markall_duration', true);
const notiDeepPageDur = new Trend('noti_deep_page_duration', true);
const notiSseDur      = new Trend('noti_sse_duration', true);

// ============================================
// 커스텀 메트릭 — Phase별 성공률
// ============================================
const phase2Success = new Rate('noti_phase2_success');
const phase3Success = new Rate('noti_phase3_success');
const phase4Success = new Rate('noti_phase4_success');
const phase5Success = new Rate('noti_phase5_success');
const phase6Success = new Rate('noti_phase6_success');
const phase7Success = new Rate('noti_phase7_success');
const phase8Success = new Rate('noti_phase8_success');
const phase9Success = new Rate('noti_phase9_success');
const phase10Success = new Rate('noti_phase10_success');

const totalErrors = new Counter('noti_total_errors');

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

        // Phase 3: Read Storm — 500 VUs
        read_storm: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 500 },
                { duration: '80s', target: 500 },
                { duration: '20s', target: 0 },
            ],
            startTime: '160s',
            exec: 'readStorm',
            tags: { phase: '3_read_storm' },
        },

        // Phase 4: Write Storm — 400 VUs
        write_storm: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 400 },
                { duration: '80s', target: 400 },
                { duration: '20s', target: 0 },
            ],
            startTime: '285s',
            exec: 'writeStorm',
            tags: { phase: '4_write_storm' },
        },

        // Phase 5: Hot User Deep Paging — 200 VUs
        deep_paging: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '15s', target: 200 },
                { duration: '60s', target: 200 },
                { duration: '15s', target: 0 },
            ],
            startTime: '410s',
            exec: 'deepPaging',
            tags: { phase: '5_deep_paging' },
        },

        // Phase 6: SSE Flood — 300 VUs
        sse_flood: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '15s', target: 300 },
                { duration: '60s', target: 300 },
                { duration: '15s', target: 0 },
            ],
            startTime: '505s',
            exec: 'sseFlood',
            tags: { phase: '6_sse_flood' },
        },

        // Phase 7: Extreme Mix — 1000 VUs
        extreme_mix: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '20s', target: 1000 },
                { duration: '80s', target: 1000 },
                { duration: '20s', target: 0 },
            ],
            startTime: '600s',
            exec: 'extremeMix',
            tags: { phase: '7_extreme' },
        },

        // Phase 8: Spike — 1000 VUs 순간 폭증
        spike: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '10s', target: 1000 },
                { duration: '40s', target: 1000 },
                { duration: '20s', target: 5 },
                { duration: '20s', target: 5 },
            ],
            startTime: '725s',
            exec: 'spikeTest',
            tags: { phase: '8_spike' },
        },

        // Phase 9: Double Spike — 회복 후 재폭증
        double_spike: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '10s', target: 700 },
                { duration: '20s', target: 700 },
                { duration: '10s', target: 10 },
                { duration: '15s', target: 10 },
                { duration: '10s', target: 800 },
                { duration: '25s', target: 800 },
                { duration: '15s', target: 5 },
                { duration: '15s', target: 5 },
            ],
            startTime: '820s',
            exec: 'doubleSpikeTest',
            tags: { phase: '9_double_spike' },
        },

        // Phase 10: Soak — 중간 부하 장시간
        soak: {
            executor: 'constant-vus',
            vus: 300,
            duration: '180s',
            startTime: '945s',
            exec: 'soakTest',
            tags: { phase: '10_soak' },
        },

        // Phase 11: Cooldown
        cooldown: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            startTime: '1130s',
            exec: 'warmup',
            tags: { phase: '11_cooldown' },
        },
    },

    thresholds: {
        // ── 글로벌 ──
        http_req_failed: ['rate<0.05'],

        // ── 엔드포인트별 (bottleneck 임계값) ──
        'noti_list_duration':     [`p(95)<${THRESHOLDS.NORMAL}`],   // 500ms
        'noti_unread_duration':   [`p(95)<${THRESHOLDS.FAST}`],     // 200ms
        'noti_mark_duration':     [`p(95)<${THRESHOLDS.FAST}`],     // 200ms
        'noti_delete_duration':   [`p(95)<${THRESHOLDS.FAST}`],     // 200ms
        'noti_markall_duration':  [`p(95)<${THRESHOLDS.NORMAL}`],   // 500ms
        'noti_deep_page_duration': [`p(95)<${THRESHOLDS.SLOW}`],    // 1000ms
        'noti_sse_duration':      [`p(95)<${THRESHOLDS.SLOW}`],     // 1000ms

        // ── Phase별 성공률 ──
        'noti_phase2_success':  ['rate>0.98'],
        'noti_phase3_success':  ['rate>0.98'],
        'noti_phase4_success':  ['rate>0.95'],
        'noti_phase5_success':  ['rate>0.98'],
        'noti_phase6_success':  ['rate>0.90'],
        'noti_phase7_success':  ['rate>0.85'],
        'noti_phase8_success':  ['rate>0.85'],
        'noti_phase9_success':  ['rate>0.85'],
        'noti_phase10_success': ['rate>0.98'],
    },
};

// ============================================
// 유저 유틸
// ============================================
function randomUser() {
    const userId = Math.floor(Math.random() * USER_COUNT) + 1;
    return { userId, kakaoId: 1000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

function vuUser(vuId) {
    const userId = ((vuId - 1) % USER_COUNT) + 1;
    return { userId, kakaoId: 1000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

function hotUser() {
    const userId = Math.floor(Math.random() * HOT_USER_MAX) + 1;
    return { userId, kakaoId: 1000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

// ============================================
// Phase 1 & 11: Warmup / Cooldown
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    http.get(`${BASE_URL}/api/v1/notifications?size=5`, {
        headers: headers(token), tags: { name: 'warmup_list' },
    });
    http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: headers(token), tags: { name: 'warmup_unread' },
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
    let notificationIds = [];

    // 목록 조회
    const listRes = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
        headers: hdrs, tags: { name: 'bl_list' },
    });
    notiListDur.add(listRes.timings.duration);
    phase2Success.add(listRes.status === 200);
    if (listRes.status !== 200) totalErrors.add(1);

    if (listRes.status === 200) {
        try {
            const body = JSON.parse(listRes.body);
            const data = body.data || body;
            if (data.notifications) {
                notificationIds = data.notifications.map(n => n.notificationId);
            }
        } catch (e) { /* ignore */ }
    }
    sleep(0.2);

    // 안읽은 개수
    const unreadRes = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: hdrs, tags: { name: 'bl_unread' },
    });
    notiUnreadDur.add(unreadRes.timings.duration);
    phase2Success.add(unreadRes.status === 200);
    sleep(0.2);

    // 단건 읽음 (50%)
    if (notificationIds.length > 0 && Math.random() < 0.5) {
        const id = notificationIds[Math.floor(Math.random() * notificationIds.length)];
        const markRes = http.patch(`${BASE_URL}/api/v1/notifications/${id}/read`, null, {
            headers: hdrs, tags: { name: 'bl_mark' },
        });
        notiMarkDur.add(markRes.timings.duration);
        phase2Success.add(markRes.status === 200);
    }
    sleep(0.2);

    // 전체 읽음 (10%)
    if (Math.random() < 0.1) {
        const markAllRes = http.patch(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: hdrs, tags: { name: 'bl_markall' },
        });
        notiMarkAllDur.add(markAllRes.timings.duration);
        phase2Success.add(markAllRes.status === 200);
    }
    sleep(0.2);

    // 삭제 (5%)
    if (notificationIds.length > 0 && Math.random() < 0.05) {
        const id = notificationIds[notificationIds.length - 1];
        const delRes = http.del(`${BASE_URL}/api/v1/notifications/${id}`, null, {
            headers: hdrs, tags: { name: 'bl_delete' },
        });
        notiDeleteDur.add(delRes.timings.duration);
        phase2Success.add(delRes.status === 200);
    }
    sleep(0.2);

    // SSE 연결 (20%)
    if (Math.random() < 0.2) {
        const sseResult = connectSSE(user, '2s');
        notiSseDur.add(sseResult.duration);
        phase2Success.add(sseResult.success);
    }

    sleep(0.3);
}

// ============================================
// Phase 3: Read Storm — 500 VUs 읽기 폭풍
// ============================================
export function readStorm() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 목록 조회
    const listRes = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
        headers: hdrs, tags: { name: 'rs_list' },
    });
    notiListDur.add(listRes.timings.duration);
    phase3Success.add(listRes.status === 200);
    if (listRes.status !== 200) totalErrors.add(1);

    // 커서 페이지네이션 (2페이지)
    if (listRes.status === 200) {
        try {
            const body = JSON.parse(listRes.body);
            const data = body.data || body;
            if (data.hasMore && data.cursor) {
                const res2 = http.get(
                    `${BASE_URL}/api/v1/notifications?size=20&cursor=${data.cursor}`, {
                        headers: hdrs, tags: { name: 'rs_list_p2' },
                    });
                notiListDur.add(res2.timings.duration);
                phase3Success.add(res2.status === 200);
            }
        } catch (e) { /* ignore */ }
    }

    // 안읽은 개수
    const unreadRes = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: hdrs, tags: { name: 'rs_unread' },
    });
    notiUnreadDur.add(unreadRes.timings.duration);
    phase3Success.add(unreadRes.status === 200);

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 4: Write Storm — 400 VUs 쓰기 폭풍
// ============================================
export function writeStorm() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();

    if (roll < 0.50) {
        // 50%: 단건 읽음
        const ids = fetchNotificationIds(token, 10);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            const res = http.patch(`${BASE_URL}/api/v1/notifications/${id}/read`, null, {
                headers: hdrs, tags: { name: 'ws_mark' },
            });
            notiMarkDur.add(res.timings.duration);
            phase4Success.add(res.status === 200);
            if (res.status !== 200) totalErrors.add(1);
        }
    } else if (roll < 0.80) {
        // 30%: 삭제
        const ids = fetchNotificationIds(token, 10);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            const res = http.del(`${BASE_URL}/api/v1/notifications/${id}`, null, {
                headers: hdrs, tags: { name: 'ws_delete' },
            });
            notiDeleteDur.add(res.timings.duration);
            phase4Success.add(res.status === 200);
            if (res.status !== 200) totalErrors.add(1);
        }
    } else {
        // 20%: 전체 읽음
        const res = http.patch(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: hdrs, tags: { name: 'ws_markall' },
        });
        notiMarkAllDur.add(res.timings.duration);
        phase4Success.add(res.status === 200);
        if (res.status !== 200) totalErrors.add(1);
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 5: Hot User Deep Paging (1,100건 유저)
// ============================================
export function deepPaging() {
    const user = hotUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    let cursor = null;
    let pageNum = 0;
    const maxPages = 10;

    while (pageNum < maxPages) {
        const url = cursor
            ? `${BASE_URL}/api/v1/notifications?size=20&cursor=${cursor}`
            : `${BASE_URL}/api/v1/notifications?size=20`;

        const res = http.get(url, {
            headers: hdrs,
            tags: { name: `dp_page_${Math.min(pageNum, 5)}` },
        });

        notiDeepPageDur.add(res.timings.duration);
        phase5Success.add(res.status === 200);
        pageNum++;

        if (res.status !== 200) { totalErrors.add(1); break; }

        try {
            const body = JSON.parse(res.body);
            const data = body.data || body;
            if (!data.hasMore || !data.cursor) break;
            cursor = data.cursor;
        } catch (e) { break; }

        sleep(0.02);
    }

    sleep(0.1);
}

// ============================================
// Phase 6: SSE Flood — 300 VUs 동시 SSE 연결
// ============================================
export function sseFlood() {
    const user = vuUser(__VU);
    const token = generateJWT(user);

    const sseResult = connectSSE(user, '5s');
    notiSseDur.add(sseResult.duration);
    phase6Success.add(sseResult.success);
    if (!sseResult.success) totalErrors.add(1);

    // SSE 연결 직후 즉시 API 호출 (recovery와 경합)
    const listRes = http.get(`${BASE_URL}/api/v1/notifications?size=10`, {
        headers: headers(token), tags: { name: 'sse_list' },
    });
    notiListDur.add(listRes.timings.duration);
    phase6Success.add(listRes.status === 200);

    sleep(0.5);
}

// ============================================
// Phase 7: Extreme Mix — 1000 VU 읽기/쓰기 혼합 극한
// ============================================
export function extremeMix() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let ok = false;
    let dur = 0;

    if (roll < 0.40) {
        // 40%: 목록 조회
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: hdrs, tags: { name: 'ex_list' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        notiListDur.add(dur);
    } else if (roll < 0.60) {
        // 20%: 안읽은 개수
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs, tags: { name: 'ex_unread' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        notiUnreadDur.add(dur);
    } else if (roll < 0.80) {
        // 20%: 단건 읽음
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.patch(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: hdrs, tags: { name: 'ex_mark' },
            });
            dur = res.timings.duration;
            ok = res.status === 200;
            notiMarkDur.add(dur);
        } else { ok = true; }
    } else if (roll < 0.92) {
        // 12%: 전체 읽음
        const res = http.patch(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: hdrs, tags: { name: 'ex_markall' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        notiMarkAllDur.add(dur);
    } else {
        // 8%: 삭제
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.del(`${BASE_URL}/api/v1/notifications/${ids[0]}`, null, {
                headers: hdrs, tags: { name: 'ex_delete' },
            });
            dur = res.timings.duration;
            ok = res.status === 200;
            notiDeleteDur.add(dur);
        } else { ok = true; }
    }

    phase7Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.02 + Math.random() * 0.05);
}

// ============================================
// Phase 8: Spike — 1000 VU 순간 폭증
// ============================================
export function spikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const ops = ['list', 'unread', 'read', 'mark_all'];
    const op = ops[Math.floor(Math.random() * ops.length)];
    let ok = false;
    let dur = 0;

    if (op === 'list') {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: hdrs, tags: { name: 'sp_list' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        notiListDur.add(dur);
    } else if (op === 'unread') {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs, tags: { name: 'sp_unread' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        notiUnreadDur.add(dur);
    } else if (op === 'read') {
        const ids = fetchNotificationIds(token, 3);
        if (ids.length > 0) {
            const res = http.patch(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: hdrs, tags: { name: 'sp_mark' },
            });
            dur = res.timings.duration;
            ok = res.status === 200;
            notiMarkDur.add(dur);
        } else { ok = true; }
    } else {
        const res = http.patch(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: hdrs, tags: { name: 'sp_markall' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        notiMarkAllDur.add(dur);
    }

    phase8Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.02);
}

// ============================================
// Phase 9: Double Spike — 이중 스파이크
// ============================================
export function doubleSpikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let ok = false;
    let dur = 0;

    if (roll < 0.50) {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: hdrs, tags: { name: 'ds_list' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        notiListDur.add(dur);
    } else if (roll < 0.80) {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs, tags: { name: 'ds_unread' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        notiUnreadDur.add(dur);
    } else {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.patch(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: hdrs, tags: { name: 'ds_mark' },
            });
            dur = res.timings.duration;
            ok = res.status === 200;
            notiMarkDur.add(dur);
        } else { ok = true; }
    }

    phase9Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.02 + Math.random() * 0.03);
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

    if (roll < 0.50) {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: hdrs, tags: { name: 'soak_list' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        notiListDur.add(dur);
    } else if (roll < 0.75) {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs, tags: { name: 'soak_unread' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;
        notiUnreadDur.add(dur);
    } else if (roll < 0.90) {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.patch(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: hdrs, tags: { name: 'soak_mark' },
            });
            dur = res.timings.duration;
            ok = res.status === 200;
            notiMarkDur.add(dur);
        } else { ok = true; }
    } else {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.del(`${BASE_URL}/api/v1/notifications/${ids[0]}`, null, {
                headers: hdrs, tags: { name: 'soak_delete' },
            });
            dur = res.timings.duration;
            ok = res.status === 200;
            notiDeleteDur.add(dur);
        } else { ok = true; }
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
