// =============================================================
// Finance 모듈 고부하 테스트 — 결제·정산·지갑 트랜잭션 정합성
// =============================================================
//
// Phase 1  Warmup           (50 VUs, 30s)       — 커넥션풀 예열
// Phase 2  결제 폭풍         (600 VUs peak, 2m)  — save→verify→confirm 대량 발사
// Phase 3  멱등성 폭풍       (750 VUs, 30s)      — 동일 orderId 750명 동시 confirm
// Phase 4  정산 대량 요청    (750 VUs, 2m)       — settlement 동시 Outbox→Kafka E2E
// Phase 5  정산 조회 폭풍    (450 VUs peak, 1.5m) — 정산 상태/참여자 리스트 집중 조회
// Phase 6  지갑 조회 집중    (450 VUs peak, 1.5m) — 거래내역 페이징 집중
// Phase 7  복합 고부하       (900 VUs peak, 3m)  — 결제40%+정산조회20%+지갑조회20%+실패기록10%+정산요청10%
// Phase 8  스파이크          (1500 VUs peak, 1.5m) — 순간 폭증 내구성
// Phase 9  이중 스파이크     (1200 VUs peak, 2m) — 회복 후 재폭증
// Phase 10 지속 내구         (600 VUs peak, 3m)  — Soak: 누수/GC/커넥션풀 고갈 탐지
// Phase 11 최종 검증         (1 VU, 30s)         — 전 API 정상 확인
//
// 전제 조건:
//   - 서버: SPRING_PROFILES_ACTIVE=local (loadtest 필수)
//   - MySQL: userId 1~100000 지갑 존재
//   - schedule 5000000~5024999 (club_id=1, ENDED)
//   - settlement 25,000건 (HOLDING, receiver=userId 1)
//   - user_settlement 250,000건 (각 10명, HOLD_ACTIVE)
//
// 실행:
//   MSYS_NO_PATHCONV=1 docker run --rm -i --network host \
//     -v "$(pwd)/k6-tests:/scripts" grafana/k6:latest run /scripts/finance-loadtest.js
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, makeUser, BASE_URL, MIN_CLUB, vu, dur, startAfter, TOTAL_USERS } from '../lib/common.js';

// ── 상수 ──
// 기본: 10x 로컬. AWS(100x): USER_COUNT=100000 SETTLEMENT_COUNT=100000
const VALID_USER_COUNT = parseInt(__ENV.USER_COUNT || '') || TOTAL_USERS;
const SETTLEMENT_COUNT = parseInt(__ENV.SETTLEMENT_COUNT || '2500');
const SCHEDULE_ID_BASE = parseInt(__ENV.SCHEDULE_ID_BASE || '5000000');

// ── 커스텀 메트릭 ──
// Phase 2: 결제 폭풍
const payFlowDur    = new Trend('pay_flow_duration', true);
const payFlowOk     = new Rate('pay_flow_success');
const payConfirmDur = new Trend('pay_confirm_duration', true);
const payConfirmOk  = new Rate('pay_confirm_success');
// Phase 3: 멱등성
const idempOk       = new Rate('idemp_correct_response');
// Phase 4: 정산 요청
const settleDur     = new Trend('settle_request_duration', true);
const settleOk      = new Rate('settle_request_success');
// Phase 5: 정산 조회
const settleQueryDur = new Trend('settle_query_duration', true);
const settleQueryOk = new Rate('settle_query_success');
// Phase 6: 지갑 조회
const walletQueryDur = new Trend('wallet_query_duration', true);
const walletQueryOk = new Rate('wallet_query_success');
// Phase 7~9: 스트레스
const stressOk      = new Rate('stress_success');
const stress5xx     = new Rate('stress_5xx_rate');
const stressDur     = new Trend('stress_duration', true);
// Phase 8: 스파이크
const spikeOk       = new Rate('spike_success');
const spike5xx      = new Rate('spike_5xx_rate');
const spikeDur      = new Trend('spike_duration', true);
// Phase 9: 이중 스파이크
const dblSpikeOk    = new Rate('dbl_spike_success');
const dblSpike5xx   = new Rate('dbl_spike_5xx_rate');
// Phase 10: 내구
const soakOk        = new Rate('soak_success');
const soak5xx       = new Rate('soak_5xx_rate');
const soakDur       = new Trend('soak_duration', true);
// Phase 11: 검증
const verifyOk      = new Rate('verify_pass');

// ── Phase 타이밍 (초 단위, dur()/startAfter()로 스케일링) ──
// Phase 1:  Warmup     (30s)
// Phase 2:  결제 폭풍   (2m)     — Phase 3 겹침
// Phase 3:  멱등성      (30s)    — Phase 2 시작과 동시
// Phase 4:  정산 요청   (2m)
// Phase 5:  정산 조회   (1.5m)   — Phase 6 겹침
// Phase 6:  지갑 조회   (1.5m)   — Phase 5 시작과 동시
// Phase 7:  복합 고부하  (3m)
// Phase 8:  스파이크     (1.5m)
// Phase 9:  이중 스파이크 (2m)   — 30s 쿨다운 후 시작
// Phase 10: 내구        (3m)
// Phase 11: 최종 검증   (30s)
const FP1 = 30, FP2 = 120, FP3 = 30, FP4 = 120, FP5 = 90, FP6 = 90;
const FP7 = 180, FP8 = 90, FP9 = 120, FP10 = 180, FP11 = 30;

export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: vu(50),
            duration: dur(FP1),
            exec: 'warmup',
            startTime: '0s',
        },
        // Phase 2: 결제 폭풍 (600 VUs peak)
        payment_storm: {
            executor: 'ramping-vus',
            stages: [
                { duration: dur(20), target: vu(600) },
                { duration: dur(80), target: vu(600) },
                { duration: dur(20), target: 0 },
            ],
            exec: 'paymentStorm',
            startTime: startAfter([FP1]),
        },
        // Phase 3: 멱등성 폭풍 (750 VUs 동시 confirm) — Phase 2와 동시 시작
        payment_idempotency: {
            executor: 'per-vu-iterations',
            vus: vu(750),
            iterations: 1,
            exec: 'paymentIdempotency',
            startTime: startAfter([FP1]),
            maxDuration: dur(FP3),
        },
        // Phase 4: 정산 대량 요청 (750 VUs, 각 1회)
        settlement_mass: {
            executor: 'per-vu-iterations',
            vus: vu(750),
            iterations: 1,
            exec: 'settlementMass',
            startTime: startAfter([FP1, FP2]),
            maxDuration: dur(FP4),
        },
        // Phase 5: 정산 조회 폭풍 (450 VUs peak)
        settlement_query_storm: {
            executor: 'ramping-vus',
            stages: [
                { duration: dur(15), target: vu(450) },
                { duration: dur(60), target: vu(450) },
                { duration: dur(15), target: 0 },
            ],
            exec: 'settlementQueryStorm',
            startTime: startAfter([FP1, FP2, FP4], 10),
        },
        // Phase 6: 지갑 조회 집중 (450 VUs peak) — Phase 5와 동시 시작
        wallet_query_storm: {
            executor: 'ramping-vus',
            stages: [
                { duration: dur(15), target: vu(450) },
                { duration: dur(60), target: vu(450) },
                { duration: dur(15), target: 0 },
            ],
            exec: 'walletQueryStorm',
            startTime: startAfter([FP1, FP2, FP4], 10),
        },
        // Phase 7: 복합 고부하 (900 VUs peak, 3m)
        mixed_highload: {
            executor: 'ramping-vus',
            stages: [
                { duration: dur(30), target: vu(900) },
                { duration: dur(120), target: vu(900) },
                { duration: dur(30), target: 0 },
            ],
            exec: 'mixedHighload',
            startTime: startAfter([FP1, FP2, FP4, FP5], 10),
        },
        // Phase 8: 스파이크 1500 VUs
        spike_1000: {
            executor: 'ramping-vus',
            stages: [
                { duration: dur(10), target: vu(1500) },
                { duration: dur(50), target: vu(1500) },
                { duration: dur(10), target: vu(10) },
                { duration: dur(20), target: vu(10) },
            ],
            exec: 'spike1000',
            startTime: startAfter([FP1, FP2, FP4, FP5, FP7], 10),
        },
        // Phase 9: 이중 스파이크 (1200 VUs peak)
        double_spike: {
            executor: 'ramping-vus',
            stages: [
                { duration: dur(10), target: vu(1200) },
                { duration: dur(20), target: vu(1200) },
                { duration: dur(10), target: vu(10) },
                { duration: dur(15), target: vu(10) },
                { duration: dur(10), target: vu(1200) },
                { duration: dur(20), target: vu(1200) },
                { duration: dur(10), target: vu(10) },
                { duration: dur(15), target: 0 },
            ],
            exec: 'doubleSpike',
            startTime: startAfter([FP1, FP2, FP4, FP5, FP7, FP8], 30),
        },
        // Phase 10: 지속 내구 Soak (600 VUs, 3m)
        soak: {
            executor: 'ramping-vus',
            stages: [
                { duration: dur(20), target: vu(600) },
                { duration: dur(140), target: vu(600) },
                { duration: dur(20), target: 0 },
            ],
            exec: 'soakTest',
            startTime: startAfter([FP1, FP2, FP4, FP5, FP7, FP8, FP9], 20),
        },
        // Phase 11: 최종 검증
        final_verify: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: 1,
            exec: 'finalVerify',
            startTime: startAfter([FP1, FP2, FP4, FP5, FP7, FP8, FP9, FP10], 10),
            maxDuration: dur(FP11),
        },
    },
    thresholds: {
        // ────── Bottleneck 기준 임계값 (저부하 기대치) ──────
        // wallet_balance: FAST(200ms), wallet_transactions: NORMAL(500ms)
        // payment_create: NORMAL(500ms), payment_confirm: SLOW(1000ms)
        // settlement_request: VERY_SLOW(3000ms), settlement_status: FAST(200ms)
        // settlement_my_list: NORMAL(500ms), settlement_batch: VERY_SLOW(3000ms)

        // Phase 2: 결제 폭풍
        'pay_flow_success':         ['rate>0.90'],
        'pay_flow_duration':        ['p(95)<5000'],
        'pay_confirm_success':      ['rate>0.90'],
        'pay_confirm_duration':     ['p(95)<3000', 'p(50)<1000'],
        // Phase 3: 멱등성
        'idemp_correct_response':   ['rate>0.95'],
        // Phase 4: 정산 요청
        'settle_request_success':   ['rate>0.80'],
        'settle_request_duration':  ['p(95)<5000', 'p(50)<3000'],
        // Phase 5: 정산 조회
        'settle_query_success':     ['rate>0.90'],
        'settle_query_duration':    ['p(95)<3000', 'p(50)<500'],
        // Phase 6: 지갑 조회
        'wallet_query_success':     ['rate>0.90'],
        'wallet_query_duration':    ['p(95)<3000', 'p(50)<500'],
        // Phase 7: 복합 고부하
        'stress_success':           ['rate>0.85'],
        'stress_5xx_rate':          ['rate<0.10'],
        'stress_duration':          ['p(95)<5000'],
        // Phase 8: 스파이크
        'spike_success':            ['rate>0.80'],
        'spike_5xx_rate':           ['rate<0.15'],
        'spike_duration':           ['p(95)<8000'],
        // Phase 9: 이중 스파이크
        'dbl_spike_success':        ['rate>0.80'],
        'dbl_spike_5xx_rate':       ['rate<0.15'],
        // Phase 10: 내구
        'soak_success':             ['rate>0.90'],
        'soak_5xx_rate':            ['rate<0.05'],
        'soak_duration':            ['p(95)<3000', 'p(50)<1000'],
        // Phase 11: 검증
        'verify_pass':              ['rate>0.80'],
    },
};

// ── 유틸 ──
function uniqueOrderId(prefix) {
    return `loadtest-${prefix}-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
}

function randomUserId() {
    return Math.floor(Math.random() * VALID_USER_COUNT) + 1;
}

function randomScheduleId() {
    return SCHEDULE_ID_BASE + Math.floor(Math.random() * SETTLEMENT_COUNT);
}

function clubForSchedule(scheduleId) {
    return MIN_CLUB + ((scheduleId - SCHEDULE_ID_BASE) % parseInt(__ENV.TOTAL_CLUBS || '5000'));
}

// ── Setup: 멱등성 테스트용 사전 save+verify ──
export function setup() {
    const idempOrderId = 'loadtest-idemp-shared-001';
    const idempAmount = 5000;
    const user = makeUser(1);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const saveRes = http.post(`${BASE_URL}/api/v1/payments/save`,
        JSON.stringify({ orderId: idempOrderId, amount: idempAmount }),
        { headers: hdrs }
    );
    console.log(`[SETUP] idemp save: status=${saveRes.status}`);

    const verifyRes = http.post(`${BASE_URL}/api/v1/payments/success`,
        JSON.stringify({ orderId: idempOrderId, amount: idempAmount }),
        { headers: hdrs }
    );
    console.log(`[SETUP] idemp verify: status=${verifyRes.status}`);

    return { idempOrderId, idempAmount };
}

// ── 결제 전체 플로우: save → verify → confirm ──
function doPaymentFlow(userId, orderId, amount) {
    const user = makeUser(userId);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const saveRes = http.post(`${BASE_URL}/api/v1/payments/save`,
        JSON.stringify({ orderId, amount }),
        { headers: hdrs, tags: { name: 'pay_save' } }
    );
    if (saveRes.status !== 200) return { success: false, step: 'save', status: saveRes.status };

    const verifyRes = http.post(`${BASE_URL}/api/v1/payments/success`,
        JSON.stringify({ orderId, amount }),
        { headers: hdrs, tags: { name: 'pay_verify' } }
    );
    if (verifyRes.status !== 200) return { success: false, step: 'verify', status: verifyRes.status };

    const paymentKey = `pk_${orderId}`;
    const start = Date.now();
    const confirmRes = http.post(`${BASE_URL}/api/v1/payments/confirm`,
        JSON.stringify({ paymentKey, orderId, amount }),
        { headers: hdrs, tags: { name: 'pay_confirm' } }
    );
    const dur = Date.now() - start;
    payConfirmDur.add(dur);
    payConfirmOk.add(confirmRes.status === 200);

    return { success: confirmRes.status === 200, step: 'confirm', status: confirmRes.status, duration: dur };
}

// ── 복합 작업 (스트레스/스파이크/내구 공용) ──
function doMixedOp(userId, ratios) {
    const user = makeUser(userId);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const ops = Math.random();
    let res;
    const start = Date.now();

    const payThresh = ratios.pay || 0;
    const sqThresh = payThresh + (ratios.settleQuery || 0);
    const wqThresh = sqThresh + (ratios.walletQuery || 0);
    const failThresh = wqThresh + (ratios.fail || 0);

    if (ops < payThresh) {
        const orderId = uniqueOrderId(`mx-${__VU}-${__ITER}`);
        const amount = 500 + Math.floor(Math.random() * 4500);
        const result = doPaymentFlow(userId, orderId, amount);
        return { dur: Date.now() - start, ok: result.success, is5xx: !result.success && result.status >= 500 };
    } else if (ops < sqThresh) {
        const scheduleId = randomScheduleId();
        res = http.get(
            `${BASE_URL}/api/v1/clubs/${clubForSchedule(scheduleId)}/schedules/${scheduleId}/settlements?page=0&size=20`,
            { headers: hdrs, tags: { name: 'mx_settle_query' } }
        );
    } else if (ops < wqThresh) {
        res = http.get(`${BASE_URL}/api/v1/users/wallet?page=0&size=20`, {
            headers: hdrs, tags: { name: 'mx_wallet' },
        });
    } else if (ops < failThresh) {
        const orderId = uniqueOrderId(`fail-${__VU}-${__ITER}`);
        res = http.post(`${BASE_URL}/api/v1/payments/fail`,
            JSON.stringify({ paymentKey: `pk_fail_${orderId}`, orderId, amount: 1000 }),
            { headers: hdrs, tags: { name: 'mx_fail' } }
        );
    } else {
        // 정산 요청 — receiver = schedule별 user_id
        const scheduleId = randomScheduleId();
        const receiverId = scheduleId - SCHEDULE_ID_BASE + 1;
        const leaderToken = generateJWT(makeUser(receiverId));
        const leaderHdrs = headers(leaderToken);
        res = http.post(
            `${BASE_URL}/api/v1/clubs/${clubForSchedule(scheduleId)}/schedules/${scheduleId}/settlements?costPerUser=10`,
            null,
            { headers: leaderHdrs, tags: { name: 'mx_settle_req' } }
        );
        // 409(CAS 경합) / 400(이미 완료) → 정상 응답으로 처리
        const dur = Date.now() - start;
        const ok = res.status >= 200 && res.status < 500;
        const is5xx = res.status >= 500;
        return { dur, ok, is5xx };
    }

    const dur = Date.now() - start;
    const ok = res && res.status >= 200 && res.status < 500;
    const is5xx = res && res.status >= 500;
    return { dur, ok, is5xx };
}

// ============================================================
// Phase 1: Warmup — 커넥션풀·JIT 예열
// ============================================================
export function warmup() {
    const userId = randomUserId();
    const orderId = uniqueOrderId(`warm-${__VU}-${__ITER}`);
    doPaymentFlow(userId, orderId, 1000);
    sleep(0.5);
}

// ============================================================
// Phase 2: 결제 폭풍 (600 VUs peak, 2분)
// save→verify→confirm 대량 발사, Redis gate + CAS + wallet credit
// ============================================================
export function paymentStorm() {
    const userId = randomUserId();
    const orderId = uniqueOrderId(`storm-${__VU}-${__ITER}`);
    const amount = 1000 + Math.floor(Math.random() * 9000);

    const start = Date.now();
    const result = doPaymentFlow(userId, orderId, amount);
    payFlowDur.add(Date.now() - start);
    payFlowOk.add(result.success);

    sleep(0.05);
}

// ============================================================
// Phase 3: 멱등성 폭풍 (750 VUs 동시 confirm)
// Redis gate(SET NX) + INSERT IGNORE + CAS 이중 차단 검증
// ============================================================
export function paymentIdempotency(data) {
    const orderId = data.idempOrderId;
    const amount = data.idempAmount;
    const paymentKey = `pk_${orderId}`;

    const user = makeUser(1);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const res = http.post(`${BASE_URL}/api/v1/payments/confirm`,
        JSON.stringify({ paymentKey, orderId, amount }),
        { headers: hdrs, tags: { name: 'idemp_confirm' } }
    );

    // 200(성공) 또는 202(PAYMENT_IN_PROGRESS) 또는 4xx(정상 거절) → OK
    const correct = res.status === 200 || res.status === 202
        || (res.status >= 400 && res.status < 500);
    idempOk.add(correct);

    if (res.status >= 500) {
        console.log(`[IDEMP 5xx] VU=${__VU} status=${res.status} body=${(res.body || '').substring(0, 200)}`);
    }
}

// ============================================================
// Phase 4: 정산 대량 요청 (750 VUs, 각 1회 → 750건 동시 정산)
// Outbox → Kafka → StructuredTaskScope(captureHold) → LedgerWriter
// ============================================================
export function settlementMass() {
    // VU 번호를 settlement 1~500에 매핑
    const settlementIdx = (__VU % SETTLEMENT_COUNT) + 1;
    const scheduleId = SCHEDULE_ID_BASE + settlementIdx;
    const costPerUser = 100;

    // receiver = seed에서 settlement.user_id = scheduleId - SCHEDULE_ID_BASE + 1
    const receiverId = scheduleId - SCHEDULE_ID_BASE + 1;
    const user = makeUser(receiverId);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const start = Date.now();
    const res = http.post(
        `${BASE_URL}/api/v1/clubs/${clubForSchedule(scheduleId)}/schedules/${scheduleId}/settlements?costPerUser=${costPerUser}`,
        null,
        { headers: hdrs, tags: { name: 'settle_request' } }
    );
    const dur = Date.now() - start;
    settleDur.add(dur);

    // 201(성공), 200, 409(CAS 경합 거절) → 정상
    const ok = res.status === 201 || res.status === 200 || res.status === 409;
    settleOk.add(ok);

    if (!ok) {
        console.log(`[SETTLE FAIL] VU=${__VU} idx=${settlementIdx} scheduleId=${scheduleId} status=${res.status} body=${(res.body || '').substring(0, 200)}`);
    }

    // 정산 완료 대기 (Kafka 비동기) — 폴링 최대 30초
    if (ok && res.status !== 409) {
        for (let i = 0; i < 15; i++) {
            sleep(2);
            const queryRes = http.get(
                `${BASE_URL}/api/v1/clubs/${clubForSchedule(scheduleId)}/schedules/${scheduleId}/settlements?page=0&size=20`,
                { headers: hdrs, tags: { name: 'settle_poll' } }
            );
            if (queryRes.status === 200) {
                try {
                    const data = JSON.parse(queryRes.body);
                    const userSettlements = data.data?.userSettlementList || [];
                    const allDone = userSettlements.length > 0 && userSettlements.every(
                        us => us.status === 'COMPLETED' || us.status === 'FAILED'
                    );
                    if (allDone) break;
                } catch (e) { /* ignore */ }
            }
        }
    }
}

// ============================================================
// Phase 5: 정산 조회 폭풍 (450 VUs peak)
// 정산 목록 + 참여자 상태 조회, user_settlement JOIN 부하
// ============================================================
export function settlementQueryStorm() {
    const userId = randomUserId();
    const user = makeUser(userId);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const scheduleId = randomScheduleId();
    const page = Math.floor(Math.random() * 3); // 0~2 페이지

    const start = Date.now();
    const res = http.get(
        `${BASE_URL}/api/v1/clubs/${clubForSchedule(scheduleId)}/schedules/${scheduleId}/settlements?page=${page}&size=20`,
        { headers: hdrs, tags: { name: 'settle_query_storm' } }
    );
    settleQueryDur.add(Date.now() - start);
    settleQueryOk.add(res.status === 200);

    sleep(0.05);
}

// ============================================================
// Phase 6: 지갑 조회 집중 (450 VUs peak)
// 거래 내역 페이징, WalletTransaction JOIN 부하
// ============================================================
export function walletQueryStorm() {
    const userId = randomUserId();
    const user = makeUser(userId);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const page = Math.floor(Math.random() * 5); // 0~4 페이지

    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v1/users/wallet?page=${page}&size=20`, {
        headers: hdrs, tags: { name: 'wallet_query_storm' },
    });
    walletQueryDur.add(Date.now() - start);
    walletQueryOk.add(res.status === 200);

    sleep(0.05);
}

// ============================================================
// Phase 7: 복합 고부하 (900 VUs peak, 3m)
// 결제40% + 정산조회20% + 지갑조회20% + 실패기록10% + 정산요청10%
// ============================================================
export function mixedHighload() {
    const userId = randomUserId();
    const r = doMixedOp(userId, { pay: 0.40, settleQuery: 0.20, walletQuery: 0.20, fail: 0.10, settleReq: 0.10 });
    stressDur.add(r.dur);
    stressOk.add(r.ok);
    stress5xx.add(r.is5xx);
    sleep(0.02);
}

// ============================================================
// Phase 8: 스파이크 1500 VUs — 순간 폭증 내구성
// ============================================================
export function spike1000() {
    const userId = randomUserId();
    const r = doMixedOp(userId, { pay: 0.50, settleQuery: 0.20, walletQuery: 0.20, fail: 0.10 });
    spikeDur.add(r.dur);
    spikeOk.add(r.ok);
    spike5xx.add(r.is5xx);
    sleep(0.01);
}

// ============================================================
// Phase 9: 이중 스파이크 — 회복 후 재폭증 패턴
// ============================================================
export function doubleSpike() {
    const userId = randomUserId();
    const r = doMixedOp(userId, { pay: 0.45, settleQuery: 0.20, walletQuery: 0.20, fail: 0.15 });
    dblSpikeOk.add(r.ok);
    dblSpike5xx.add(r.is5xx);
    sleep(0.01); // Redis 커넥션 회복 여유
}

// ============================================================
// Phase 10: 지속 내구 Soak (600 VUs peak, 3m)
// 메모리 누수, GC 압력, 커넥션풀 고갈, Kafka lag 누적 탐지
// ============================================================
export function soakTest() {
    const userId = randomUserId();
    const r = doMixedOp(userId, { pay: 0.35, settleQuery: 0.25, walletQuery: 0.25, fail: 0.10, settleReq: 0.05 });
    soakDur.add(r.dur);
    soakOk.add(r.ok);
    soak5xx.add(r.is5xx);
    sleep(0.05);
}

// ============================================================
// Phase 11: 최종 정합성 검증
// ============================================================
export function finalVerify() {
    const user = makeUser(1);
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 1. 결제 플로우 정상
    const orderId = uniqueOrderId('verify-final');
    const result = doPaymentFlow(1, orderId, 1000);
    let ok = check(null, { 'verify: payment flow': () => result.success });
    verifyOk.add(ok);
    console.log(`[VERIFY] Payment: success=${result.success}, status=${result.status}`);

    // 2. 지갑 조회 정상
    const walletRes = http.get(`${BASE_URL}/api/v1/users/wallet?page=0&size=5`, {
        headers: hdrs, tags: { name: 'verify_wallet' },
    });
    ok = check(walletRes, { 'verify: wallet 200': (r) => r.status === 200 });
    verifyOk.add(ok);

    // 3. 정산 조회 정상
    const settleRes = http.get(
        `${BASE_URL}/api/v1/clubs/${clubForSchedule(5000000)}/schedules/5000000/settlements?page=0&size=20`,
        { headers: hdrs, tags: { name: 'verify_settle' } }
    );
    ok = check(settleRes, { 'verify: settlement 200': (r) => r.status === 200 });
    verifyOk.add(ok);

    // 4. save + verify 왕복
    const testOrderId = uniqueOrderId('verify-roundtrip');
    const saveRes = http.post(`${BASE_URL}/api/v1/payments/save`,
        JSON.stringify({ orderId: testOrderId, amount: 500 }),
        { headers: hdrs }
    );
    ok = check(saveRes, { 'verify: save 200': (r) => r.status === 200 });
    verifyOk.add(ok);

    const successRes = http.post(`${BASE_URL}/api/v1/payments/success`,
        JSON.stringify({ orderId: testOrderId, amount: 500 }),
        { headers: hdrs }
    );
    ok = check(successRes, { 'verify: success 200': (r) => r.status === 200 });
    verifyOk.add(ok);

    console.log(`[VERIFY] wallet=${walletRes.status}, settlement=${settleRes.status}`);
}

// ============================================
// handleSummary — Finance 부하 테스트 결과 리포트
// ============================================
export function handleSummary(data) {
    const line = '─'.repeat(60);

    let summary = `
╔════════════════════════════════════════════════════════════╗
║            Finance 모듈 부하 테스트 결과                    ║
╚════════════════════════════════════════════════════════════╝
`;

    const metrics = [
        ['결제 플로우',         'pay_flow_duration'],
        ['결제 Confirm',       'pay_confirm_duration'],
        ['정산 요청',           'settle_request_duration'],
        ['정산 조회',           'settle_query_duration'],
        ['지갑 조회',           'wallet_query_duration'],
        ['복합 고부하',         'stress_duration'],
        ['스파이크',            'spike_duration'],
        ['내구(Soak)',          'soak_duration'],
    ];

    summary += `\n${line}\n`;
    summary += `${'API'.padEnd(22)} ${'p50'.padStart(8)} ${'p95'.padStart(8)} ${'p99'.padStart(8)} ${'max'.padStart(8)}  ${'avg'.padStart(8)}\n`;
    summary += `${line}\n`;

    for (const [label, key] of metrics) {
        const m = data.metrics[key];
        if (m && m.values) {
            const v = m.values;
            summary += `${label.padEnd(22)} ${fmt(v['p(50)'])} ${fmt(v['p(95)'])} ${fmt(v['p(99)'])} ${fmt(v['max'])}  ${fmt(v['avg'])}\n`;
        }
    }
    summary += `${line}\n`;

    // Phase별 성공률
    const phases = [
        ['Phase 2 PayStorm',    'pay_flow_success'],
        ['Phase 2 Confirm',     'pay_confirm_success'],
        ['Phase 3 Idempotency', 'idemp_correct_response'],
        ['Phase 4 Settlement',  'settle_request_success'],
        ['Phase 5 SettleQuery', 'settle_query_success'],
        ['Phase 6 WalletQuery', 'wallet_query_success'],
        ['Phase 7 Stress',      'stress_success'],
        ['Phase 8 Spike',       'spike_success'],
        ['Phase 9 DblSpike',    'dbl_spike_success'],
        ['Phase 10 Soak',       'soak_success'],
        ['Phase 11 Verify',     'verify_pass'],
    ];

    summary += `\n${'Phase'.padEnd(22)} ${'성공률'.padStart(10)}\n`;
    summary += `${line}\n`;
    for (const [label, key] of phases) {
        const m = data.metrics[key];
        if (m && m.values) {
            const rate = (m.values['rate'] * 100).toFixed(2) + '%';
            summary += `${label.padEnd(22)} ${rate.padStart(10)}\n`;
        }
    }
    summary += `${line}\n`;

    // 5xx 비율
    const stress5xxM = data.metrics['stress_5xx_rate'];
    const spike5xxM = data.metrics['spike_5xx_rate'];
    const soak5xxM = data.metrics['soak_5xx_rate'];
    summary += `\n5xx 비율 — Stress: ${stress5xxM && stress5xxM.values ? (stress5xxM.values['rate'] * 100).toFixed(2) + '%' : 'N/A'}`;
    summary += `  |  Spike: ${spike5xxM && spike5xxM.values ? (spike5xxM.values['rate'] * 100).toFixed(2) + '%' : 'N/A'}`;
    summary += `  |  Soak: ${soak5xxM && soak5xxM.values ? (soak5xxM.values['rate'] * 100).toFixed(2) + '%' : 'N/A'}\n`;

    // Thresholds PASS/FAIL
    let passCount = 0, failCount = 0;
    if (data.metrics) {
        for (const [, val] of Object.entries(data.metrics)) {
            if (val.thresholds) {
                for (const [, th] of Object.entries(val.thresholds)) {
                    if (th.ok) passCount++;
                    else failCount++;
                }
            }
        }
    }
    summary += `Thresholds: ${passCount} PASS / ${failCount} FAIL\n`;

    console.log(summary);

    return { 'stdout': summary };
}

function fmt(ms) {
    if (ms === undefined || ms === null) return 'N/A'.padStart(8);
    if (ms < 1000) return (ms.toFixed(0) + 'ms').padStart(8);
    return ((ms / 1000).toFixed(2) + 's').padStart(8);
}
