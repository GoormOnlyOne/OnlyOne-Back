// =============================================================
// 알림 SSE End-to-End 전달 검증 테스트 (xk6-sse 모듈)
// =============================================================
// 실행: ./k6-tests/run-loadtest.sh notif-sse-e2e
//
// 검증 포인트:
//   1. Recovery: 알림 생성(미연결) → SSE 연결 → sse_sent=false 알림 수신
//   2. Live: SSE 연결 유지 중 → 알림 생성 → BatchProcessor 100ms 내 전달
//   3. ID 매칭: 생성한 notificationId가 SSE event의 notificationId와 일치
//   4. 중복 방지: 동일 notification이 2번 이상 전달되지 않음
//
// ⚠️ 유저 매핑 핵심:
//   SSE는 userId별 연결. 알림도 targetUserId로 전달.
//   → listener와 creator가 반드시 동일 userId를 공유해야 전달률 측정 가능.
//   → LIVE_POOL 크기로 공유 유저 수를 제어 (기본: listener VU 수와 동일)
//
// Phase 구성 (~7분):
// ┌────────┬─────────────────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                             │ VU   │ 시간  │
// ├────────┼─────────────────────────────────────┼──────┼───────┤
// │ 1      │ Warmup                              │ 10   │ 15s   │
// │ 2      │ Recovery 단건 (생성→대기→연결→검증)   │ 50   │ 90s   │
// │ 3      │ Recovery 다건 (3종 타입)              │ 50   │ 90s   │
// │ 4      │ Live (listener 50 + creator 50)      │ 100  │ 2m    │
// │ 5      │ Heavy Live (listener 100 + creator 100)│ 200 │ 90s   │
// │ 6      │ Cooldown                             │ 5    │ 15s   │
// └────────┴─────────────────────────────────────┴──────┴───────┘
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, vu, dur, startAfter } from '../lib/common.js';
import { vuUser, createNotification, pad, num, pct } from './helpers.js';
import { sseSubscribe } from './sse-helpers.js';

// ── 커스텀 메트릭 ──
const recoveryDelivered  = new Rate('sse_recovery_delivered');
const recoveryLatency    = new Trend('sse_recovery_latency', true);
const recoveryEventCount = new Trend('sse_recovery_event_count');

const liveDelivered      = new Rate('sse_live_delivered');
const liveLatency        = new Trend('sse_live_latency', true);
const liveEventCount     = new Trend('sse_live_event_count');

const eventIdMatch       = new Rate('sse_event_id_match');
const eventDuplicate     = new Counter('sse_event_duplicates');

const sseConnectOk       = new Rate('sse_connect_success');
const sseConnectDur      = new Trend('sse_connect_duration', true);

const createOk           = new Rate('sse_create_success');
const createDur          = new Trend('sse_create_duration', true);

const totalErrors        = new Counter('sse_e2e_total_errors');

// ── 설정 ──
// LIVE_POOL: listener VU와 creator VU가 공유하는 유저 풀 크기.
// listener VU N이면 userId 1~N, creator VU M이면 userId 1~M → 교집합으로 매칭.
// 풀이 작을수록 매칭 확률 높음. 기본값은 listener VU 수와 동일.
const LIVE_POOL = parseInt(__ENV.LIVE_POOL || '0') || vu(50);

// Phase 시간 (초)
const P1 = 15, P2 = 90, P3 = 90, P4 = 120, P5 = 90, P6 = 15;

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus', vus: vu(10), duration: dur(P1),
            exec: 'warmup', tags: { phase: '1_warmup' },
        },
        recovery_single: {
            executor: 'constant-vus', vus: vu(50), duration: dur(P2),
            startTime: startAfter([P1]), exec: 'recoverySingle', tags: { phase: '2_recovery_single' },
        },
        recovery_multi: {
            executor: 'constant-vus', vus: vu(50), duration: dur(P3),
            startTime: startAfter([P1, P2]), exec: 'recoveryMulti', tags: { phase: '3_recovery_multi' },
        },
        // Phase 4: Live — listener와 creator가 동일 유저풀(LIVE_POOL) 공유
        live_listeners: {
            executor: 'constant-vus', vus: vu(50), duration: dur(P4),
            startTime: startAfter([P1, P2, P3]), exec: 'liveListener', tags: { phase: '4_live' },
        },
        live_creators: {
            executor: 'constant-vus', vus: vu(50), duration: dur(P4),
            startTime: startAfter([P1, P2, P3], 7), exec: 'liveCreator', tags: { phase: '4_live' },
        },
        heavy_listeners: {
            executor: 'constant-vus', vus: vu(100), duration: dur(P5),
            startTime: startAfter([P1, P2, P3, P4]), exec: 'liveListener', tags: { phase: '5_heavy_live' },
        },
        heavy_creators: {
            executor: 'constant-vus', vus: vu(100), duration: dur(P5),
            startTime: startAfter([P1, P2, P3, P4], 7), exec: 'liveCreator', tags: { phase: '5_heavy_live' },
        },
        cooldown: {
            executor: 'constant-vus', vus: vu(5), duration: dur(P6),
            startTime: startAfter([P1, P2, P3, P4, P5]), exec: 'warmup', tags: { phase: '6_cooldown' },
        },
    },
    thresholds: {
        'sse_recovery_delivered':   ['rate>0.70'],
        'sse_recovery_latency':     ['p(95)<5000'],
        'sse_recovery_event_count': ['avg>0.5'],
        'sse_live_delivered':       ['rate>0.50'],
        'sse_live_latency':         ['p(95)<3000'],
        'sse_event_id_match':       ['rate>0.90'],
        'sse_connect_success':      ['rate>0.95'],
        'sse_create_success':       ['rate>0.98'],
    },
};

// ── 유저 매핑 ──

/**
 * Live/Heavy Phase용 유저 ID.
 * listener VU와 creator VU가 동일 유저풀(1~LIVE_POOL)을 공유하여
 * SSE 연결 중인 유저에게 알림이 생성되도록 보장.
 */
function liveUserId() {
    return ((__VU - 1) % LIVE_POOL) + 1;
}

/** 알림 생성 + 메트릭 기록 */
function doCreate(userId, type) {
    const result = createNotification(userId, type);
    createDur.add(result.duration);
    createOk.add(result.ok);
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

// ── Phase 2: Recovery 단건 ──
// 흐름: 알림 생성(미연결) → DB에 sse_sent=false → 0.5s 대기 → SSE 연결
//       → MissedNotificationRecovery가 sse_sent=false 최대 50건 전달
//       → 생성한 notificationId가 수신 이벤트에 포함되는지 검증
export function recoverySingle() {
    const user = vuUser(__VU);
    const token = generateJWT(user);

    // 1) 알림 생성 (SSE 미연결 상태 → sse_sent=false로 DB에만 저장)
    const created = doCreate(user.userId, 'LIKE');
    if (!created) { sleep(1); return; }

    // 2) DB 커밋 대기 (BatchProcessor가 AFTER_COMMIT으로 동작하므로)
    sleep(0.5);

    // 3) SSE 연결 → MissedNotificationRecovery 트리거
    //    maxEvents=50: 서버 MAX_RECOVERY_SIZE와 동일
    const result = sseSubscribe(token, 5, 50);
    sseConnectOk.add(result.connected);
    if (result.connectDuration > 0) sseConnectDur.add(result.connectDuration);
    if (!result.connected) { sleep(0.5); return; }

    // 4) notificationId 매칭 검증
    const received = result.notifEvents.some(e => e.notificationId === created.notificationId);
    recoveryDelivered.add(received);
    recoveryEventCount.add(result.notifEvents.length);

    if (received) {
        const matched = result.notifEvents.find(e => e.notificationId === created.notificationId);
        if (matched && matched.sentAtEpochMs > 0) {
            recoveryLatency.add(matched.sentAtEpochMs - created.serverTimestamp);
        }
        eventIdMatch.add(1);
    } else {
        eventIdMatch.add(0);
    }

    // 5) 중복 검사
    const ids = result.notifEvents.map(e => e.notificationId);
    const unique = new Set(ids);
    if (ids.length > unique.size) eventDuplicate.add(ids.length - unique.size);

    sleep(0.5);
}

// ── Phase 3: Recovery 다건 (3종 타입) ──
export function recoveryMulti() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const types = ['LIKE', 'CHAT', 'REFEED'];
    const createdIds = [];

    for (const type of types) {
        const created = doCreate(user.userId, type);
        if (created) createdIds.push(created);
        sleep(0.1);
    }
    if (createdIds.length === 0) { sleep(1); return; }

    sleep(0.8);

    const result = sseSubscribe(token, 6, 50);
    sseConnectOk.add(result.connected);
    if (result.connectDuration > 0) sseConnectDur.add(result.connectDuration);
    if (!result.connected) { sleep(0.5); return; }

    const receivedIds = new Set(result.notifEvents.map(e => e.notificationId));
    let matchCount = 0;
    for (const created of createdIds) {
        const found = receivedIds.has(created.notificationId);
        eventIdMatch.add(found ? 1 : 0);
        if (found) matchCount++;
    }

    recoveryDelivered.add(matchCount / createdIds.length >= 0.5);
    recoveryEventCount.add(result.notifEvents.length);

    const allIds = result.notifEvents.map(e => e.notificationId);
    if (allIds.length > receivedIds.size) eventDuplicate.add(allIds.length - receivedIds.size);

    sleep(0.5);
}

// ── Phase 4-5: Live 리스너 (SSE 실시간 수신) ──
// liveUserId()로 listener와 creator가 동일 유저풀 공유
export function liveListener() {
    const userId = liveUserId();
    const user = { userId, kakaoId: 1000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
    const token = generateJWT(user);

    // SSE 연결 유지 — 서버 timeout(30s) 또는 이벤트 10건까지 대기
    const result = sseSubscribe(token, 10, 10);
    sseConnectOk.add(result.connected);
    if (result.connectDuration > 0) sseConnectDur.add(result.connectDuration);

    const notifCount = result.notifEvents.length;
    liveEventCount.add(notifCount);
    liveDelivered.add(notifCount > 0);

    for (const evt of result.notifEvents) {
        if (evt.sentAtEpochMs > 0) {
            const delay = Date.now() - evt.sentAtEpochMs;
            if (delay > 0 && delay < 30000) liveLatency.add(delay);
        }
    }

    const ids = result.notifEvents.map(e => e.notificationId);
    const unique = new Set(ids);
    if (ids.length > unique.size) eventDuplicate.add(ids.length - unique.size);

    sleep(0.5);
}

// ── Phase 4-5: Live 생성자 ──
// liveUserId()로 listener가 연결 중인 유저에게 알림 생성
export function liveCreator() {
    const targetUserId = liveUserId();
    const types = ['LIKE', 'CHAT', 'REFEED', 'SETTLEMENT'];
    const type = types[Math.floor(Math.random() * types.length)];

    doCreate(targetUserId, type);
    // creator는 listener보다 느리게 → listener가 이벤트 받을 시간 확보
    sleep(1 + Math.random() * 2);
}

// ── 리포트 ──
export function handleSummary(data) {
    const m = data.metrics;

    const lines = [
        '',
        '╔══════════════════════════════════════════════════════════════╗',
        '║          알림 SSE E2E 전달 검증 리포트 (xk6-sse)            ║',
        '╚══════════════════════════════════════════════════════════════╝',
        '',
        '┌─────────────────────────────────────────────────────────────┐',
        '│ 1. Recovery 전달 (미연결 → 생성 → 연결 → 수신)              │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ 전달 성공률:  ${pad(pct(m.sse_recovery_delivered?.values?.rate), 10)}                           │`,
        `│ 전달 지연:    p50=${pad(num(m.sse_recovery_latency?.values?.med), 8)} p95=${pad(num(m.sse_recovery_latency?.values?.['p(95)']), 8)} ms │`,
        `│ 수신 이벤트:  avg=${pad(num(m.sse_recovery_event_count?.values?.avg), 8)}                      │`,
        '├─────────────────────────────────────────────────────────────┤',
        '│ 2. Live 전달 (연결 유지 중 → 생성 → 즉시 수신)              │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ 전달 성공률:  ${pad(pct(m.sse_live_delivered?.values?.rate), 10)}                           │`,
        `│ 전달 지연:    p50=${pad(num(m.sse_live_latency?.values?.med), 8)} p95=${pad(num(m.sse_live_latency?.values?.['p(95)']), 8)} ms │`,
        `│ 수신 이벤트:  avg=${pad(num(m.sse_live_event_count?.values?.avg), 8)}                      │`,
        '├─────────────────────────────────────────────────────────────┤',
        '│ 3. 이벤트 무결성                                            │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ ID 매치율:    ${pad(pct(m.sse_event_id_match?.values?.rate), 10)}                           │`,
        `│ 중복 이벤트:  ${pad(num(m.sse_event_duplicates?.values?.count, 0), 10)} 건                       │`,
        '├─────────────────────────────────────────────────────────────┤',
        '│ 4. 인프라 성능                                              │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ SSE 연결성공: ${pad(pct(m.sse_connect_success?.values?.rate), 10)}                           │`,
        `│ SSE 연결시간: p50=${pad(num(m.sse_connect_duration?.values?.med), 8)} p95=${pad(num(m.sse_connect_duration?.values?.['p(95)']), 8)} ms │`,
        `│ 알림 생성:    p50=${pad(num(m.sse_create_duration?.values?.med), 8)} p95=${pad(num(m.sse_create_duration?.values?.['p(95)']), 8)} ms │`,
        `│ 생성 성공률:  ${pad(pct(m.sse_create_success?.values?.rate), 10)}                           │`,
        `│ 총 에러:      ${pad(num(m.sse_e2e_total_errors?.values?.count, 0), 10)} 건                       │`,
        '└─────────────────────────────────────────────────────────────┘',
        '',
    ];

    console.log(lines.join('\n'));
    return {};
}
