// =============================================================
// Finance 모듈 고부하 테스트 — 결제·정산·지갑 트랜잭션 정합성
// =============================================================
//
// Phase 1  Warmup           (50 VUs, 30s)  — 커넥션풀 예열
// Phase 2  결제 폭풍         (1200 VUs peak, 2m)  — save→verify→confirm 대량 발사
// Phase 3  멱등성 폭풍       (1500 VUs, 30s)  — 동일 orderId 1500명 동시 confirm
// Phase 4  정산 대량 요청    (1500 VUs, 2m)   — settlement 25,000건 동시 Outbox→Kafka E2E
// Phase 5  정산 조회 폭풍    (900 VUs peak, 1.5m) — 정산 상태/참여자 리스트 집중 조회
// Phase 6  지갑 조회 집중    (900 VUs peak, 1.5m) — 거래내역 페이징 집중
// Phase 7  복합 고부하       (1800 VUs peak, 3m) — 결제40%+정산조회20%+지갑조회20%+실패기록10%+정산요청10%
// Phase 8  스파이크 3000     (3000 VUs peak, 1.5m) — 순간 폭증 내구성
// Phase 9  이중 스파이크     (2400 VUs peak, 2m) — 회복 후 재폭증
// Phase 10 지속 내구         (1200 VUs, 3m) — Soak: 누수/GC/커넥션풀 고갈 탐지
// Phase 11 최종 검증         (1 VU, 30s)  — 전 API 정상 확인
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
import { generateJWT, headers, makeUser, BASE_URL, MIN_CLUB } from './lib/common.js';

// ── 상수 ──
const VALID_USER_COUNT = 100000;
const SETTLEMENT_COUNT = 25000;    // schedule 5000000~5024999 (club_id=1)
const SCHEDULE_ID_BASE = 5000000;   // scheduleId = 5000000 + (0~24999)

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

// ── 시나리오 타임라인 ──
// Phase 1:  0s ~ 30s          (30s)
// Phase 2:  35s ~ 2m35s       (2m)
// Phase 3:  35s ~ 1m05s       (30s, Phase2와 겹침)
// Phase 4:  2m40s ~ 4m40s     (2m)
// Phase 5:  4m50s ~ 6m20s     (1.5m)
// Phase 6:  4m50s ~ 6m20s     (1.5m, Phase5와 겹침)
// Phase 7:  6m30s ~ 9m30s     (3m)
// Phase 8:  9m40s ~ 11m10s    (1.5m)
// Phase 9:  11m40s ~ 13m40s   (2m)  — 30s 쿨다운 후 시작
// Phase 10: 14m00s ~ 17m00s   (3m)
// Phase 11: 17m10s ~ 17m40s   (30s)
// 총: ~17분 40초

export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: 150,
            duration: '30s',
            exec: 'warmup',
            startTime: '0s',
        },
        // Phase 2: 결제 폭풍 (1200 VUs peak)
        payment_storm: {
            executor: 'ramping-vus',
            stages: [
                { duration: '20s', target: 1200 },
                { duration: '80s', target: 1200 },
                { duration: '20s', target: 0 },
            ],
            exec: 'paymentStorm',
            startTime: '35s',
        },
        // Phase 3: 멱등성 폭풍 (1500 VUs 동시 confirm)
        payment_idempotency: {
            executor: 'per-vu-iterations',
            vus: 1500,
            iterations: 1,
            exec: 'paymentIdempotency',
            startTime: '35s',
            maxDuration: '30s',
        },
        // Phase 4: 정산 대량 요청 (1500 VUs, 각 1회 → 1500건 동시 정산)
        settlement_mass: {
            executor: 'per-vu-iterations',
            vus: 1500,
            iterations: 1,
            exec: 'settlementMass',
            startTime: '2m40s',
            maxDuration: '2m',
        },
        // Phase 5: 정산 조회 폭풍 (900 VUs peak)
        settlement_query_storm: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 900 },
                { duration: '60s', target: 900 },
                { duration: '15s', target: 0 },
            ],
            exec: 'settlementQueryStorm',
            startTime: '4m50s',
        },
        // Phase 6: 지갑 조회 집중 (900 VUs peak)
        wallet_query_storm: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 900 },
                { duration: '60s', target: 900 },
                { duration: '15s', target: 0 },
            ],
            exec: 'walletQueryStorm',
            startTime: '4m50s',
        },
        // Phase 7: 복합 고부하 (1800 VUs peak, 3m)
        mixed_highload: {
            executor: 'ramping-vus',
            stages: [
                { duration: '30s', target: 1800 },
                { duration: '120s', target: 1800 },
                { duration: '30s', target: 0 },
            ],
            exec: 'mixedHighload',
            startTime: '6m30s',
        },
        // Phase 8: 스파이크 3000 VUs
        spike_1000: {
            executor: 'ramping-vus',
            stages: [
                { duration: '10s', target: 3000 },
                { duration: '50s', target: 3000 },
                { duration: '15s', target: 10 },
                { duration: '15s', target: 10 },
            ],
            exec: 'spike1000',
            startTime: '9m40s',
        },
        // Phase 9: 이중 스파이크 (2400 VUs peak) — Phase 8 후 30s 쿨다운
        double_spike: {
            executor: 'ramping-vus',
            stages: [
                { duration: '10s', target: 2400 },
                { duration: '20s', target: 2400 },
                { duration: '10s', target: 20 },
                { duration: '10s', target: 20 },
                { duration: '10s', target: 2400 },
                { duration: '20s', target: 2400 },
                { duration: '10s', target: 20 },
                { duration: '10s', target: 0 },
            ],
            exec: 'doubleSpike',
            startTime: '11m40s',
        },
        // Phase 10: 지속 내구 Soak (1200 VUs, 3m)
        soak: {
            executor: 'ramping-vus',
            stages: [
                { duration: '20s', target: 1200 },
                { duration: '140s', target: 1200 },
                { duration: '20s', target: 0 },
            ],
            exec: 'soakTest',
            startTime: '14m00s',
        },
        // Phase 11: 최종 검증 (쿨다운 10초 후)
        final_verify: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: 1,
            exec: 'finalVerify',
            startTime: '17m10s',
            maxDuration: '30s',
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
            `${BASE_URL}/api/v1/clubs/${MIN_CLUB}/schedules/${scheduleId}/settlements?page=0&size=20`,
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
        // 정산 요청 (소액) — receiver=userId1(리더)로 고정
        const leaderToken = generateJWT(makeUser(1));
        const leaderHdrs = headers(leaderToken);
        const scheduleId = randomScheduleId();
        res = http.post(
            `${BASE_URL}/api/v1/clubs/${MIN_CLUB}/schedules/${scheduleId}/settlements?costPerUser=10`,
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
// Phase 2: 결제 폭풍 (1200 VUs peak, 2분)
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
// Phase 3: 멱등성 폭풍 (1500 VUs 동시 confirm)
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
// Phase 4: 정산 대량 요청 (1500 VUs, 각 1회 → 1500건 동시 정산)
// Outbox → Kafka → StructuredTaskScope(captureHold) → LedgerWriter
// ============================================================
export function settlementMass() {
    // VU 번호를 settlement 1~500에 매핑
    const settlementIdx = (__VU % SETTLEMENT_COUNT) + 1;
    const scheduleId = SCHEDULE_ID_BASE + settlementIdx;
    const costPerUser = 100;

    const user = makeUser(1); // receiver = userId 1
    const token = generateJWT(user);
    const hdrs = headers(token);

    const start = Date.now();
    const res = http.post(
        `${BASE_URL}/api/v1/clubs/${MIN_CLUB}/schedules/${scheduleId}/settlements?costPerUser=${costPerUser}`,
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
                `${BASE_URL}/api/v1/clubs/${MIN_CLUB}/schedules/${scheduleId}/settlements?page=0&size=20`,
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
// Phase 5: 정산 조회 폭풍 (900 VUs peak)
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
        `${BASE_URL}/api/v1/clubs/${MIN_CLUB}/schedules/${scheduleId}/settlements?page=${page}&size=20`,
        { headers: hdrs, tags: { name: 'settle_query_storm' } }
    );
    settleQueryDur.add(Date.now() - start);
    settleQueryOk.add(res.status === 200);

    sleep(0.05);
}

// ============================================================
// Phase 6: 지갑 조회 집중 (900 VUs peak)
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
// Phase 7: 복합 고부하 (1800 VUs peak, 3m)
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
// Phase 8: 스파이크 3000 VUs — 순간 폭증 내구성
// ============================================================
export function spike1000() {
    const userId = randomUserId();
    const r = doMixedOp(userId, { pay: 0.50, settleQuery: 0.20, walletQuery: 0.20, fail: 0.10 });
    spikeDur.add(r.dur);
    spikeOk.add(r.ok);
    spike5xx.add(r.is5xx);
    // no sleep — max throughput
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
// Phase 10: 지속 내구 Soak (1200 VUs, 3m)
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
        `${BASE_URL}/api/v1/clubs/${MIN_CLUB}/schedules/5000000/settlements?page=0&size=20`,
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
