// =============================================================
// Servlet SSE vs WebFlux SSE 성능 비교 부하 테스트
// =============================================================
//
// 목적:
//   Servlet SseEmitter (스레드 기반 async) vs WebFlux Flux<SSE> (논블로킹)
//   동일 부하에서 성능 차이를 정량적으로 비교
//
// 비교 포인트:
//   1. SSE 연결 수립 시간 (connect latency)
//   2. SSE 동시 연결 용량 (max concurrent connections)
//   3. SSE 연결 중 CRUD API 응답 시간 (스레드 경합 탐지)
//   4. SSE 연결 해제 후 CRUD 회복 속도
//
// 인프라 튜닝 탐지 지점:
//   - Tomcat max-threads / accept-count → SSE 동시 연결이 스레드풀 점유 시
//   - SseEmitter executor-permits (sse-executor-permits) → 이벤트 전송 병목
//   - HikariCP max pool → SSE + CRUD 동시 DB 접근 시 커넥션 고갈
//   - JVM 메모리 (ZGC/G1 tuning) → 대량 SSE 연결의 힙 사용량 차이
//   - Reactor event-loop threads → WebFlux 쪽 이벤트 루프 포화
//   - Redis connection pool → unread-count 캐시 접근 경합
//
// 실행 방법:
//   1) Servlet SSE 테스트:
//      서버 설정: app.notification.delivery=sse, storage=mongodb
//      MSYS_NO_PATHCONV=1 docker run --rm -i --network=host \
//        -v "$(pwd)/k6-tests:/scripts" \
//        -e SSE_MODE=servlet \
//        grafana/k6 run /scripts/notification-webflux-comparison.js
//
//   2) WebFlux SSE 테스트:
//      서버 설정: app.notification.delivery=webflux, storage=mongodb
//      MSYS_NO_PATHCONV=1 docker run --rm -i --network=host \
//        -v "$(pwd)/k6-tests:/scripts" \
//        -e SSE_MODE=webflux \
//        grafana/k6 run /scripts/notification-webflux-comparison.js
//
//   3) 결과 비교:
//      두 JSON 결과를 비교하여 각 Phase별 CRUD 응답 시간 차이 확인
//
// Phase 구성 (~10분, 최대 380 VUs):
//   ┌─────┬──────────────────────────────────────┬──────────┬────────┐
//   │  #  │ Phase                                │ VU       │ 시간   │
//   ├─────┼──────────────────────────────────────┼──────────┼────────┤
//   │  1  │ CRUD Baseline (SSE 연결 없음)        │ 80       │ 1.5m   │
//   │  2  │ SSE 50 + CRUD 80 (저부하)            │ 50+80    │ 2m     │
//   │  3  │ SSE 200 + CRUD 80 (중부하)           │ 200+80   │ 2m     │
//   │  4  │ SSE 300 + CRUD 80 (고부하/포화)      │ 300+80   │ 2m     │
//   │  5  │ CRUD Recovery (SSE 해제 후)          │ 80       │ 1m     │
//   │  6  │ Cooldown                             │ 5        │ 30s    │
//   └─────┴──────────────────────────────────────┴──────────┴────────┘
//
// 핵심 비교 메트릭:
//   crud_baseline_duration  vs  crud_sse50_duration  vs  crud_sse200_duration  vs  crud_sse300_duration
//   → Servlet: SSE 증가에 따라 CRUD p95 급등 예상 (스레드풀 경합)
//   → WebFlux: SSE 증가해도 CRUD p95 안정 예상 (논블로킹)
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, sseHeaders, parseSSEEvents, fetchNotificationIds, BASE_URL } from './lib/common.js';

// ============================================
// 환경 설정
// ============================================
const SSE_MODE = __ENV.SSE_MODE || 'servlet';
const SSE_ENDPOINT = SSE_MODE === 'webflux'
    ? `${BASE_URL}/api/v1/reactive/subscribe`
    : `${BASE_URL}/api/v1/sse/subscribe`;

const VALID_USER_COUNT = 1000;
const SSE_HOLD_TIMEOUT = '25s';  // 서버 SSE timeout(30s)보다 짧게

// ============================================
// 커스텀 메트릭
// ============================================

// SSE 연결 메트릭
const sseConnectDur    = new Trend('sse_connect_duration', true);
const sseConnectOk     = new Rate('sse_connect_success');
const sseEventCount    = new Trend('sse_event_count');
const sseConnections   = new Counter('sse_total_connections');

// Phase 1: CRUD Baseline (SSE 없음)
const crudBaselineDur  = new Trend('crud_baseline_duration', true);
const crudBaselineOk   = new Rate('crud_baseline_success');

// Phase 2: CRUD + SSE 50
const crudSse50Dur     = new Trend('crud_sse50_duration', true);
const crudSse50Ok      = new Rate('crud_sse50_success');

// Phase 3: CRUD + SSE 200
const crudSse200Dur    = new Trend('crud_sse200_duration', true);
const crudSse200Ok     = new Rate('crud_sse200_success');

// Phase 4: CRUD + SSE 300
const crudSse500Dur    = new Trend('crud_sse300_duration', true);
const crudSse500Ok     = new Rate('crud_sse300_success');

// Phase 5: CRUD Recovery
const crudRecoveryDur  = new Trend('crud_recovery_duration', true);
const crudRecoveryOk   = new Rate('crud_recovery_success');

// 개별 CRUD 엔드포인트 메트릭 (전 Phase 통합)
const listDur          = new Trend('noti_list_duration', true);
const listOk           = new Rate('noti_list_success');
const unreadDur        = new Trend('noti_unread_duration', true);
const unreadOk         = new Rate('noti_unread_success');
const markDur          = new Trend('noti_mark_duration', true);
const markOk           = new Rate('noti_mark_success');
const deleteDur        = new Trend('noti_delete_duration', true);
const deleteOk         = new Rate('noti_delete_success');
const markAllDur       = new Trend('noti_markall_duration', true);
const markAllOk        = new Rate('noti_markall_success');

// 전역
const totalErrors      = new Counter('total_5xx_errors');

// ============================================
// 시나리오 타임라인
// ============================================
// Phase 1:  0s   ~ 90s    (1.5m)  CRUD baseline
// Phase 2:  95s  ~ 215s   (2m)    SSE 50 + CRUD
// Phase 3:  220s ~ 340s   (2m)    SSE 200 + CRUD
// Phase 4:  345s ~ 465s   (2m)    SSE 300 + CRUD
// Phase 5:  470s ~ 530s   (1m)    CRUD recovery
// Phase 6:  535s ~ 565s   (30s)   Cooldown
// 총: ~9.5분

export const options = {
    scenarios: {
        // ── Phase 1: CRUD Baseline ──
        crud_baseline: {
            executor: 'constant-vus',
            vus: 80,
            duration: '90s',
            exec: 'crudBaseline',
            startTime: '0s',
            tags: { phase: '1_baseline' },
        },

        // ── Phase 2: SSE 50 + CRUD 80 ──
        sse_hold_50: {
            executor: 'constant-vus',
            vus: 50,
            duration: '120s',
            exec: 'holdSSE',
            startTime: '95s',
            tags: { phase: '2_sse50' },
        },
        crud_sse50: {
            executor: 'constant-vus',
            vus: 80,
            duration: '120s',
            exec: 'crudWithSse50',
            startTime: '95s',
            tags: { phase: '2_sse50' },
        },

        // ── Phase 3: SSE 200 + CRUD 80 ──
        sse_hold_200: {
            executor: 'constant-vus',
            vus: 200,
            duration: '120s',
            exec: 'holdSSE',
            startTime: '220s',
            tags: { phase: '3_sse200' },
        },
        crud_sse200: {
            executor: 'constant-vus',
            vus: 80,
            duration: '120s',
            exec: 'crudWithSse200',
            startTime: '220s',
            tags: { phase: '3_sse200' },
        },

        // ── Phase 4: SSE 300 + CRUD 80 ──
        sse_hold_300: {
            executor: 'constant-vus',
            vus: 300,
            duration: '120s',
            exec: 'holdSSE',
            startTime: '345s',
            tags: { phase: '4_sse300' },
        },
        crud_sse300: {
            executor: 'constant-vus',
            vus: 80,
            duration: '120s',
            exec: 'crudWithSse500',
            startTime: '345s',
            tags: { phase: '4_sse300' },
        },

        // ── Phase 5: CRUD Recovery ──
        crud_recovery: {
            executor: 'constant-vus',
            vus: 80,
            duration: '60s',
            exec: 'crudRecovery',
            startTime: '470s',
            tags: { phase: '5_recovery' },
        },

        // ── Phase 6: Cooldown ──
        cooldown: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            exec: 'crudBaseline',
            startTime: '535s',
            tags: { phase: '6_cooldown' },
        },
    },
    thresholds: {
        // SSE 연결
        'sse_connect_success':       ['rate>0.90'],

        // CRUD Baseline (SSE 없는 순수 성능)
        'crud_baseline_duration':    ['p(95)<500'],
        'crud_baseline_success':     ['rate>0.98'],

        // CRUD + SSE 50 (미미한 영향 기대)
        'crud_sse50_duration':       ['p(95)<800'],
        'crud_sse50_success':        ['rate>0.95'],

        // CRUD + SSE 200 (Servlet 쪽 영향 시작 예상)
        'crud_sse200_duration':      ['p(95)<2000'],
        'crud_sse200_success':       ['rate>0.90'],

        // CRUD + SSE 300 (Servlet 쪽 심각한 영향 예상)
        'crud_sse300_duration':      ['p(95)<5000'],
        'crud_sse300_success':       ['rate>0.80'],

        // 회복
        'crud_recovery_duration':    ['p(95)<500'],
        'crud_recovery_success':     ['rate>0.98'],

        // 개별 엔드포인트
        'noti_list_duration':        ['p(95)<1000'],
        'noti_unread_duration':      ['p(95)<500'],
    },
};

// ============================================
// 유틸리티
// ============================================
function randomUser() {
    const userId = Math.floor(Math.random() * VALID_USER_COUNT) + 1;
    return { userId, kakaoId: 1000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

function vuUser(vuId) {
    const userId = ((vuId - 1) % VALID_USER_COUNT) + 1;
    return { userId, kakaoId: 1000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

// ============================================
// SSE 연결 유지 (각 SSE Phase에서 호출)
// ============================================
export function holdSSE() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = sseHeaders(token);

    const start = Date.now();
    const res = http.get(SSE_ENDPOINT, {
        headers: hdrs,
        timeout: SSE_HOLD_TIMEOUT,
        responseType: 'text',
        tags: { name: 'sse_subscribe' },
    });
    const dur = Date.now() - start;

    // SSE 스트리밍: status 200 = 정상, status 0 = k6 timeout (연결 유지 중 timeout → 정상)
    const connected = res.status === 200 || res.status === 0;
    sseConnectDur.add(dur);
    sseConnectOk.add(connected ? 1 : 0);
    sseConnections.add(1);

    // 수신 이벤트 파싱
    const events = parseSSEEvents(res.body);
    sseEventCount.add(events.length);

    if (!connected) {
        totalErrors.add(1);
    }

    sleep(0.5);  // 재연결 전 짧은 대기
}

// ============================================
// CRUD 혼합 작업 (공용)
// ============================================
function doCrudMix(user) {
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let dur, ok;

    if (roll < 0.35) {
        // 알림 목록 조회 (35%)
        const cursor = Math.random() < 0.3 ? '' : `&cursor=${Math.floor(Math.random() * 10000) + 1}`;
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20${cursor}`, {
            headers: hdrs, tags: { name: 'noti_list' },
        });
        dur = Date.now() - start;
        ok = res.status === 200;
        listDur.add(dur);
        listOk.add(ok ? 1 : 0);
        if (res.status >= 500) totalErrors.add(1);

    } else if (roll < 0.60) {
        // 안읽은 알림 수 (25%)
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: hdrs, tags: { name: 'noti_unread' },
        });
        dur = Date.now() - start;
        ok = res.status === 200;
        unreadDur.add(dur);
        unreadOk.add(ok ? 1 : 0);
        if (res.status >= 500) totalErrors.add(1);

    } else if (roll < 0.80) {
        // 알림 읽음 처리 (20%)
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const notiId = ids[Math.floor(Math.random() * ids.length)];
            const start = Date.now();
            const res = http.put(`${BASE_URL}/api/v1/notifications/${notiId}/read`, null, {
                headers: hdrs, tags: { name: 'noti_mark' },
            });
            dur = Date.now() - start;
            ok = res.status === 200;
            markDur.add(dur);
            markOk.add(ok ? 1 : 0);
            if (res.status >= 500) totalErrors.add(1);
        } else {
            dur = 0; ok = true;
        }

    } else if (roll < 0.92) {
        // 알림 삭제 (12%)
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const notiId = ids[Math.floor(Math.random() * ids.length)];
            const start = Date.now();
            const res = http.del(`${BASE_URL}/api/v1/notifications/${notiId}`, null, {
                headers: hdrs, tags: { name: 'noti_delete' },
            });
            dur = Date.now() - start;
            ok = res.status === 200;
            deleteDur.add(dur);
            deleteOk.add(ok ? 1 : 0);
            if (res.status >= 500) totalErrors.add(1);
        } else {
            dur = 0; ok = true;
        }

    } else {
        // 전체 읽음 (8%)
        const start = Date.now();
        const res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: hdrs, tags: { name: 'noti_markall' },
        });
        dur = Date.now() - start;
        ok = res.status === 200;
        markAllDur.add(dur);
        markAllOk.add(ok ? 1 : 0);
        if (res.status >= 500) totalErrors.add(1);
    }

    return { dur: dur || 0, ok: ok !== false };
}

// ============================================
// Phase 1: CRUD Baseline (SSE 없음)
// ============================================
export function crudBaseline() {
    const user = randomUser();
    const r = doCrudMix(user);
    crudBaselineDur.add(r.dur);
    crudBaselineOk.add(r.ok ? 1 : 0);
    sleep(0.1);
}

// ============================================
// Phase 2: CRUD + SSE 50 동시 연결
// ============================================
export function crudWithSse50() {
    const user = randomUser();
    const r = doCrudMix(user);
    crudSse50Dur.add(r.dur);
    crudSse50Ok.add(r.ok ? 1 : 0);
    sleep(0.1);
}

// ============================================
// Phase 3: CRUD + SSE 200 동시 연결
// ============================================
export function crudWithSse200() {
    const user = randomUser();
    const r = doCrudMix(user);
    crudSse200Dur.add(r.dur);
    crudSse200Ok.add(r.ok ? 1 : 0);
    sleep(0.1);
}

// ============================================
// Phase 4: CRUD + SSE 300 동시 연결
// ============================================
export function crudWithSse500() {
    const user = randomUser();
    const r = doCrudMix(user);
    crudSse500Dur.add(r.dur);
    crudSse500Ok.add(r.ok ? 1 : 0);
    sleep(0.1);
}

// ============================================
// Phase 5: CRUD Recovery (SSE 연결 해제 후)
// ============================================
export function crudRecovery() {
    const user = randomUser();
    const r = doCrudMix(user);
    crudRecoveryDur.add(r.dur);
    crudRecoveryOk.add(r.ok ? 1 : 0);
    sleep(0.1);
}

// ============================================
// handleSummary — A/B 비교 리포트
// ============================================
export function handleSummary(data) {
    const mode = SSE_MODE;
    const line = '='.repeat(68);
    const dash = '-'.repeat(68);

    let s = `
${line}
  Servlet SSE vs WebFlux SSE 비교 결과 [mode: ${mode}]
${line}

  SSE 엔드포인트: ${SSE_ENDPOINT}
${dash}
`;

    // SSE 연결 메트릭
    const sseConn = data.metrics['sse_connect_duration'];
    const sseOk = data.metrics['sse_connect_success'];
    const sseCnt = data.metrics['sse_total_connections'];
    s += `
[SSE 연결 성능]
  총 연결 시도:  ${sseCnt ? sseCnt.values.count : 'N/A'}
  연결 성공률:   ${sseOk ? (sseOk.values.rate * 100).toFixed(1) + '%' : 'N/A'}
  연결 시간 p50: ${sseConn ? fmt(sseConn.values['p(50)']) : 'N/A'}
  연결 시간 p95: ${sseConn ? fmt(sseConn.values['p(95)']) : 'N/A'}
  연결 시간 max: ${sseConn ? fmt(sseConn.values['max']) : 'N/A'}
${dash}
`;

    // CRUD 비교 테이블
    const phases = [
        ['Baseline (SSE 0)',    'crud_baseline_duration',   'crud_baseline_success'],
        ['SSE 50 + CRUD',      'crud_sse50_duration',      'crud_sse50_success'],
        ['SSE 200 + CRUD',     'crud_sse200_duration',     'crud_sse200_success'],
        ['SSE 300 + CRUD',     'crud_sse300_duration',     'crud_sse300_success'],
        ['Recovery (SSE 해제)', 'crud_recovery_duration',   'crud_recovery_success'],
    ];

    s += `
[CRUD 응답 시간 비교 — SSE 동시 연결 수별]
${'Phase'.padEnd(22)} ${'p50'.padStart(8)} ${'p95'.padStart(8)} ${'p99'.padStart(8)} ${'max'.padStart(8)}  ${'성공률'.padStart(7)}
${dash}
`;

    for (const [label, durKey, okKey] of phases) {
        const m = data.metrics[durKey];
        const r = data.metrics[okKey];
        if (m && m.values) {
            const v = m.values;
            const rate = r ? (r.values.rate * 100).toFixed(1) + '%' : 'N/A';
            s += `${label.padEnd(22)} ${fmt(v['p(50)'])} ${fmt(v['p(95)'])} ${fmt(v['p(99)'])} ${fmt(v['max'])}  ${rate.padStart(7)}\n`;
        } else {
            s += `${label.padEnd(22)} ${'N/A'.padStart(8)} ${'N/A'.padStart(8)} ${'N/A'.padStart(8)} ${'N/A'.padStart(8)}  ${'N/A'.padStart(7)}\n`;
        }
    }
    s += `${dash}\n`;

    // 개별 엔드포인트 메트릭
    const endpoints = [
        ['알림 목록',       'noti_list_duration'],
        ['안읽은 수',       'noti_unread_duration'],
        ['읽음 처리',       'noti_mark_duration'],
        ['삭제',            'noti_delete_duration'],
        ['전체 읽음',       'noti_markall_duration'],
    ];

    s += `\n[개별 엔드포인트 응답 시간]\n`;
    s += `${'API'.padEnd(16)} ${'p50'.padStart(8)} ${'p95'.padStart(8)} ${'max'.padStart(8)}\n`;
    s += `${dash}\n`;

    for (const [label, key] of endpoints) {
        const m = data.metrics[key];
        if (m && m.values) {
            const v = m.values;
            s += `${label.padEnd(16)} ${fmt(v['p(50)'])} ${fmt(v['p(95)'])} ${fmt(v['max'])}\n`;
        }
    }

    // Thresholds
    let pass = 0, fail = 0;
    if (data.metrics) {
        for (const [, val] of Object.entries(data.metrics)) {
            if (val.thresholds) {
                for (const [, th] of Object.entries(val.thresholds)) {
                    if (th.ok) pass++; else fail++;
                }
            }
        }
    }

    const errCount = data.metrics['total_5xx_errors'];
    s += `\n${dash}\n`;
    s += `모드: ${mode}  |  5xx 에러: ${errCount ? errCount.values.count : 0}  |  Thresholds: ${pass} PASS / ${fail} FAIL\n`;
    s += `${line}\n`;

    console.log(s);

    return {
        'stdout': s,
        [`webflux-comparison-${mode}-${Date.now()}.json`]: JSON.stringify(data, null, 2),
    };
}

function fmt(ms) {
    if (ms === undefined || ms === null) return 'N/A'.padStart(8);
    if (ms < 1000) return (ms.toFixed(0) + 'ms').padStart(8);
    return ((ms / 1000).toFixed(2) + 's').padStart(8);
}
