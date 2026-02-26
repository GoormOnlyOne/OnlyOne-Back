import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { generateJWT, BASE_URL, headers as authHeaders } from './lib/common.js';

// ============================================
// Finance 도메인 병목 탐지 테스트 (~10분)
// 대상: Payment(Redis+CAS), Settlement(조회), Wallet(조회)
// 전략: VU별 고유 유저 + 고유 orderId로 충돌 최소화
//
// 사전 조건: scale-finance-data.sql 실행 필요
//   - wallet 2,000건 (유저당 1개)
//   - schedule 200건, settlement 200건, user_settlement ~2,000건
//   - wallet_transaction 600건
// ============================================

// 커스텀 메트릭 — Payment
const paymentSaveDuration = new Trend('payment_save_duration', true);
const paymentVerifyDuration = new Trend('payment_verify_duration', true);
const paymentFailDuration = new Trend('payment_fail_duration', true);
const paymentConfirmDuration = new Trend('payment_confirm_duration', true);
const paymentSaveErrors = new Counter('payment_save_errors');
const paymentVerifyErrors = new Counter('payment_verify_errors');
const paymentConfirmErrors = new Counter('payment_confirm_errors');

// 커스텀 메트릭 — Settlement
const settlementListDuration = new Trend('settlement_list_duration', true);
const settlementListErrors = new Counter('settlement_list_errors');

// 커스텀 메트릭 — Wallet
const walletListDuration = new Trend('wallet_list_duration', true);
const walletListErrors = new Counter('wallet_list_errors');

// 공통
const errorRate = new Rate('errors');
const serverErrorRate = new Rate('server_error_rate');

// ============================================
// 테스트 설정
// ============================================
export const options = {
    scenarios: {
        // Phase 1: Payment Redis 플로우 (save → verify) — 200 VU, 2.5분
        payment_redis_flow: {
            executor: 'ramping-vus',
            exec: 'paymentRedisFlow',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 200 },
                { duration: '1m30s', target: 200 },
                { duration: '30s', target: 0 },
            ],
            startTime: '0s',
            gracefulRampDown: '10s',
        },
        // Phase 2: Payment Confirm CAS 경합 — 300 VU, 2.5분
        payment_confirm_contention: {
            executor: 'ramping-vus',
            exec: 'paymentConfirmContention',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 300 },
                { duration: '1m30s', target: 300 },
                { duration: '30s', target: 0 },
            ],
            startTime: '3m',
            gracefulRampDown: '10s',
        },
        // Phase 3: 전체 혼합 (Payment + Settlement 조회 + Wallet 조회) — 400 VU, 3분
        mixed_finance: {
            executor: 'ramping-vus',
            exec: 'mixedFinance',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 400 },
                { duration: '2m', target: 400 },
                { duration: '30s', target: 0 },
            ],
            startTime: '6m',
            gracefulRampDown: '10s',
        },
    },
    thresholds: {
        payment_save_duration: ['p(95)<300', 'p(50)<50'],
        payment_verify_duration: ['p(95)<300', 'p(50)<50'],
        payment_confirm_duration: ['p(95)<2000'],
        settlement_list_duration: ['p(95)<500'],
        wallet_list_duration: ['p(95)<500'],
        errors: ['rate<0.1'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('finance_users', function () {
    const users = [];
    for (let i = 1; i <= 2000; i++) {
        users.push({ userId: i, kakaoId: 10000000 + i, status: 'ACTIVE', role: 'ROLE_USER' });
    }
    return users;
});

// Settlement 조회용 (schedule × club 매핑)
// scale-finance-data-large.sql 기준: club 1~50 × SCHEDULES_PER_CLUB개
// schedule_id 시작값은 환경 변수로 전달: SCHEDULE_ID_START
const SCHEDULE_ID_START = parseInt(__ENV.SCHEDULE_ID_START || '15201');
const SCHEDULES_PER_CLUB = parseInt(__ENV.SCHEDULES_PER_CLUB || '100');

const settlementPairs = new SharedArray('settlement_pairs', function () {
    const pairs = [];
    let schedId = SCHEDULE_ID_START;
    for (let clubId = 1; clubId <= 50; clubId++) {
        for (let s = 0; s < SCHEDULES_PER_CLUB; s++) {
            pairs.push({ clubId, scheduleId: schedId++ });
        }
    }
    return pairs; // 50 × SCHEDULES_PER_CLUB
});

// ============================================
// 유틸리티
// ============================================
function reqOpts(token) {
    return { headers: authHeaders(token) };
}

function generateOrderId() {
    return `order_${__VU}_${__ITER}_${Date.now()}`;
}

// ============================================
// Phase 1: Payment Redis 플로우 (save → verify)
// Redis SET/GET 레이턴시, 커넥션풀 경합 탐지
// ============================================
export function paymentRedisFlow() {
    const user = testUsers[(__VU - 1) % testUsers.length];
    const token = generateJWT(user);
    const opts = reqOpts(token);
    const orderId = generateOrderId();
    const amount = Math.floor(Math.random() * 100000) + 1000;

    group('payment_redis', () => {
        // 1. Save (Redis SET)
        const savePayload = JSON.stringify({ orderId, amount });
        const saveRes = http.post(`${BASE_URL}/api/v1/payments/save`, savePayload, opts);
        paymentSaveDuration.add(saveRes.timings.duration);
        errorRate.add(saveRes.status >= 500);
        serverErrorRate.add(saveRes.status >= 500);
        if (saveRes.status >= 500) paymentSaveErrors.add(1);

        check(saveRes, {
            'save: status 200': (r) => r.status === 200,
        });

        if (saveRes.status !== 200) return;

        sleep(0.05);

        // 2. Verify (Redis GET + DELETE)
        const verifyPayload = JSON.stringify({ orderId, amount });
        const verifyRes = http.post(`${BASE_URL}/api/v1/payments/success`, verifyPayload, opts);
        paymentVerifyDuration.add(verifyRes.timings.duration);
        errorRate.add(verifyRes.status >= 500);
        serverErrorRate.add(verifyRes.status >= 500);
        if (verifyRes.status >= 500) paymentVerifyErrors.add(1);

        check(verifyRes, {
            'verify: status 200': (r) => r.status === 200,
        });
    });

    sleep(0.3);
}

// ============================================
// Phase 2: Payment Confirm CAS 경합
// INSERT IGNORE + CAS UPDATE 동시성, Semaphore admission
// Toss API 미설정 → Phase 2에서 실패하지만 Phase 1 CAS 성능은 측정 가능
// reportFail 호출 시 wallet 필요 → scale-finance-data.sql 필수
// ============================================
export function paymentConfirmContention() {
    const user = testUsers[(__VU - 1) % testUsers.length];
    const token = generateJWT(user);
    const opts = reqOpts(token);

    group('payment_confirm', () => {
        const orderId = generateOrderId();
        const paymentKey = `pk_${__VU}_${__ITER}_${Date.now()}`;
        const amount = Math.floor(Math.random() * 50000) + 5000;

        // save → verify → confirm 파이프라인
        const savePayload = JSON.stringify({ orderId, amount });
        const saveRes = http.post(`${BASE_URL}/api/v1/payments/save`, savePayload, opts);
        if (saveRes.status !== 200) {
            errorRate.add(true);
            return;
        }

        const verifyPayload = JSON.stringify({ orderId, amount });
        const verifyRes = http.post(`${BASE_URL}/api/v1/payments/success`, verifyPayload, opts);
        if (verifyRes.status !== 200) {
            errorRate.add(true);
            return;
        }

        // Confirm — Phase 1(claimPayment CAS) → Phase 2(Toss API, 실패 예상)
        const confirmPayload = JSON.stringify({ paymentKey, orderId, amount });
        const confirmRes = http.post(`${BASE_URL}/api/v1/payments/confirm`, confirmPayload, opts);
        paymentConfirmDuration.add(confirmRes.timings.duration);

        // 200: 전체 성공, 400: 비즈니스 에러 (INVALID_PAYMENT_INFO 등)
        // 500은 Toss 미설정 시 발생 가능하나 Phase 1 CAS는 정상 통과한 것
        check(confirmRes, {
            'confirm: got response': (r) => r.status !== 0,
        });

        if (confirmRes.status >= 500) paymentConfirmErrors.add(1);
    });

    sleep(0.3);
}

// ============================================
// Phase 3: 전체 혼합 (Payment + Settlement + Wallet)
// ============================================
export function mixedFinance() {
    const user = testUsers[(__VU - 1) % testUsers.length];
    const token = generateJWT(user);
    const opts = reqOpts(token);
    const action = Math.random();

    group('mixed_finance', () => {
        if (action < 0.35) {
            // 35%: Payment save → verify
            const orderId = generateOrderId();
            const amount = Math.floor(Math.random() * 100000) + 1000;
            const savePayload = JSON.stringify({ orderId, amount });
            const saveRes = http.post(`${BASE_URL}/api/v1/payments/save`, savePayload, opts);
            paymentSaveDuration.add(saveRes.timings.duration);
            errorRate.add(saveRes.status >= 500);
            if (saveRes.status >= 500) paymentSaveErrors.add(1);

            if (saveRes.status === 200) {
                sleep(0.03);
                const verifyPayload = JSON.stringify({ orderId, amount });
                const verifyRes = http.post(`${BASE_URL}/api/v1/payments/success`, verifyPayload, opts);
                paymentVerifyDuration.add(verifyRes.timings.duration);
                errorRate.add(verifyRes.status >= 500);
                if (verifyRes.status >= 500) paymentVerifyErrors.add(1);
            }
        } else if (action < 0.55) {
            // 20%: Payment fail (wallet 필요)
            const orderId = generateOrderId();
            const paymentKey = `pk_fail_${__VU}_${__ITER}_${Date.now()}`;
            const amount = Math.floor(Math.random() * 50000) + 1000;
            const failPayload = JSON.stringify({ paymentKey, orderId, amount });
            const failRes = http.post(`${BASE_URL}/api/v1/payments/fail`, failPayload, opts);
            paymentFailDuration.add(failRes.timings.duration);
            errorRate.add(failRes.status >= 500);
        } else if (action < 0.75) {
            // 20%: Settlement 목록 조회
            const pair = settlementPairs[(__VU + __ITER) % settlementPairs.length];
            const res = http.get(
                `${BASE_URL}/api/v1/clubs/${pair.clubId}/schedules/${pair.scheduleId}/settlements?page=0&size=20`,
                opts
            );
            settlementListDuration.add(res.timings.duration);

            const is5xx = res.status >= 500;
            errorRate.add(is5xx);
            serverErrorRate.add(is5xx);
            if (is5xx) settlementListErrors.add(1);

            check(res, {
                'settlement list: success': (r) => r.status === 200,
            });
        } else {
            // 25%: Wallet 거래 내역 조회
            const filters = ['ALL', 'CHARGE', 'TRANSACTION'];
            const filter = filters[(__VU + __ITER) % filters.length];
            const res = http.get(
                `${BASE_URL}/api/v1/users/wallet?filter=${filter}&page=0&size=20`,
                opts
            );
            walletListDuration.add(res.timings.duration);

            const is5xx = res.status >= 500;
            errorRate.add(is5xx);
            serverErrorRate.add(is5xx);
            if (is5xx) walletListErrors.add(1);

            check(res, {
                'wallet list: success': (r) => r.status === 200,
            });
        }
    });

    sleep(0.2);
}

// ============================================
// Lifecycle
// ============================================
export function setup() {
    console.log('=== Finance Bottleneck Test (~10min) ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('');
    console.log('Phase 1 (0-2.5m):   Payment Redis Flow (save→verify) — 0→200 VU');
    console.log('Phase 2 (3-5.5m):   Payment Confirm CAS Contention — 0→300 VU');
    console.log('Phase 3 (6-9m):     Mixed Finance (Payment+Settlement+Wallet) — 0→400 VU');
    console.log('');
    console.log('Prerequisite: scale-finance-data.sql 실행 필수');
    console.log('========================================');

    const user = testUsers[0];
    const token = generateJWT(user);
    const opts = reqOpts(token);

    // 1. Smoke: Payment save (Redis)
    const orderId = `smoke_setup_${Date.now()}`;
    const saveRes = http.post(`${BASE_URL}/api/v1/payments/save`,
        JSON.stringify({ orderId, amount: 1000 }), opts);
    console.log(`Smoke (payment save): status=${saveRes.status}, ${saveRes.timings.duration.toFixed(0)}ms`);

    // 2. Smoke: Wallet list — wallet 존재 여부 확인
    const walletRes = http.get(`${BASE_URL}/api/v1/users/wallet?filter=ALL&page=0&size=5`, opts);
    console.log(`Smoke (wallet list): status=${walletRes.status}, ${walletRes.timings.duration.toFixed(0)}ms`);
    if (walletRes.status !== 200) {
        console.error('!! Wallet not found — scale-finance-data.sql 실행 필요');
    }

    // 3. Smoke: Settlement list 확인
    //    settlementPairs는 SCHEDULE_ID_START 환경변수 기반으로 구성됨
    const firstPair = settlementPairs[0];
    console.log(`Settlement pairs: SCHEDULE_ID_START=${SCHEDULE_ID_START}, first pair: clubId=${firstPair.clubId}, scheduleId=${firstPair.scheduleId}`);
    const stlRes = http.get(
        `${BASE_URL}/api/v1/clubs/${firstPair.clubId}/schedules/${firstPair.scheduleId}/settlements?page=0&size=5`, opts);
    console.log(`Smoke (settlement list): status=${stlRes.status}, ${stlRes.timings.duration.toFixed(0)}ms`);
    if (stlRes.status !== 200) {
        console.error(`!! Settlement not found at scheduleId=${firstPair.scheduleId} — SCHEDULE_ID_START 값 확인 필요`);
    }

    console.log('========================================');
    return {};
}

export function teardown() {
    console.log('');
    console.log('=== Finance Bottleneck Test Complete ===');
    console.log('Key metrics:');
    console.log('  payment_save_duration    — Redis SET latency');
    console.log('  payment_verify_duration  — Redis GET+DELETE latency');
    console.log('  payment_confirm_duration — CAS INSERT IGNORE + Semaphore');
    console.log('  payment_fail_duration    — DB INSERT + wallet lookup');
    console.log('  settlement_list_duration — Settlement 참여자 조회');
    console.log('  wallet_list_duration     — Wallet 거래내역 조회');
    console.log('  server_error_rate        — 5xx 비율');
    console.log('========================================');
}
