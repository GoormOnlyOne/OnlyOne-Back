// =============================================================
// 알림 도메인 통합 부하 테스트
// =============================================================
// 실행: ./k6-tests/run-loadtest.sh notification
//
// 사전 준비:
//   1. seed-all-domains.sql 실행 (20M 알림, userId 1~100000)
//
// Phase 구성 (~22분, 최대 1500 VUs):
// ┌────────┬─────────────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                         │ VU   │ 시간  │
// ├────────┼─────────────────────────────────┼──────┼───────┤
// │ 1      │ Warmup                          │ 50   │ 30s   │
// │ 2      │ Baseline — 전 API 혼합           │ 300  │ 2m    │
// │ 3      │ Read Storm — 목록 + unread       │ 500  │ 2m    │
// │ 4      │ Write Storm — 읽음 + 삭제        │ 400  │ 2m    │
// │ 5      │ Hot User Deep Paging             │ 200  │ 1.5m  │
// │ 6      │ SSE Flood                        │ 300  │ 1.5m  │
// │ 6b     │ SSE Delivery E2E (생성→전달 검증) │ 100  │ 1.5m  │
// │ 7      │ Extreme Mix — 1500 VU 극한       │ 1500 │ 2m    │
// │ 8      │ Spike — 순간 폭증                │ 1500 │ 1.5m  │
// │ 9      │ Double Spike — 회복 후 재폭증     │ 1200 │ 2m    │
// │ 10     │ Soak — 중간 부하 장시간           │ 300  │ 3m    │
// │ 11     │ Cooldown                         │ 5    │ 30s   │
// └────────┴─────────────────────────────────┴──────┴───────┘
// =============================================================

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, sseHeaders, BASE_URL, fetchNotificationIds, parseSSEEvents, vu, dur, startAfter } from '../lib/common.js';
import { THRESHOLDS } from '../lib/bottleneck.js';
import { randomUser, vuUser, hotUser, createNotification, pad, num, pct } from './helpers.js';
import { sseSubscribe } from './sse-helpers.js';

// ── Phase 시간 (초) ──
const P1 = 30, P2 = 120, P3 = 120, P4 = 120, P5 = 90, P6 = 90;
const P7 = 120, P8 = 90, P9 = 120, P10 = 180, P11 = 30;

// ── Phase별 VU ──
const BASELINE_VU  = parseInt(__ENV.BASELINE_VU  || '300');
const READ_VU      = parseInt(__ENV.READ_VU      || '500');
const WRITE_VU     = parseInt(__ENV.WRITE_VU     || '400');
const DEEP_VU      = parseInt(__ENV.DEEP_VU      || '200');
const SSE_FLOOD_VU = parseInt(__ENV.SSE_FLOOD_VU || '300');
const SSE_E2E_VU   = parseInt(__ENV.SSE_E2E_VU   || '100');
const EXTREME_VU   = parseInt(__ENV.EXTREME_VU   || '1500');
const SPIKE_VU     = parseInt(__ENV.SPIKE_VU     || '1500');
const DSPIKE_VU1   = parseInt(__ENV.DSPIKE_VU1   || '1000');
const DSPIKE_VU2   = parseInt(__ENV.DSPIKE_VU2   || '1200');
const SOAK_VU      = parseInt(__ENV.SOAK_VU      || '300');

// ── 커스텀 메트릭 — 엔드포인트별 ──
const notiListDur     = new Trend('noti_list_duration', true);
const notiUnreadDur   = new Trend('noti_unread_duration', true);
const notiMarkDur     = new Trend('noti_mark_duration', true);
const notiDeleteDur   = new Trend('noti_delete_duration', true);
const notiMarkAllDur  = new Trend('noti_markall_duration', true);
const notiDeepPageDur = new Trend('noti_deep_page_duration', true);
const notiSseDur      = new Trend('noti_sse_duration', true);
const notiSseConnDur  = new Trend('noti_sse_connect_duration', true);

// ── SSE Delivery E2E 메트릭 ──
const sseDeliveryRate       = new Rate('sse_delivery_rate');
const sseDeliveryLatency    = new Trend('sse_delivery_latency', true);
const sseDeliveryEventCount = new Trend('sse_delivery_event_count');
const sseCreateDur          = new Trend('sse_create_duration', true);
const sseCreateOk           = new Rate('sse_create_success');
const sseRecoveryRate       = new Rate('sse_recovery_delivery_rate');

// ── 커스텀 메트릭 — Phase별 성공률 ──
const phase2Success  = new Rate('noti_phase2_success');
const phase3Success  = new Rate('noti_phase3_success');
const phase4Success  = new Rate('noti_phase4_success');
const phase5Success  = new Rate('noti_phase5_success');
const phase6Success  = new Rate('noti_phase6_success');
const phase7Success  = new Rate('noti_phase7_success');
const phase8Success  = new Rate('noti_phase8_success');
const phase9Success  = new Rate('noti_phase9_success');
const phase10Success = new Rate('noti_phase10_success');

const totalErrors = new Counter('noti_total_errors');

// ── 시나리오 설정 ──
export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus', vus: vu(50), duration: dur(P1),
            exec: 'warmup', tags: { phase: '1_warmup' },
        },
        baseline: {
            executor: 'constant-vus', vus: vu(BASELINE_VU), duration: dur(P2),
            startTime: startAfter([P1]), exec: 'baseline', tags: { phase: '2_baseline' },
        },
        read_storm: {
            executor: 'ramping-vus', startVUs: vu(10),
            stages: [
                { duration: dur(20), target: vu(READ_VU) },
                { duration: dur(80), target: vu(READ_VU) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2]), exec: 'readStorm', tags: { phase: '3_read_storm' },
        },
        write_storm: {
            executor: 'ramping-vus', startVUs: vu(10),
            stages: [
                { duration: dur(20), target: vu(WRITE_VU) },
                { duration: dur(80), target: vu(WRITE_VU) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3]), exec: 'writeStorm', tags: { phase: '4_write_storm' },
        },
        deep_paging: {
            executor: 'ramping-vus', startVUs: vu(5),
            stages: [
                { duration: dur(15), target: vu(DEEP_VU) },
                { duration: dur(60), target: vu(DEEP_VU) },
                { duration: dur(15), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4]), exec: 'deepPaging', tags: { phase: '5_deep_paging' },
        },
        sse_flood: {
            executor: 'ramping-vus', startVUs: vu(5),
            stages: [
                { duration: dur(15), target: vu(SSE_FLOOD_VU) },
                { duration: dur(60), target: vu(SSE_FLOOD_VU) },
                { duration: dur(15), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5]), exec: 'sseFlood', tags: { phase: '6_sse_flood' },
        },
        sse_delivery_e2e: {
            executor: 'constant-vus', vus: vu(SSE_E2E_VU), duration: dur(P6),
            startTime: startAfter([P1, P2, P3, P4, P5]), exec: 'sseDeliveryE2E', tags: { phase: '6b_sse_delivery' },
        },
        extreme_mix: {
            executor: 'ramping-vus', startVUs: vu(20),
            stages: [
                { duration: dur(20), target: vu(EXTREME_VU) },
                { duration: dur(80), target: vu(EXTREME_VU) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5, P6]), exec: 'extremeMix', tags: { phase: '7_extreme' },
        },
        spike: {
            executor: 'ramping-vus', startVUs: vu(5),
            stages: [
                { duration: dur(10), target: vu(SPIKE_VU) },
                { duration: dur(40), target: vu(SPIKE_VU) },
                { duration: dur(20), target: vu(5) },
                { duration: dur(20), target: vu(5) },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7]), exec: 'spikeTest', tags: { phase: '8_spike' },
        },
        double_spike: {
            executor: 'ramping-vus', startVUs: vu(5),
            stages: [
                { duration: dur(10), target: vu(DSPIKE_VU1) },
                { duration: dur(20), target: vu(DSPIKE_VU1) },
                { duration: dur(10), target: vu(10) },
                { duration: dur(15), target: vu(10) },
                { duration: dur(10), target: vu(DSPIKE_VU2) },
                { duration: dur(25), target: vu(DSPIKE_VU2) },
                { duration: dur(15), target: vu(5) },
                { duration: dur(15), target: vu(5) },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7, P8]), exec: 'doubleSpikeTest', tags: { phase: '9_double_spike' },
        },
        soak: {
            executor: 'constant-vus', vus: vu(SOAK_VU), duration: dur(P10),
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7, P8, P9]), exec: 'soakTest', tags: { phase: '10_soak' },
        },
        cooldown: {
            executor: 'constant-vus', vus: vu(5), duration: dur(P11),
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7, P8, P9, P10]), exec: 'warmup', tags: { phase: '11_cooldown' },
        },
    },

    thresholds: {
        http_req_failed:           ['rate<0.05'],
        'noti_list_duration':      [`p(95)<${THRESHOLDS.NORMAL}`],
        'noti_unread_duration':    [`p(95)<${THRESHOLDS.NORMAL}`],
        'noti_mark_duration':      [`p(95)<${THRESHOLDS.NORMAL}`],
        'noti_delete_duration':    [`p(95)<${THRESHOLDS.NORMAL}`],
        'noti_markall_duration':   [`p(95)<${THRESHOLDS.NORMAL}`],
        'noti_deep_page_duration': [`p(95)<${THRESHOLDS.SLOW}`],
        'noti_sse_duration':       ['p(95)<6000'],
        'sse_delivery_rate':       ['rate>0.50'],
        'sse_delivery_latency':    ['p(95)<5000'],
        'sse_recovery_delivery_rate': ['rate>0.50'],
        'sse_create_success':      ['rate>0.95'],
        'noti_phase2_success':     ['rate>0.98'],
        'noti_phase3_success':     ['rate>0.98'],
        'noti_phase4_success':     ['rate>0.95'],
        'noti_phase5_success':     ['rate>0.98'],
        'noti_phase6_success':     ['rate>0.90'],
        'noti_phase7_success':     ['rate>0.85'],
        'noti_phase8_success':     ['rate>0.85'],
        'noti_phase9_success':     ['rate>0.85'],
        'noti_phase10_success':    ['rate>0.98'],
    },
};

// ── Phase 1 & 11: Warmup / Cooldown ──
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

// ── Phase 2: Baseline — 전 API 혼합 ──
export function baseline() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    let notificationIds = [];

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

    const unreadRes = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: hdrs, tags: { name: 'bl_unread' },
    });
    notiUnreadDur.add(unreadRes.timings.duration);
    phase2Success.add(unreadRes.status === 200);
    sleep(0.2);

    if (notificationIds.length > 0 && Math.random() < 0.5) {
        const id = notificationIds[Math.floor(Math.random() * notificationIds.length)];
        const markRes = http.put(`${BASE_URL}/api/v1/notifications/${id}/read`, null, {
            headers: hdrs, tags: { name: 'bl_mark' },
        });
        notiMarkDur.add(markRes.timings.duration);
        phase2Success.add(markRes.status === 200);
    }
    sleep(0.2);

    if (Math.random() < 0.1) {
        const markAllRes = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: hdrs, tags: { name: 'bl_markall' },
        });
        notiMarkAllDur.add(markAllRes.timings.duration);
        phase2Success.add(markAllRes.status === 200);
    }
    sleep(0.2);

    if (notificationIds.length > 0 && Math.random() < 0.05) {
        const id = notificationIds[notificationIds.length - 1];
        const delRes = http.del(`${BASE_URL}/api/v1/notifications/${id}`, null, {
            headers: hdrs, tags: { name: 'bl_delete' },
        });
        notiDeleteDur.add(delRes.timings.duration);
        phase2Success.add(delRes.status === 200);
    }
    sleep(0.2);

    if (Math.random() < 0.2) {
        const token2 = generateJWT(user);
        const sseResult = sseSubscribe(token2, 2, 1, 'bl_sse');
        notiSseDur.add(sseResult.duration);
        notiSseConnDur.add(sseResult.connectDuration);
        phase2Success.add(sseResult.connected);
    }

    sleep(0.3);
}

// ── Phase 3: Read Storm ──
export function readStorm() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const listRes = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
        headers: hdrs, tags: { name: 'rs_list' },
    });
    notiListDur.add(listRes.timings.duration);
    phase3Success.add(listRes.status === 200);
    if (listRes.status !== 200) totalErrors.add(1);

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

    const unreadRes = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: hdrs, tags: { name: 'rs_unread' },
    });
    notiUnreadDur.add(unreadRes.timings.duration);
    phase3Success.add(unreadRes.status === 200);

    sleep(0.05 + Math.random() * 0.1);
}

// ── Phase 4: Write Storm ──
export function writeStorm() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();

    if (roll < 0.50) {
        const ids = fetchNotificationIds(token, 10);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            const res = http.put(`${BASE_URL}/api/v1/notifications/${id}/read`, null, {
                headers: hdrs, tags: { name: 'ws_mark' },
            });
            notiMarkDur.add(res.timings.duration);
            phase4Success.add(res.status === 200);
            if (res.status !== 200) totalErrors.add(1);
        }
    } else if (roll < 0.80) {
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
        const res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: hdrs, tags: { name: 'ws_markall' },
        });
        notiMarkAllDur.add(res.timings.duration);
        phase4Success.add(res.status === 200);
        if (res.status !== 200) totalErrors.add(1);
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ── Phase 5: Hot User Deep Paging ──
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

// ── Phase 6: SSE Flood ──
export function sseFlood() {
    const user = vuUser(__VU);
    const token = generateJWT(user);

    const sseResult = sseSubscribe(token, 3, 5, 'sse_flood');
    notiSseDur.add(sseResult.duration);
    notiSseConnDur.add(sseResult.connectDuration);
    phase6Success.add(sseResult.connected);
    if (!sseResult.connected) totalErrors.add(1);

    const listRes = http.get(`${BASE_URL}/api/v1/notifications?size=10`, {
        headers: headers(token), tags: { name: 'sse_list' },
    });
    notiListDur.add(listRes.timings.duration);
    phase6Success.add(listRes.status === 200);

    sleep(0.5);
}

// ── Phase 6b: SSE Delivery E2E — 알림 생성→SSE 실제 전달 검증 ──
//
// 흐름 (Recovery 방식 — 표준 k6 HTTP로 검증 가능):
//   1. 알림 생성 (SSE 미연결 → sse_sent=false로 DB 저장)
//   2. 0.5s 대기 (트랜잭션 커밋 대기)
//   3. SSE 연결 (HTTP GET, 3s timeout)
//   4. MissedNotificationRecovery 트리거 → sse_sent=false 알림 전달
//   5. 응답 body에서 notification 이벤트 파싱
//   6. 생성한 notificationId가 수신 이벤트에 포함되는지 검증
//
// 측정 항목:
//   - sse_delivery_rate: 생성한 알림이 SSE로 실제 전달된 비율
//   - sse_delivery_latency: 생성~수신 간 지연 (ms)
//   - sse_delivery_event_count: SSE 연결 시 수신한 notification 이벤트 수
//   - sse_recovery_delivery_rate: Recovery 경로 전달 성공률
export function sseDeliveryE2E() {
    const user = vuUser(__VU);
    const createStart = Date.now();

    // 1) 알림 생성 (SSE 미연결 → DB에만 저장, sse_sent=false)
    const created = createNotification(user.userId, 'LIKE');
    sseCreateDur.add(created.duration);
    sseCreateOk.add(created.ok);
    if (!created.ok) {
        totalErrors.add(1);
        sleep(1);
        return;
    }

    // 2) 트랜잭션 커밋 대기
    sleep(0.3);

    // 3) SSE 연결 — MissedNotificationRecovery 트리거 (동기 실행)
    //    xk6-sse: event 수신 시 즉시 close → timeout 대기 없음
    const token = generateJWT(user);
    const sseResult = sseSubscribe(token, 5, 10, 'sse_delivery_e2e');

    if (!sseResult.connected) {
        sseDeliveryRate.add(false);
        sseRecoveryRate.add(false);
        sleep(0.5);
        return;
    }

    notiSseConnDur.add(sseResult.connectDuration);
    sseDeliveryEventCount.add(sseResult.notifEvents.length);

    // 4) 생성한 notificationId가 수신 이벤트에 포함되는지 검증
    const delivered = sseResult.notifEvents.some(e => e.notificationId === created.notificationId);
    sseDeliveryRate.add(delivered);
    sseRecoveryRate.add(delivered);

    // 5) 전달 지연 측정
    if (delivered) {
        const matchedEvent = sseResult.notifEvents.find(e => e.notificationId === created.notificationId);
        if (matchedEvent && matchedEvent.sentAtEpochMs > 0) {
            const latency = matchedEvent.sentAtEpochMs - createStart;
            if (latency > 0 && latency < 30000) {
                sseDeliveryLatency.add(latency);
            }
        } else {
            sseDeliveryLatency.add(Date.now() - createStart);
        }
    }

    sleep(0.5);
}

// ── Phase 7: Extreme Mix ──
export function extremeMix() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let ok = false;
    let d = 0;

    if (roll < 0.40) {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: hdrs, tags: { name: 'ex_list' },
        });
        d = res.timings.duration; ok = res.status === 200;
        notiListDur.add(d);
    } else if (roll < 0.60) {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs, tags: { name: 'ex_unread' },
        });
        d = res.timings.duration; ok = res.status === 200;
        notiUnreadDur.add(d);
    } else if (roll < 0.80) {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.put(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: hdrs, tags: { name: 'ex_mark' },
            });
            d = res.timings.duration; ok = res.status === 200;
            notiMarkDur.add(d);
        } else { ok = true; }
    } else if (roll < 0.92) {
        const res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: hdrs, tags: { name: 'ex_markall' },
        });
        d = res.timings.duration; ok = res.status === 200;
        notiMarkAllDur.add(d);
    } else {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.del(`${BASE_URL}/api/v1/notifications/${ids[0]}`, null, {
                headers: hdrs, tags: { name: 'ex_delete' },
            });
            d = res.timings.duration; ok = res.status === 200;
            notiDeleteDur.add(d);
        } else { ok = true; }
    }

    phase7Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.02 + Math.random() * 0.05);
}

// ── Phase 8: Spike ──
export function spikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const ops = ['list', 'unread', 'read', 'mark_all'];
    const op = ops[Math.floor(Math.random() * ops.length)];
    let ok = false;

    if (op === 'list') {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: hdrs, tags: { name: 'sp_list' },
        });
        ok = res.status === 200; notiListDur.add(res.timings.duration);
    } else if (op === 'unread') {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs, tags: { name: 'sp_unread' },
        });
        ok = res.status === 200; notiUnreadDur.add(res.timings.duration);
    } else if (op === 'read') {
        const ids = fetchNotificationIds(token, 3);
        if (ids.length > 0) {
            const res = http.put(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: hdrs, tags: { name: 'sp_mark' },
            });
            ok = res.status === 200; notiMarkDur.add(res.timings.duration);
        } else { ok = true; }
    } else {
        const res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: hdrs, tags: { name: 'sp_markall' },
        });
        ok = res.status === 200; notiMarkAllDur.add(res.timings.duration);
    }

    phase8Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.02);
}

// ── Phase 9: Double Spike ──
export function doubleSpikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let ok = false;

    if (roll < 0.50) {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: hdrs, tags: { name: 'ds_list' },
        });
        ok = res.status === 200; notiListDur.add(res.timings.duration);
    } else if (roll < 0.80) {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs, tags: { name: 'ds_unread' },
        });
        ok = res.status === 200; notiUnreadDur.add(res.timings.duration);
    } else {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.put(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: hdrs, tags: { name: 'ds_mark' },
            });
            ok = res.status === 200; notiMarkDur.add(res.timings.duration);
        } else { ok = true; }
    }

    phase9Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.02 + Math.random() * 0.03);
}

// ── Phase 10: Soak ──
export function soakTest() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let ok = false;

    if (roll < 0.50) {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: hdrs, tags: { name: 'soak_list' },
        });
        ok = res.status === 200; notiListDur.add(res.timings.duration);
    } else if (roll < 0.75) {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs, tags: { name: 'soak_unread' },
        });
        ok = res.status === 200; notiUnreadDur.add(res.timings.duration);
    } else if (roll < 0.90) {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.put(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: hdrs, tags: { name: 'soak_mark' },
            });
            ok = res.status === 200; notiMarkDur.add(res.timings.duration);
        } else { ok = true; }
    } else {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.del(`${BASE_URL}/api/v1/notifications/${ids[0]}`, null, {
                headers: hdrs, tags: { name: 'soak_delete' },
            });
            ok = res.status === 200; notiDeleteDur.add(res.timings.duration);
        } else { ok = true; }
    }

    phase10Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.05 + Math.random() * 0.15);
}

// ── default function (fallback) ──
export default function () {
    warmup();
}

// ── handleSummary ──
export function handleSummary(data) {
    const m = data.metrics;

    function endpointRow(label, durKey) {
        const p50 = num(m[durKey]?.values?.med);
        const p95 = num(m[durKey]?.values?.['p(95)']);
        const p99 = num(m[durKey]?.values?.['p(99)']);
        return `│ ${pad(label, 12)} │ ${pad(p50, 8)} │ ${pad(p95, 8)} │ ${pad(p99, 8)} │`;
    }

    const lines = [
        '',
        '╔══════════════════════════════════════════════════════════╗',
        '║          알림 통합 부하 테스트 리포트                      ║',
        '╚══════════════════════════════════════════════════════════╝',
        '',
        '┌──────────────┬──────────┬──────────┬──────────┐',
        '│ Endpoint     │ p50 (ms) │ p95 (ms) │ p99 (ms) │',
        '├──────────────┼──────────┼──────────┼──────────┤',
        endpointRow('list',      'noti_list_duration'),
        endpointRow('unread',    'noti_unread_duration'),
        endpointRow('mark',      'noti_mark_duration'),
        endpointRow('delete',    'noti_delete_duration'),
        endpointRow('mark-all',  'noti_markall_duration'),
        endpointRow('deep-page', 'noti_deep_page_duration'),
        endpointRow('sse',       'noti_sse_duration'),
        endpointRow('sse-conn',  'noti_sse_connect_duration'),
        '└──────────────┴──────────┴──────────┴──────────┘',
        '',
        '┌──────────────────────────────────────────────────────┐',
        '│ SSE Delivery E2E (알림 생성 → SSE 실제 전달 검증)     │',
        '├──────────────────────────────────────────────────────┤',
        `│ 전달 성공률:    ${pad(pct(m.sse_delivery_rate?.values?.rate), 10)}                       │`,
        `│ Recovery 전달:  ${pad(pct(m.sse_recovery_delivery_rate?.values?.rate), 10)}                       │`,
        `│ 전달 지연:      p50=${pad(num(m.sse_delivery_latency?.values?.med), 8)} p95=${pad(num(m.sse_delivery_latency?.values?.['p(95)']), 8)} ms │`,
        `│ 수신 이벤트:    avg=${pad(num(m.sse_delivery_event_count?.values?.avg), 8)}                      │`,
        `│ 알림 생성:      p50=${pad(num(m.sse_create_duration?.values?.med), 8)} p95=${pad(num(m.sse_create_duration?.values?.['p(95)']), 8)} ms │`,
        `│ 생성 성공률:    ${pad(pct(m.sse_create_success?.values?.rate), 10)}                       │`,
        '└──────────────────────────────────────────────────────┘',
        '',
        '┌──────────────────────────────────────────────────────┐',
        '│ Phase별 성공률                                        │',
        '├──────────────────────────────────────────────────────┤',
        `│ P2  Baseline (${BASELINE_VU} VU):    ${pad(pct(m.noti_phase2_success?.values?.rate), 8)}           │`,
        `│ P3  Read Storm (${READ_VU} VU):   ${pad(pct(m.noti_phase3_success?.values?.rate), 8)}           │`,
        `│ P4  Write Storm (${WRITE_VU} VU):  ${pad(pct(m.noti_phase4_success?.values?.rate), 8)}           │`,
        `│ P5  Deep Paging (${DEEP_VU} VU):  ${pad(pct(m.noti_phase5_success?.values?.rate), 8)}           │`,
        `│ P6  SSE Flood (${SSE_FLOOD_VU} VU):   ${pad(pct(m.noti_phase6_success?.values?.rate), 8)}           │`,
        `│ P7  Extreme (${EXTREME_VU} VU):   ${pad(pct(m.noti_phase7_success?.values?.rate), 8)}           │`,
        `│ P8  Spike (${SPIKE_VU} VU):     ${pad(pct(m.noti_phase8_success?.values?.rate), 8)}           │`,
        `│ P9  Double Spike:       ${pad(pct(m.noti_phase9_success?.values?.rate), 8)}           │`,
        `│ P10 Soak (${SOAK_VU} VU):       ${pad(pct(m.noti_phase10_success?.values?.rate), 8)}           │`,
        `│ 총 에러:                ${pad(num(m.noti_total_errors?.values?.count, 0), 8)} 건         │`,
        '└──────────────────────────────────────────────────────┘',
        '',
    ];

    const summary = lines.join('\n');
    console.log(summary);
    return { 'stdout': summary };
}
