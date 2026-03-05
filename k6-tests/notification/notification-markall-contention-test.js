// =============================================================
// 알림 mark-all(전체 읽음) Row Lock 경합 전용 테스트
// =============================================================
// 평가 항목: 동시 mark-all UPDATE 시 InnoDB row lock 경합, 데드락, HikariCP 풀 고갈
//
// 실행: ./k6-tests/run-loadtest.sh notif-markall
//
// 사전 준비:
//   1. seed-all-domains.sql 실행 (user 100,000명, notification 10,000,000건)
//   2. 앱 실행 (local 프로필)
//
// 배경:
//   mark-all 쿼리: UPDATE notification SET is_read=true
//                  WHERE user_id=:userId AND is_read=false LIMIT 1000
//   - LIMIT 1000 배치로 row lock 범위 제한
//   - 하지만 동시 다수 유저가 mark-all 호출 시 InnoDB 버퍼 풀 경합 발생 가능
//   - 같은 유저가 동시 mark-all 호출 시 row lock 대기 발생
//
// 측정 포인트:
//   ① 서로 다른 유저 동시 mark-all → row lock 간섭 없어야 함
//   ② 같은 유저 동시 mark-all → row lock 대기 시간 측정
//   ③ mark-all + 단건 read 혼합 → 교차 잠금 데드락 여부
//   ④ 고부하 mark-all → HikariCP 풀 고갈 및 타임아웃
//
// Phase 구성 (~8분):
// ┌────────┬──────────────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                          │ VU   │ 시간  │
// ├────────┼──────────────────────────────────┼──────┼───────┤
// │ 1      │ Warmup + 데이터 준비              │ 10   │ 15s   │
// │ 2      │ 서로 다른 유저 동시 mark-all       │ 100  │ 90s   │
// │ 3      │ 같은 유저 동시 mark-all (경합)     │ 50   │ 90s   │
// │ 4      │ mark-all + 단건 read 혼합          │ 100  │ 90s   │
// │ 5      │ 극한 mark-all (풀 고갈 탐지)       │ 300  │ 60s   │
// │ 6      │ Cooldown + 검증                   │ 5    │ 15s   │
// └────────┴──────────────────────────────────┴──────┴───────┘
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, fetchNotificationIds, BASE_URL, vu, dur, startAfter } from '../lib/common.js';
import { randomUser, hotUser, createNotification, pad, num, pct } from './helpers.js';

// ── 커스텀 메트릭 ──
const diffUserMarkAllDur  = new Trend('diff_user_markall_duration', true);
const diffUserMarkAllOk   = new Rate('diff_user_markall_success');
const sameUserMarkAllDur  = new Trend('same_user_markall_duration', true);
const sameUserMarkAllOk   = new Rate('same_user_markall_success');
const sameUserLockWait    = new Trend('same_user_lock_wait', true);
const mixMarkAllDur       = new Trend('mix_markall_duration', true);
const mixMarkAllOk        = new Rate('mix_markall_success');
const mixMarkSingleDur    = new Trend('mix_mark_single_duration', true);
const mixMarkSingleOk     = new Rate('mix_mark_single_success');
const extremeMarkAllDur   = new Trend('extreme_markall_duration', true);
const extremeMarkAllOk    = new Rate('extreme_markall_success');
const totalErrors         = new Counter('markall_total_errors');
const deadlockErrors      = new Counter('markall_deadlock_errors');
const timeoutErrors       = new Counter('markall_timeout_errors');
const markAllCount        = new Counter('markall_total_calls');
const createDur           = new Trend('markall_create_duration', true);

// ── 설정 (환경변수 오버라이드 가능) ──
const EXTREME_VU = parseInt(__ENV.EXTREME_VU || '300');

// Phase 시간 (초)
const P1 = 15, P2 = 90, P3 = 90, P4 = 90, P5 = 60, P6 = 15;

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus', vus: vu(10), duration: dur(P1),
            exec: 'warmup', tags: { phase: '1_warmup' },
        },
        diff_user_markall: {
            executor: 'constant-vus', vus: vu(100), duration: dur(P2),
            startTime: startAfter([P1]), exec: 'diffUserMarkAll', tags: { phase: '2_diff_user' },
        },
        same_user_markall: {
            executor: 'constant-vus', vus: vu(50), duration: dur(P3),
            startTime: startAfter([P1, P2]), exec: 'sameUserMarkAll', tags: { phase: '3_same_user' },
        },
        mixed_markall: {
            executor: 'constant-vus', vus: vu(50), duration: dur(P4),
            startTime: startAfter([P1, P2, P3]), exec: 'mixedMarkAll', tags: { phase: '4_mixed' },
        },
        mixed_single_read: {
            executor: 'constant-vus', vus: vu(50), duration: dur(P4),
            startTime: startAfter([P1, P2, P3]), exec: 'mixedSingleRead', tags: { phase: '4_mixed' },
        },
        extreme_markall: {
            executor: 'ramping-vus', startVUs: vu(10),
            stages: [
                { duration: dur(10), target: vu(EXTREME_VU) },
                { duration: dur(35), target: vu(EXTREME_VU) },
                { duration: dur(15), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4]), exec: 'extremeMarkAll', tags: { phase: '5_extreme' },
        },
        cooldown: {
            executor: 'constant-vus', vus: vu(5), duration: dur(P6),
            startTime: startAfter([P1, P2, P3, P4, P5]), exec: 'warmup', tags: { phase: '6_cooldown' },
        },
    },

    thresholds: {
        // Phase 2: 서로 다른 유저 — lock 간섭 없음
        'diff_user_markall_duration': ['p(95)<500'],
        'diff_user_markall_success':  ['rate>0.98'],

        // Phase 3: 같은 유저 — lock 대기 발생 허용
        'same_user_markall_duration': ['p(95)<3000'],
        'same_user_markall_success':  ['rate>0.90'],

        // Phase 4: 혼합 — 교차 잠금 없어야 함
        'mix_markall_success':        ['rate>0.95'],
        'mix_mark_single_success':    ['rate>0.95'],

        // Phase 5: 극한 — 기본 동작 유지
        'extreme_markall_success':    ['rate>0.85'],

        // 데드락 0건
        'markall_deadlock_errors':    ['count<5'],
    },
};

// ============================================
// 유틸리티
// ============================================
function doMarkAll(user, durMetric, okMetric) {
    const token = generateJWT(user);
    const hdrs = headers(token);

    const res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
        headers: hdrs,
        tags: { name: 'markall' },
        timeout: '10s',
    });

    durMetric.add(res.timings.duration);
    markAllCount.add(1);

    const ok = res.status === 200;
    okMetric.add(ok);

    if (!ok) {
        totalErrors.add(1);
        if (res.status >= 500) deadlockErrors.add(1);  // 5xx → 데드락 또는 내부 오류
        if (res.status === 0) timeoutErrors.add(1);     // 타임아웃
    }

    return { ok, duration: res.timings.duration };
}

function replenishNotifications(userId) {
    // 읽음 처리 후 다음 iteration을 위해 알림 재생성
    const types = ['LIKE', 'CHAT', 'REFEED'];
    for (let i = 0; i < 3; i++) {
        const result = createNotification(userId, types[i]);
        createDur.add(result.duration);
    }
}

// ============================================
// Phase 1: Warmup
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    http.get(`${BASE_URL}/api/v1/notifications?size=5`, {
        headers: headers(token), tags: { name: 'warmup' },
    });
    sleep(0.5);
}

// ============================================
// Phase 2: 서로 다른 유저 동시 mark-all
// ============================================
export function diffUserMarkAll() {
    const user = randomUser();  // 100,000명 중 랜덤 → 충돌 확률 극히 낮음
    doMarkAll(user, diffUserMarkAllDur, diffUserMarkAllOk);

    // 데이터 보충
    replenishNotifications(user.userId);
    sleep(0.3 + Math.random() * 0.2);
}

// ============================================
// Phase 3: 같은 유저 동시 mark-all (경합)
// ============================================
export function sameUserMarkAll() {
    const user = hotUser();  // 10명 중 선택 → 50 VU가 10명에 집중

    const startTime = Date.now();
    const result = doMarkAll(user, sameUserMarkAllDur, sameUserMarkAllOk);
    const elapsed = Date.now() - startTime;

    // lock 대기 시간 추정: 실제 응답시간 - 평균 mark-all 시간(~50ms)
    // 50ms 이상이면 lock 대기 발생으로 추정
    if (elapsed > 50) {
        sameUserLockWait.add(elapsed - 50);
    }

    // 다음 iteration을 위해 알림 재생성
    replenishNotifications(user.userId);
    sleep(0.2 + Math.random() * 0.3);
}

// ============================================
// Phase 4: mark-all + 단건 read 혼합
// ============================================
export function mixedMarkAll() {
    const user = randomUser();
    doMarkAll(user, mixMarkAllDur, mixMarkAllOk);
    replenishNotifications(user.userId);
    sleep(0.2 + Math.random() * 0.2);
}

export function mixedSingleRead() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    const ids = fetchNotificationIds(token, 10);
    if (ids.length === 0) { sleep(0.5); return; }

    // 연속 3건 단건 읽음
    for (let i = 0; i < Math.min(3, ids.length); i++) {
        const res = http.put(`${BASE_URL}/api/v1/notifications/${ids[i]}/read`, null, {
            headers: hdrs, tags: { name: 'mark_single' },
        });
        mixMarkSingleDur.add(res.timings.duration);
        mixMarkSingleOk.add(res.status === 200);
        if (res.status >= 500) deadlockErrors.add(1);
        sleep(0.05);
    }

    sleep(0.2);
}

// ============================================
// Phase 5: 극한 mark-all — 300 VU
// ============================================
export function extremeMarkAll() {
    const user = randomUser();
    doMarkAll(user, extremeMarkAllDur, extremeMarkAllOk);
    sleep(0.1 + Math.random() * 0.1);
}

// ============================================
// handleSummary
// ============================================
export function handleSummary(data) {
    const m = data.metrics;

    const lines = [
        '',
        '╔══════════════════════════════════════════════════════════════╗',
        '║        알림 Mark-All Row Lock 경합 테스트 리포트              ║',
        '╚══════════════════════════════════════════════════════════════╝',
        '',
        '┌─────────────────────────────────────────────────────────────┐',
        '│ 1. 서로 다른 유저 동시 mark-all (100 VU)                    │',
        '│    기대: lock 간섭 없음, p95 < 500ms                        │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ 응답시간:  p50=${pad(num(m.diff_user_markall_duration?.values?.med), 8)} p95=${pad(num(m.diff_user_markall_duration?.values?.['p(95)']), 8)} ms │`,
        `│ 성공률:    ${pad(pct(m.diff_user_markall_success?.values?.rate), 10)}                           │`,
        '├─────────────────────────────────────────────────────────────┤',
        '│ 2. 같은 유저 동시 mark-all (50 VU → 10명 집중)              │',
        '│    기대: row lock 대기 발생, p95 < 3000ms                   │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ 응답시간:  p50=${pad(num(m.same_user_markall_duration?.values?.med), 8)} p95=${pad(num(m.same_user_markall_duration?.values?.['p(95)']), 8)} ms │`,
        `│ 성공률:    ${pad(pct(m.same_user_markall_success?.values?.rate), 10)}                           │`,
        `│ Lock대기:  p50=${pad(num(m.same_user_lock_wait?.values?.med), 8)} p95=${pad(num(m.same_user_lock_wait?.values?.['p(95)']), 8)} ms │`,
        '├─────────────────────────────────────────────────────────────┤',
        '│ 3. mark-all + 단건 read 혼합 (50+50 VU)                    │',
        '│    기대: 교차 잠금 없음 (데드락 0건)                         │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ mark-all:  p50=${pad(num(m.mix_markall_duration?.values?.med), 8)} p95=${pad(num(m.mix_markall_duration?.values?.['p(95)']), 8)} ms │`,
        `│ mark-all:  성공률 ${pad(pct(m.mix_markall_success?.values?.rate), 10)}                         │`,
        `│ 단건read:  p50=${pad(num(m.mix_mark_single_duration?.values?.med), 8)} p95=${pad(num(m.mix_mark_single_duration?.values?.['p(95)']), 8)} ms │`,
        `│ 단건read:  성공률 ${pad(pct(m.mix_mark_single_success?.values?.rate), 10)}                         │`,
        '├─────────────────────────────────────────────────────────────┤',
        '│ 4. 극한 mark-all (300 VU)                                   │',
        '│    기대: 기본 동작 유지, 성공률 > 85%                        │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ 응답시간:  p50=${pad(num(m.extreme_markall_duration?.values?.med), 8)} p95=${pad(num(m.extreme_markall_duration?.values?.['p(95)']), 8)} ms │`,
        `│ 성공률:    ${pad(pct(m.extreme_markall_success?.values?.rate), 10)}                           │`,
        '├─────────────────────────────────────────────────────────────┤',
        '│ 5. 에러 분석                                                │',
        '├─────────────────────────────────────────────────────────────┤',
        `│ 총 호출 수:     ${pad(num(m.markall_total_calls?.values?.count, 0), 10)} 회                     │`,
        `│ 총 에러:        ${pad(num(m.markall_total_errors?.values?.count, 0), 10)} 건                     │`,
        `│ 데드락 의심:    ${pad(num(m.markall_deadlock_errors?.values?.count, 0), 10)} 건 (5xx 응답)          │`,
        `│ 타임아웃:       ${pad(num(m.markall_timeout_errors?.values?.count, 0), 10)} 건                     │`,
        '└─────────────────────────────────────────────────────────────┘',
        '',
        '판정 기준:',
        '  다른 유저 mark-all p95 < 500ms   → user_id별 row 격리 정상',
        '  같은 유저 mark-all p95 < 3s      → LIMIT 배치가 lock 범위 제한 중',
        '  데드락 건수 < 5                  → 교차 잠금 방지 설계 유효',
        '  극한 성공률 > 85%                → HikariCP 풀 적절',
        '',
        '  데드락 > 5건  → UPDATE 순서 검토 필요 (PK 기준 정렬 권장)',
        '  타임아웃 > 0  → HikariCP connection-timeout 또는 innodb_lock_wait_timeout 조정',
        '',
    ];

    console.log(lines.join('\n'));
    return {};
}
