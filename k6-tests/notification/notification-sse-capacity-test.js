// =============================================================
// 알림 SSE 동시 연결 수용량 & CRUD 영향도 테스트 (xk6-sse 모듈)
// =============================================================
// 실행: ./k6-tests/run-loadtest.sh notif-sse-capacity
//
// SSE 연결 수(50→200→500)를 단계적으로 올리며
// 동시 CRUD 응답시간 저하를 측정한다.
// =============================================================

import http from 'k6/http';
import sse from 'k6/x/sse';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, fetchNotificationIds, BASE_URL, SSE_SUBSCRIBE_URL, vu, dur, startAfter } from '../lib/common.js';
import { vuUser, pad, num, pct } from './helpers.js';

// ── 커스텀 메트릭 — Phase별 CRUD ──

const crudBaselineDur = new Trend('crud_baseline_duration', true);
const crudBaselineOk  = new Rate('crud_baseline_success');
const crudSse50Dur    = new Trend('crud_sse50_duration', true);
const crudSse50Ok     = new Rate('crud_sse50_success');
const crudSse200Dur   = new Trend('crud_sse200_duration', true);
const crudSse200Ok    = new Rate('crud_sse200_success');
const crudSse500Dur   = new Trend('crud_sse500_duration', true);
const crudSse500Ok    = new Rate('crud_sse500_success');
const crudRecoveryDur = new Trend('crud_recovery_duration', true);
const crudRecoveryOk  = new Rate('crud_recovery_success');

const sseConnectDur   = new Trend('sse_connect_duration', true);
const sseConnectOk    = new Rate('sse_connect_success');
const sseConnections  = new Counter('sse_total_connections');
const sseEventCount   = new Trend('sse_event_count');

const listDur         = new Trend('capacity_list_duration', true);
const unreadDur       = new Trend('capacity_unread_duration', true);
const markDur         = new Trend('capacity_mark_duration', true);
const deleteDur       = new Trend('capacity_delete_duration', true);
const markAllDur      = new Trend('capacity_markall_duration', true);

const totalErrors     = new Counter('capacity_total_errors');

// ── 설정 (환경변수 오버라이드 가능) ──
const VALID_USER_COUNT = parseInt(__ENV.VALID_USER_COUNT || '1000');

// Phase 시간 (초), CRUD VU, SSE VU별 설정
const P1 = 15, P2 = 90, P3 = 90, P4 = 90, P5 = 90, P6 = 60, P7 = 15;
const CRUD_VU = parseInt(__ENV.CRUD_VU || '80');
const SSE_50  = parseInt(__ENV.SSE_50  || '50');
const SSE_200 = parseInt(__ENV.SSE_200 || '200');
const SSE_500 = parseInt(__ENV.SSE_500 || '500');

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus', vus: vu(10), duration: dur(P1),
            exec: 'warmup', tags: { phase: '1_warmup' },
        },
        crud_baseline: {
            executor: 'constant-vus', vus: vu(CRUD_VU), duration: dur(P2),
            startTime: startAfter([P1]), exec: 'crudBaseline', tags: { phase: '2_baseline' },
        },
        sse_50: {
            executor: 'constant-vus', vus: vu(SSE_50), duration: dur(P3),
            startTime: startAfter([P1, P2]), exec: 'holdSSE', tags: { phase: '3_sse50' },
        },
        crud_sse50: {
            executor: 'constant-vus', vus: vu(CRUD_VU), duration: dur(P3),
            startTime: startAfter([P1, P2]), exec: 'crudWithSse50', tags: { phase: '3_sse50' },
        },
        sse_200: {
            executor: 'constant-vus', vus: vu(SSE_200), duration: dur(P4),
            startTime: startAfter([P1, P2, P3]), exec: 'holdSSE', tags: { phase: '4_sse200' },
        },
        crud_sse200: {
            executor: 'constant-vus', vus: vu(CRUD_VU), duration: dur(P4),
            startTime: startAfter([P1, P2, P3]), exec: 'crudWithSse200', tags: { phase: '4_sse200' },
        },
        sse_500: {
            executor: 'constant-vus', vus: vu(SSE_500), duration: dur(P5),
            startTime: startAfter([P1, P2, P3, P4]), exec: 'holdSSE', tags: { phase: '5_sse500' },
        },
        crud_sse500: {
            executor: 'constant-vus', vus: vu(CRUD_VU), duration: dur(P5),
            startTime: startAfter([P1, P2, P3, P4]), exec: 'crudWithSse500', tags: { phase: '5_sse500' },
        },
        crud_recovery: {
            executor: 'constant-vus', vus: vu(CRUD_VU), duration: dur(P6),
            startTime: startAfter([P1, P2, P3, P4, P5]), exec: 'crudRecovery', tags: { phase: '6_recovery' },
        },
        cooldown: {
            executor: 'constant-vus', vus: vu(5), duration: dur(P7),
            startTime: startAfter([P1, P2, P3, P4, P5, P6]), exec: 'warmup', tags: { phase: '7_cooldown' },
        },
    },
    thresholds: {
        'crud_baseline_duration':  ['p(95)<500'],
        'crud_sse50_duration':     ['p(95)<800'],
        'crud_sse200_duration':    ['p(95)<2000'],
        'crud_sse500_duration':    ['p(95)<5000'],
        'crud_recovery_duration':  ['p(95)<500'],
        'crud_baseline_success':   ['rate>0.99'],
        'crud_sse50_success':      ['rate>0.98'],
        'crud_sse200_success':     ['rate>0.95'],
        'crud_sse500_success':     ['rate>0.90'],
        'crud_recovery_success':   ['rate>0.99'],
        'sse_connect_success':     ['rate>0.90'],
    },
};

// ── SSE 연결 유지 (Phase 3-5) ──
// xk6-sse는 blocking이므로 서버 SSE timeout(30s)까지 연결 유지됨.
export function holdSSE() {
    const user = vuUser(__VU, VALID_USER_COUNT);
    const token = generateJWT(user);
    let connected = false;
    let eventCount = 0;
    const startTime = Date.now();

    const params = {
        method: 'GET',
        headers: {
            'Authorization': `Bearer ${token}`,
            'Accept': 'text/event-stream',
            'Cache-Control': 'no-cache',
        },
        tags: { name: 'sse_subscribe' },
    };

    const response = sse.open(SSE_SUBSCRIBE_URL, params, function (client) {
        client.on('open', function () {
            connected = true;
            sseConnectDur.add(Date.now() - startTime);
        });

        client.on('event', function (event) {
            if (event.name === 'notification') {
                eventCount++;
            }
        });

        client.on('error', function (_) {});

        // 서버 SSE timeout(30s)에 의존하여 자연스럽게 종료.
        // VU가 재시작되며 다시 연결 → 지속적 SSE 부하 유지.
    });

    sseConnectOk.add(connected || (response && response.status === 200));
    sseConnections.add(1);
    sseEventCount.add(eventCount);

    if (!connected && !(response && response.status === 200)) {
        totalErrors.add(1);
    }

    sleep(0.5);
}

// ── CRUD 혼합 ──
function doCrudMix(durMetric, okMetric) {
    const user = vuUser(__VU, VALID_USER_COUNT);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();

    let res;
    if (roll < 0.35) {
        res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: hdrs, tags: { name: 'cap_list' },
        });
        listDur.add(res.timings.duration);
    } else if (roll < 0.60) {
        res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs, tags: { name: 'cap_unread' },
        });
        unreadDur.add(res.timings.duration);
    } else if (roll < 0.80) {
        const ids = fetchNotificationIds(token, 10);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            res = http.put(`${BASE_URL}/api/v1/notifications/${id}/read`, null, {
                headers: hdrs, tags: { name: 'cap_mark' },
            });
            markDur.add(res.timings.duration);
        } else {
            res = { status: 200, timings: { duration: 0 } };
        }
    } else if (roll < 0.92) {
        const ids = fetchNotificationIds(token, 10);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            res = http.del(`${BASE_URL}/api/v1/notifications/${id}`, null, {
                headers: hdrs, tags: { name: 'cap_delete' },
            });
            deleteDur.add(res.timings.duration);
        } else {
            res = { status: 200, timings: { duration: 0 } };
        }
    } else {
        res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: hdrs, tags: { name: 'cap_markall' },
        });
        markAllDur.add(res.timings.duration);
    }

    const ok = res.status === 200;
    durMetric.add(res.timings.duration);
    okMetric.add(ok);
    if (!ok) totalErrors.add(1);

    sleep(0.1 + Math.random() * 0.2);
}

// ── Phase별 함수 ──
export function warmup() {
    const user = vuUser(__VU, VALID_USER_COUNT);
    const token = generateJWT(user);
    http.get(`${BASE_URL}/api/v1/notifications?size=5`, {
        headers: headers(token), tags: { name: 'warmup' },
    });
    sleep(0.5);
}

export function crudBaseline()    { doCrudMix(crudBaselineDur, crudBaselineOk); }
export function crudWithSse50()   { doCrudMix(crudSse50Dur, crudSse50Ok); }
export function crudWithSse200()  { doCrudMix(crudSse200Dur, crudSse200Ok); }
export function crudWithSse500()  { doCrudMix(crudSse500Dur, crudSse500Ok); }
export function crudRecovery()    { doCrudMix(crudRecoveryDur, crudRecoveryOk); }

// ── 리포트 ──
export function handleSummary(data) {
    const m = data.metrics;

    function phaseRow(label, durKey, okKey) {
        const p50 = num(m[durKey]?.values?.med);
        const p95 = num(m[durKey]?.values?.['p(95)']);
        const p99 = num(m[durKey]?.values?.['p(99)']);
        const max = num(m[durKey]?.values?.max);
        const rate = pct(m[okKey]?.values?.rate);
        return `│ ${pad(label, 18)} │ ${pad(p50, 8)} │ ${pad(p95, 8)} │ ${pad(p99, 8)} │ ${pad(max, 8)} │ ${pad(rate, 7)} │`;
    }

    const lines = [
        '',
        '╔══════════════════════════════════════════════════════════════════════╗',
        '║    알림 SSE 동시 연결 수용량 & CRUD 영향도 리포트 (xk6-sse)        ║',
        '╚══════════════════════════════════════════════════════════════════════╝',
        '',
        '┌────────────────────┬──────────┬──────────┬──────────┬──────────┬─────────┐',
        '│ Phase              │ p50 (ms) │ p95 (ms) │ p99 (ms) │ max (ms) │ 성공률  │',
        '├────────────────────┼──────────┼──────────┼──────────┼──────────┼─────────┤',
        phaseRow('Baseline (SSE 0)', 'crud_baseline_duration', 'crud_baseline_success'),
        phaseRow('CRUD + SSE 50',   'crud_sse50_duration',     'crud_sse50_success'),
        phaseRow('CRUD + SSE 200',  'crud_sse200_duration',    'crud_sse200_success'),
        phaseRow('CRUD + SSE 500',  'crud_sse500_duration',    'crud_sse500_success'),
        phaseRow('Recovery',        'crud_recovery_duration',  'crud_recovery_success'),
        '└────────────────────┴──────────┴──────────┴──────────┴──────────┴─────────┘',
        '',
        '┌─────────────────────────────────────────────────────────────┐',
        '│ SSE 연결 통계                                               │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ 총 연결 시도:   ${pad(num(m.sse_total_connections?.values?.count, 0), 10)} 회                     │`,
        `│ 연결 성공률:    ${pad(pct(m.sse_connect_success?.values?.rate), 10)}                          │`,
        `│ 연결 시간:      p50=${pad(num(m.sse_connect_duration?.values?.med), 8)} p95=${pad(num(m.sse_connect_duration?.values?.['p(95)']), 8)} ms │`,
        `│ 수신 이벤트:    avg=${pad(num(m.sse_event_count?.values?.avg), 8)}                          │`,
        '├─────────────────────────────────────────────────────────────┤',
        '│ 엔드포인트별 세부                                           │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ list:     p50=${pad(num(m.capacity_list_duration?.values?.med), 8)} p95=${pad(num(m.capacity_list_duration?.values?.['p(95)']), 8)} ms │`,
        `│ unread:   p50=${pad(num(m.capacity_unread_duration?.values?.med), 8)} p95=${pad(num(m.capacity_unread_duration?.values?.['p(95)']), 8)} ms │`,
        `│ mark:     p50=${pad(num(m.capacity_mark_duration?.values?.med), 8)} p95=${pad(num(m.capacity_mark_duration?.values?.['p(95)']), 8)} ms │`,
        `│ delete:   p50=${pad(num(m.capacity_delete_duration?.values?.med), 8)} p95=${pad(num(m.capacity_delete_duration?.values?.['p(95)']), 8)} ms │`,
        `│ mark-all: p50=${pad(num(m.capacity_markall_duration?.values?.med), 8)} p95=${pad(num(m.capacity_markall_duration?.values?.['p(95)']), 8)} ms │`,
        `│ 총 에러:  ${pad(num(m.capacity_total_errors?.values?.count, 0), 10)} 건                          │`,
        '└─────────────────────────────────────────────────────────────┘',
        '',
    ];

    console.log(lines.join('\n'));
    return {};
}
