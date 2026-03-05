// =============================================================
// 알림 SSE 재연결 & 미전송 알림 복구 테스트 (xk6-sse 모듈)
// =============================================================
// 실행: ./k6-tests/run-loadtest.sh notif-sse-reconnect
//
// 검증 포인트:
//   1. 재연결 시 MissedNotificationRecovery가 sse_sent=false 알림 전달
//   2. 반복 연결/해제 후에도 복구 동작 안정
//   3. 200 VU 동시 재연결 시 서버 안정성
//   4. 재연결 후 CRUD API 정상 동작
//
// SSE 복구 흐름:
//   1. 알림 생성 → sse_sent=false 상태로 DB 저장
//   2. SSE 미연결이므로 BatchProcessor 스킵
//   3. 클라이언트 SSE 재연결 → SseMissedNotificationRecovery 트리거
//   4. sse_sent=false인 알림 최대 50건 SSE로 전달 + sse_sent=true
//
// Phase 구성 (~6분):
// ┌────────┬──────────────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                          │ VU   │ 시간  │
// ├────────┼──────────────────────────────────┼──────┼───────┤
// │ 1      │ Warmup                           │ 10   │ 15s   │
// │ 2      │ 단순 재연결 (생성→대기→연결→검증)  │ 50   │ 90s   │
// │ 3      │ 반복 재연결 (3 사이클)             │ 30   │ 90s   │
// │ 4      │ 대규모 동시 재연결 (200 VU)        │ 200  │ 90s   │
// │ 5      │ 재연결 후 CRUD                    │ 50   │ 60s   │
// │ 6      │ Cooldown                          │ 5    │ 15s   │
// └────────┴──────────────────────────────────┴──────┴───────┘
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, fetchNotificationIds, BASE_URL, vu, dur, startAfter } from '../lib/common.js';
import { vuUser, createNotification, pad, num, pct } from './helpers.js';
import { sseSubscribe } from './sse-helpers.js';

// ── 커스텀 메트릭 ──
const reconnectRecoveryRate    = new Rate('reconnect_recovery_rate');
const reconnectRecoveryLatency = new Trend('reconnect_recovery_latency', true);
const reconnectRecoveryCount   = new Trend('reconnect_recovery_count');

const connectSuccess           = new Rate('reconnect_connect_success');
const connectDuration          = new Trend('reconnect_connect_duration', true);
const reconnectCycles          = new Counter('reconnect_total_cycles');
const reconnectFailures        = new Counter('reconnect_failures');

const massReconnectOk          = new Rate('mass_reconnect_success');
const massReconnectDur         = new Trend('mass_reconnect_duration', true);

const postReconnectCrudOk      = new Rate('post_reconnect_crud_success');
const postReconnectCrudDur     = new Trend('post_reconnect_crud_duration', true);

const totalErrors              = new Counter('reconnect_total_errors');

// Phase 시간 (초)
const P1 = 15, P2 = 90, P3 = 90, P4 = 90, P5 = 60, P6 = 15;

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus', vus: vu(10), duration: dur(P1),
            exec: 'warmup', tags: { phase: '1_warmup' },
        },
        simple_reconnect: {
            executor: 'constant-vus', vus: vu(50), duration: dur(P2),
            startTime: startAfter([P1]), exec: 'simpleReconnect', tags: { phase: '2_simple_reconnect' },
        },
        repeat_reconnect: {
            executor: 'constant-vus', vus: vu(30), duration: dur(P3),
            startTime: startAfter([P1, P2]), exec: 'repeatReconnect', tags: { phase: '3_repeat_reconnect' },
        },
        mass_reconnect: {
            executor: 'ramping-vus', startVUs: vu(5),
            stages: [
                { duration: dur(10), target: vu(200) },
                { duration: dur(60), target: vu(200) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3]), exec: 'massReconnect', tags: { phase: '4_mass_reconnect' },
        },
        post_reconnect_crud: {
            executor: 'constant-vus', vus: vu(50), duration: dur(P5),
            startTime: startAfter([P1, P2, P3, P4]), exec: 'postReconnectCrud', tags: { phase: '5_post_crud' },
        },
        cooldown: {
            executor: 'constant-vus', vus: vu(5), duration: dur(P6),
            startTime: startAfter([P1, P2, P3, P4, P5]), exec: 'warmup', tags: { phase: '6_cooldown' },
        },
    },
    thresholds: {
        'reconnect_recovery_rate':      ['rate>0.60'],
        'reconnect_recovery_latency':   ['p(95)<5000'],
        'reconnect_connect_success':    ['rate>0.95'],
        'mass_reconnect_success':       ['rate>0.90'],
        'mass_reconnect_duration':      ['p(95)<3000'],
        'post_reconnect_crud_success':  ['rate>0.98'],
        'post_reconnect_crud_duration': ['p(95)<500'],
    },
};

/** 알림 생성 + 에러 기록 */
function doCreate(userId, type) {
    const result = createNotification(userId, type);
    if (!result.ok) { totalErrors.add(1); return null; }
    return result;
}

// ── Phase 1: Warmup ──
export function warmup() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    http.get(`${BASE_URL}/api/v1/notifications?size=5`, {
        headers: headers(token), tags: { name: 'warmup' },
    });
    sleep(0.5);
}

// ── Phase 2: 단순 재연결 ──
// 흐름: 알림 생성 → 1s 대기(연결 없음) → SSE 연결 → Recovery가 전달 → ID 검증
export function simpleReconnect() {
    const user = vuUser(__VU);
    const token = generateJWT(user);

    const created = doCreate(user.userId, 'LIKE');
    if (!created) { sleep(2); return; }

    sleep(1); // 연결 없이 대기 → sse_sent=false 유지

    const reconnectStart = Date.now();
    const result = sseSubscribe(token, 5, 1, 'sse_reconnect');

    connectSuccess.add(result.connected);
    if (result.connectDuration > 0) connectDuration.add(result.connectDuration);

    if (!result.connected) {
        reconnectFailures.add(1);
        totalErrors.add(1);
        sleep(1);
        return;
    }

    const recovered = result.notifEvents.some(e => e.notificationId === created.notificationId);
    reconnectRecoveryRate.add(recovered);
    reconnectRecoveryCount.add(result.notifEvents.length);

    if (recovered) {
        reconnectRecoveryLatency.add(Date.now() - reconnectStart);
    }

    reconnectCycles.add(1);
    sleep(0.5);
}

// ── Phase 3: 반복 연결/해제 (3 사이클) ──
export function repeatReconnect() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const cycles = 3;

    for (let i = 0; i < cycles; i++) {
        const created = doCreate(user.userId, ['LIKE', 'CHAT', 'REFEED'][i % 3]);
        if (!created) { sleep(1); continue; }

        sleep(0.8); // 끊김 시뮬레이션

        const result = sseSubscribe(token, 4, 1, 'sse_reconnect');
        reconnectCycles.add(1);
        connectSuccess.add(result.connected);
        if (result.connectDuration > 0) connectDuration.add(result.connectDuration);

        if (!result.connected) {
            reconnectFailures.add(1);
            continue;
        }

        const recovered = result.notifEvents.some(e => e.notificationId === created.notificationId);
        reconnectRecoveryRate.add(recovered);
        reconnectRecoveryCount.add(result.notifEvents.length);

        sleep(0.3);
    }

    sleep(0.5);
}

// ── Phase 4: 대규모 동시 재연결 ──
export function massReconnect() {
    const user = vuUser(__VU);
    const token = generateJWT(user);

    doCreate(user.userId, 'SETTLEMENT');
    sleep(0.5);

    const result = sseSubscribe(token, 5, 1, 'sse_reconnect');

    massReconnectOk.add(result.connected);
    massReconnectDur.add(result.duration);

    if (!result.connected) {
        totalErrors.add(1);
    }

    sleep(0.3);
}

// ── Phase 5: 재연결 후 CRUD 정상 동작 ──
export function postReconnectCrud() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    // SSE 짧게 연결 후 끊기
    sseSubscribe(token, 2, 1, 'sse_reconnect');
    sleep(0.3);

    // CRUD 호출
    const ops = [
        () => http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: hdrs, tags: { name: 'post_list' },
        }),
        () => http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs, tags: { name: 'post_unread' },
        }),
    ];

    for (const op of ops) {
        const res = op();
        postReconnectCrudOk.add(res.status === 200);
        postReconnectCrudDur.add(res.timings.duration);
        if (res.status !== 200) totalErrors.add(1);
        sleep(0.1);
    }

    const ids = fetchNotificationIds(token, 5);
    if (ids.length > 0) {
        const res = http.put(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
            headers: hdrs, tags: { name: 'post_mark' },
        });
        postReconnectCrudOk.add(res.status === 200);
        postReconnectCrudDur.add(res.timings.duration);
    }

    sleep(0.5);
}

// ── 리포트 ──
export function handleSummary(data) {
    const m = data.metrics;

    const lines = [
        '',
        '╔══════════════════════════════════════════════════════════════╗',
        '║     알림 SSE 재연결 & 복구 테스트 리포트 (xk6-sse)          ║',
        '╚══════════════════════════════════════════════════════════════╝',
        '',
        '┌─────────────────────────────────────────────────────────────┐',
        '│ 1. 재연결 복구 성능                                         │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ 복구 성공률:      ${pad(pct(m.reconnect_recovery_rate?.values?.rate), 10)}                       │`,
        `│ 복구 지연:        p50=${pad(num(m.reconnect_recovery_latency?.values?.med), 8)} p95=${pad(num(m.reconnect_recovery_latency?.values?.['p(95)']), 8)} ms │`,
        `│ 복구 알림 수:     avg=${pad(num(m.reconnect_recovery_count?.values?.avg), 8)}                  │`,
        '├─────────────────────────────────────────────────────────────┤',
        '│ 2. 연결 안정성                                              │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ 연결 성공률:      ${pad(pct(m.reconnect_connect_success?.values?.rate), 10)}                       │`,
        `│ 연결 시간:        p50=${pad(num(m.reconnect_connect_duration?.values?.med), 8)} p95=${pad(num(m.reconnect_connect_duration?.values?.['p(95)']), 8)} ms │`,
        `│ 연결/해제 사이클: ${pad(num(m.reconnect_total_cycles?.values?.count, 0), 10)} 회                     │`,
        `│ 연결 실패:        ${pad(num(m.reconnect_failures?.values?.count, 0), 10)} 회                     │`,
        '├─────────────────────────────────────────────────────────────┤',
        '│ 3. 대규모 동시 재연결                                       │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ 동시 연결 성공:   ${pad(pct(m.mass_reconnect_success?.values?.rate), 10)}                       │`,
        `│ 동시 연결 시간:   p50=${pad(num(m.mass_reconnect_duration?.values?.med), 8)} p95=${pad(num(m.mass_reconnect_duration?.values?.['p(95)']), 8)} ms │`,
        '├─────────────────────────────────────────────────────────────┤',
        '│ 4. 재연결 후 CRUD                                           │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ CRUD 성공률:      ${pad(pct(m.post_reconnect_crud_success?.values?.rate), 10)}                       │`,
        `│ CRUD 응답시간:    p50=${pad(num(m.post_reconnect_crud_duration?.values?.med), 8)} p95=${pad(num(m.post_reconnect_crud_duration?.values?.['p(95)']), 8)} ms │`,
        `│ 총 에러:          ${pad(num(m.reconnect_total_errors?.values?.count, 0), 10)} 건                     │`,
        '└─────────────────────────────────────────────────────────────┘',
        '',
    ];

    console.log(lines.join('\n'));
    return {};
}
