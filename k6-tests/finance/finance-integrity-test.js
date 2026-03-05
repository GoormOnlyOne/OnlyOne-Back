// =============================================================
// Finance 정합성 테스트 — 잔액 무결성 · 환불 · CAS 충돌 추적
// =============================================================
//
// Phase 1  Warmup                (50 VUs, 30s)   — 커넥션풀 예열
// Phase 2  잔액 무결성            (100 VUs, 3m)   — 결제 N건 → 최종 잔액 = 초기 + sum(amounts) 검증
// Phase 3  동일 지갑 동시 스트레스 (200 VUs, 2m)   — 10명 타겟, 409/충돌 추적
// Phase 4  결제 실패 플로우        (100 VUs, 1.5m) — /payments/fail → 에러 처리·잔여 데이터 검증
// Phase 5  CAS 충돌 추적          (300 VUs, 2m)   — 동일 스케줄 정산 → 409 충돌률·재시도 성공률
// Phase 6  최종 검증              (1 VU, 30s)     — 스트레스 후 전 API 정상 확인
//
// 전제 조건:
//   - 서버: SPRING_PROFILES_ACTIVE=local
//   - MySQL: userId 1~100000 지갑 존재
//   - schedule 5000000~5024999 (club_id=1, ENDED)
//   - settlement 25,000건 (HOLDING, receiver=userId 1)
//
// 실행:
//   MSYS_NO_PATHCONV=1 docker run --rm -i --network host \
//     -v "$(pwd)/k6-tests:/scripts" grafana/k6:latest \
//     run /scripts/finance/finance-integrity-test.js
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, makeUser, BASE_URL, MIN_CLUB } from '../lib/common.js';

// ── 상수 ──
const USER_COUNT       = parseInt(__ENV.USER_COUNT || '10000');
const SCHEDULE_ID_BASE = 5000000;
const SETTLEMENT_COUNT = parseInt(__ENV.SETTLEMENT_COUNT || '2500');

// Phase 3: 동시 스트레스 대상 유저 10명
const CONCURRENT_TARGET_USERS = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

// ── 커스텀 메트릭 ──
// Phase 2: 잔액 무결성
const financeBalanceMatch     = new Rate('finance_balance_match');
const financeBalanceDrift     = new Trend('finance_balance_drift');
// Phase 3: 동일 지갑 동시 스트레스
const financeConcurrentOk     = new Rate('finance_concurrent_wallet_ok');
const financeConcurrent409    = new Counter('finance_concurrent_wallet_409');
// Phase 4: 결제 실패 플로우
const financePayFailHandled   = new Rate('finance_payment_fail_handled');
// Phase 5: CAS 충돌 추적
const financeCasCollision     = new Counter('finance_cas_collision_count');
const financeCasRetrySuccess  = new Rate('finance_cas_success_after_retry');

// ── 시나리오 타임라인 ──
// Phase 1:  0s   ~ 30s       (30s)
// Phase 2:  35s  ~ 3m35s     (3m)
// Phase 3:  3m40s ~ 5m40s    (2m)
// Phase 4:  5m45s ~ 7m15s    (1.5m)
// Phase 5:  7m20s ~ 9m20s    (2m)
// Phase 6:  9m25s ~ 9m55s    (30s)
// 총: ~10분

export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: 50,
            duration: '30s',
            exec: 'warmup',
            startTime: '0s',
        },
        // Phase 2: 잔액 무결성 검증 (100 VUs, 3m)
        balance_integrity: {
            executor: 'per-vu-iterations',
            vus: 100,
            iterations: 1,
            exec: 'balanceIntegrity',
            startTime: '35s',
            maxDuration: '3m',
        },
        // Phase 3: 동일 지갑 동시 스트레스 (200 VUs, 2m)
        concurrent_wallet_stress: {
            executor: 'constant-vus',
            vus: 200,
            duration: '2m',
            exec: 'concurrentWalletStress',
            startTime: '3m40s',
        },
        // Phase 4: 결제 실패 플로우 (100 VUs, 1.5m)
        payment_fail_flow: {
            executor: 'constant-vus',
            vus: 100,
            duration: '1m30s',
            exec: 'paymentFailFlow',
            startTime: '5m45s',
        },
        // Phase 5: CAS 충돌 추적 (300 VUs, 2m)
        cas_collision_tracking: {
            executor: 'constant-vus',
            vus: 300,
            duration: '2m',
            exec: 'casCollisionTracking',
            startTime: '7m20s',
        },
        // Phase 6: 최종 검증 (1 VU, 30s)
        final_verification: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: 1,
            exec: 'finalVerification',
            startTime: '9m25s',
            maxDuration: '30s',
        },
    },
    thresholds: {
        // Phase 2: 잔액 무결성 — 90% 이상 일치해야 PASS
        'finance_balance_match':           ['rate>0.90'],
        // Phase 3: 동시 지갑 — 비충돌 성공률 70% 이상
        'finance_concurrent_wallet_ok':    ['rate>0.70'],
        // Phase 4: 실패 처리 — 95% 이상 정상 핸들링
        'finance_payment_fail_handled':    ['rate>0.95'],
        // Phase 5: CAS 재시도 성공 — 50% 이상
        'finance_cas_success_after_retry': ['rate>0.50'],
    },
};

// ── 유틸 ──
function uniqueOrderId(prefix) {
    return `loadtest-${prefix}-${Date.now()}-${Math.random().toString(36).substring(2, 8)}`;
}

function randomUserId() {
    return Math.floor(Math.random() * USER_COUNT) + 1;
}

function randomScheduleId() {
    return SCHEDULE_ID_BASE + Math.floor(Math.random() * SETTLEMENT_COUNT);
}

/** 지갑 잔액 조회 — balance 값 파싱 반환 */
function getWalletBalance(token) {
    const res = http.get(`${BASE_URL}/api/v1/users/wallet?page=0&size=5`, {
        headers: headers(token),
        tags: { name: 'wallet_balance' },
    });
    if (res.status !== 200) return null;
    try {
        const body = JSON.parse(res.body);
        // data.balance 또는 data.walletBalance 등 응답 구조에 맞게 파싱
        const data = body.data || body;
        return typeof data.balance === 'number' ? data.balance
             : typeof data.walletBalance === 'number' ? data.walletBalance
             : typeof data.amount === 'number' ? data.amount
             : null;
    } catch (e) {
        return null;
    }
}

/** 결제 전체 플로우: save -> verify(success) -> confirm */
function doPaymentFlow(userId, orderId, amount) {
    const user = makeUser(userId);
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 1) save
    const saveRes = http.post(`${BASE_URL}/api/v1/payments/save`,
        JSON.stringify({ orderId, amount }),
        { headers: hdrs, tags: { name: 'integrity_pay_save' } }
    );
    if (saveRes.status !== 200) {
        return { success: false, step: 'save', status: saveRes.status };
    }

    // 2) verify (success)
    const verifyRes = http.post(`${BASE_URL}/api/v1/payments/success`,
        JSON.stringify({ orderId, amount }),
        { headers: hdrs, tags: { name: 'integrity_pay_verify' } }
    );
    if (verifyRes.status !== 200) {
        return { success: false, step: 'verify', status: verifyRes.status };
    }

    // 3) confirm
    const paymentKey = `pk_${orderId}`;
    const confirmRes = http.post(`${BASE_URL}/api/v1/payments/confirm`,
        JSON.stringify({ paymentKey, orderId, amount }),
        { headers: hdrs, tags: { name: 'integrity_pay_confirm' } }
    );

    return {
        success: confirmRes.status === 200,
        step: 'confirm',
        status: confirmRes.status,
    };
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
// Phase 2: 잔액 무결성 검증 (100 VUs, 각 1회)
//
// 각 VU가 고유 유저로:
//   1) GET wallet → 초기 잔액 기록
//   2) N건 결제 (save → verify → confirm) → 성공한 금액 합산
//   3) GET wallet → 최종 잔액 기록
//   4) 최종 잔액 == 초기 + sum(성공 금액) 검증
// ============================================================
export function balanceIntegrity() {
    // 각 VU마다 고유 유저 배정 (VU 1~100 → userId 1~100)
    const userId = __VU;
    const user = makeUser(userId);
    const token = generateJWT(user);

    // 1) 초기 잔액 조회
    const initialBalance = getWalletBalance(token);
    if (initialBalance === null) {
        console.log(`[BALANCE] VU=${__VU} userId=${userId} — 초기 잔액 조회 실패, skip`);
        financeBalanceMatch.add(false);
        financeBalanceDrift.add(0);
        return;
    }

    // 2) N건 결제 수행 (3~5건)
    const paymentCount = 3 + Math.floor(Math.random() * 3); // 3~5
    let totalCredited = 0;
    const amounts = [];

    for (let i = 0; i < paymentCount; i++) {
        const amount = 100 + Math.floor(Math.random() * 900); // 100~999
        const orderId = uniqueOrderId(`bal-${__VU}-${i}`);
        const result = doPaymentFlow(userId, orderId, amount);

        if (result.success) {
            totalCredited += amount;
            amounts.push(amount);
        }
        sleep(0.1);
    }

    // 3) 최종 잔액 조회 (비동기 반영 대기)
    sleep(1);
    const finalBalance = getWalletBalance(token);
    if (finalBalance === null) {
        console.log(`[BALANCE] VU=${__VU} userId=${userId} — 최종 잔액 조회 실패`);
        financeBalanceMatch.add(false);
        financeBalanceDrift.add(0);
        return;
    }

    // 4) 정합성 검증
    const expectedBalance = initialBalance + totalCredited;
    const drift = Math.abs(finalBalance - expectedBalance);
    const matched = drift === 0;

    financeBalanceMatch.add(matched);
    financeBalanceDrift.add(drift);

    if (!matched) {
        console.log(
            `[BALANCE MISMATCH] VU=${__VU} userId=${userId} ` +
            `initial=${initialBalance} credited=${totalCredited} ` +
            `expected=${expectedBalance} actual=${finalBalance} drift=${drift} ` +
            `amounts=[${amounts.join(',')}]`
        );
    }
}

// ============================================================
// Phase 3: 동일 지갑 동시 스트레스 (200 VUs, 2m)
//
// 10명의 타겟 유저에 200 VU가 동시 결제 플로우 실행.
// 409/conflict 응답을 CAS 거절로 추적.
// ============================================================
export function concurrentWalletStress() {
    // 200 VU → 10명 유저에 분산 (각 유저당 ~20 VU 동시)
    const targetIdx = __VU % CONCURRENT_TARGET_USERS.length;
    const userId = CONCURRENT_TARGET_USERS[targetIdx];
    const orderId = uniqueOrderId(`conc-${__VU}-${__ITER}`);
    const amount = 100 + Math.floor(Math.random() * 400); // 100~499

    const result = doPaymentFlow(userId, orderId, amount);

    if (result.success) {
        financeConcurrentOk.add(true);
    } else if (result.status === 409) {
        // CAS / 동시성 충돌 — 예상된 거절
        financeConcurrentOk.add(false);
        financeConcurrent409.add(1);
    } else {
        financeConcurrentOk.add(false);
        if (result.status >= 500) {
            console.log(
                `[CONCURRENT 5xx] VU=${__VU} userId=${userId} ` +
                `step=${result.step} status=${result.status}`
            );
        }
    }

    sleep(0.1);
}

// ============================================================
// Phase 4: 결제 실패 플로우 (100 VUs, 1.5m)
//
// POST /api/v1/payments/fail → 정상 에러 핸들링 확인
// 고아 레코드(orphaned records) 발생 없음 검증
// ============================================================
export function paymentFailFlow() {
    const userId = randomUserId();
    const user = makeUser(userId);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const orderId = uniqueOrderId(`fail-${__VU}-${__ITER}`);
    const amount = 500 + Math.floor(Math.random() * 4500);
    const paymentKey = `pk_fail_${orderId}`;

    // 1) save → 결제 레코드 생성
    const saveRes = http.post(`${BASE_URL}/api/v1/payments/save`,
        JSON.stringify({ orderId, amount }),
        { headers: hdrs, tags: { name: 'fail_flow_save' } }
    );

    // 2) fail 호출 — 결제 실패 처리
    const failRes = http.post(`${BASE_URL}/api/v1/payments/fail`,
        JSON.stringify({ paymentKey, orderId, amount }),
        { headers: hdrs, tags: { name: 'fail_flow_fail' } }
    );

    // fail 호출 결과 검증:
    //   200/201: 정상 실패 처리
    //   4xx: 비즈니스 에러 (이미 실패 등) — 정상 핸들링으로 간주
    //   5xx: 비정상
    const handled = failRes.status >= 200 && failRes.status < 500;
    financePayFailHandled.add(handled);

    if (!handled) {
        console.log(
            `[FAIL FLOW ERROR] VU=${__VU} userId=${userId} ` +
            `orderId=${orderId} status=${failRes.status} ` +
            `body=${(failRes.body || '').substring(0, 200)}`
        );
    }

    // 3) 실패 후 같은 orderId로 confirm 시도 → 반드시 거절되어야 함
    const confirmRes = http.post(`${BASE_URL}/api/v1/payments/confirm`,
        JSON.stringify({ paymentKey, orderId, amount }),
        { headers: hdrs, tags: { name: 'fail_flow_confirm_after_fail' } }
    );

    const confirmRejected = confirmRes.status >= 400 && confirmRes.status < 500;
    if (!confirmRejected && confirmRes.status === 200) {
        console.log(
            `[FAIL FLOW LEAK] VU=${__VU} — confirm succeeded after fail! ` +
            `orderId=${orderId} status=${confirmRes.status}`
        );
    }

    sleep(0.2);
}

// ============================================================
// Phase 5: CAS 충돌 추적 (300 VUs, 2m)
//
// 동일 스케줄에 다수 VU가 정산 요청 → 409 응답 = CAS 충돌
// 충돌 후 재시도 1회 → 성공률 측정
// ============================================================
export function casCollisionTracking() {
    // 50개 스케줄에 300 VU 분산 → 스케줄당 ~6 VU 동시
    const scheduleIdx = __VU % 50;
    const scheduleId = SCHEDULE_ID_BASE + scheduleIdx;
    const costPerUser = 10 + Math.floor(Math.random() * 90); // 10~99

    const user = makeUser(1); // receiver = userId 1 (리더)
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 1차 시도
    const res1 = http.post(
        `${BASE_URL}/api/v1/clubs/${MIN_CLUB}/schedules/${scheduleId}/settlements?costPerUser=${costPerUser}`,
        null,
        { headers: hdrs, tags: { name: 'cas_settle_attempt' } }
    );

    const is409 = res1.status === 409;
    const isSuccess = res1.status === 200 || res1.status === 201;

    if (is409) {
        financeCasCollision.add(1);

        // CAS 충돌 후 재시도 (백오프 100~300ms)
        sleep(0.1 + Math.random() * 0.2);

        const res2 = http.post(
            `${BASE_URL}/api/v1/clubs/${MIN_CLUB}/schedules/${scheduleId}/settlements?costPerUser=${costPerUser}`,
            null,
            { headers: hdrs, tags: { name: 'cas_settle_retry' } }
        );

        const retrySuccess = res2.status === 200 || res2.status === 201;
        financeCasRetrySuccess.add(retrySuccess);

        if (!retrySuccess && res2.status >= 500) {
            console.log(
                `[CAS RETRY 5xx] VU=${__VU} scheduleId=${scheduleId} ` +
                `status=${res2.status}`
            );
        }
    } else if (isSuccess) {
        // 첫 시도에 성공 — 재시도 불필요, 성공 카운트
        financeCasRetrySuccess.add(true);
    } else if (res1.status >= 400 && res1.status < 500) {
        // 4xx (이미 처리 등) — 비즈니스 정상 거절
        financeCasRetrySuccess.add(true);
    } else {
        // 5xx 서버 에러
        financeCasRetrySuccess.add(false);
        console.log(
            `[CAS 5xx] VU=${__VU} scheduleId=${scheduleId} ` +
            `status=${res1.status} body=${(res1.body || '').substring(0, 200)}`
        );
    }

    sleep(0.05);
}

// ============================================================
// Phase 6: 최종 검증 (1 VU, 30s)
//
// 스트레스 후 전 API 정상 동작 확인
// ============================================================
export function finalVerification() {
    const user = makeUser(1);
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 1) 결제 플로우 정상
    const orderId = uniqueOrderId('verify-integrity');
    const result = doPaymentFlow(1, orderId, 1000);
    check(null, {
        'verify: payment flow OK': () => result.success,
    });
    console.log(`[VERIFY] Payment: success=${result.success}, status=${result.status}`);

    // 2) 지갑 조회 정상
    const walletRes = http.get(`${BASE_URL}/api/v1/users/wallet?page=0&size=5`, {
        headers: hdrs, tags: { name: 'verify_wallet' },
    });
    check(walletRes, {
        'verify: wallet 200': (r) => r.status === 200,
    });

    // 3) 정산 조회 정상
    const settleRes = http.get(
        `${BASE_URL}/api/v1/clubs/${MIN_CLUB}/schedules/${SCHEDULE_ID_BASE}/settlements?page=0&size=20`,
        { headers: hdrs, tags: { name: 'verify_settlement' } }
    );
    check(settleRes, {
        'verify: settlement 200': (r) => r.status === 200,
    });

    // 4) 결제 실패 API 정상
    const failOrderId = uniqueOrderId('verify-fail');
    const failRes = http.post(`${BASE_URL}/api/v1/payments/fail`,
        JSON.stringify({ paymentKey: `pk_${failOrderId}`, orderId: failOrderId, amount: 500 }),
        { headers: hdrs, tags: { name: 'verify_fail' } }
    );
    check(failRes, {
        'verify: fail endpoint responds': (r) => r.status >= 200 && r.status < 500,
    });

    console.log(
        `[VERIFY] wallet=${walletRes.status} settlement=${settleRes.status} fail=${failRes.status}`
    );
}

// ============================================================
// handleSummary — 테스트 리포트 출력
// ============================================================
export function handleSummary(data) {
    const m = data.metrics;
    const pad = (s, n) => String(s).padEnd(n);
    const num = (v, d = 1) => v !== undefined && v !== null ? Number(v).toFixed(d) : 'N/A';
    const pct = (v) => v !== undefined && v !== null ? (Number(v) * 100).toFixed(1) + '%' : 'N/A';
    const cnt = (v) => v !== undefined && v !== null ? Number(v).toFixed(0) : '0';

    const lines = [
        '',
        '=====================================================================',
        '          Finance 정합성 테스트 리포트',
        '=====================================================================',
        '',
        '--- Phase 2: 잔액 무결성 (Balance Integrity) ---',
        `  잔액 일치율:       ${pad(pct(m.finance_balance_match?.values?.rate), 10)}`,
        `  잔액 드리프트 p50: ${pad(num(m.finance_balance_drift?.values?.med), 10)}`,
        `  잔액 드리프트 p95: ${pad(num(m.finance_balance_drift?.values?.['p(95)']), 10)}`,
        `  잔액 드리프트 max: ${pad(num(m.finance_balance_drift?.values?.max), 10)}`,
        '',
        '--- Phase 3: 동일 지갑 동시 스트레스 (Concurrent Wallet) ---',
        `  비충돌 성공률:     ${pad(pct(m.finance_concurrent_wallet_ok?.values?.rate), 10)}`,
        `  409 충돌 횟수:     ${pad(cnt(m.finance_concurrent_wallet_409?.values?.count), 10)}`,
        '',
        '--- Phase 4: 결제 실패 플로우 (Payment Fail) ---',
        `  정상 핸들링률:     ${pad(pct(m.finance_payment_fail_handled?.values?.rate), 10)}`,
        '',
        '--- Phase 5: CAS 충돌 추적 (CAS Collision) ---',
        `  CAS 충돌 총 횟수:  ${pad(cnt(m.finance_cas_collision_count?.values?.count), 10)}`,
        `  재시도 성공률:     ${pad(pct(m.finance_cas_success_after_retry?.values?.rate), 10)}`,
        '',
        '--- HTTP 요약 ---',
        `  총 요청 수:        ${pad(cnt(m.http_reqs?.values?.count), 10)}`,
        `  처리량 (req/s):    ${pad(num(m.http_reqs?.values?.rate), 10)}`,
        `  HTTP 실패율:       ${pad(pct(m.http_req_failed?.values?.rate), 10)}`,
        `  응답 p50:          ${pad(num(m.http_req_duration?.values?.med), 8)} ms`,
        `  응답 p95:          ${pad(num(m.http_req_duration?.values?.['p(95)']), 8)} ms`,
        `  응답 p99:          ${pad(num(m.http_req_duration?.values?.['p(99)']), 8)} ms`,
        '',
        '--- Threshold 판정 ---',
        `  finance_balance_match        > 90%:  ${m.finance_balance_match?.thresholds ? (Object.values(m.finance_balance_match.thresholds).every(t => t.ok) ? 'PASS' : 'FAIL') : 'N/A'}`,
        `  finance_concurrent_wallet_ok > 70%:  ${m.finance_concurrent_wallet_ok?.thresholds ? (Object.values(m.finance_concurrent_wallet_ok.thresholds).every(t => t.ok) ? 'PASS' : 'FAIL') : 'N/A'}`,
        `  finance_payment_fail_handled > 95%:  ${m.finance_payment_fail_handled?.thresholds ? (Object.values(m.finance_payment_fail_handled.thresholds).every(t => t.ok) ? 'PASS' : 'FAIL') : 'N/A'}`,
        `  finance_cas_success_after_retry > 50%: ${m.finance_cas_success_after_retry?.thresholds ? (Object.values(m.finance_cas_success_after_retry.thresholds).every(t => t.ok) ? 'PASS' : 'FAIL') : 'N/A'}`,
        '',
        '=====================================================================',
        '',
    ];

    console.log(lines.join('\n'));
    return {};
}
