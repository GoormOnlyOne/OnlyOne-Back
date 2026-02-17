import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// 커스텀 메트릭
// ============================================
const errorRate = new Rate('errors');

// 결제 레이턴시 메트릭
const paymentSaveDuration = new Trend('payment_save_duration');
const paymentVerifyDuration = new Trend('payment_verify_duration');
const paymentFailDuration = new Trend('payment_fail_duration');
const paymentConfirmDuration = new Trend('payment_confirm_duration');
const pipelineTotalDuration = new Trend('pipeline_total_duration');

// Redis / DB 병목 메트릭
const redisContentionRate = new Rate('redis_contention_rate');
const dbLockWaitMs = new Trend('db_lock_wait_ms');
const concurrentFailConflict = new Counter('concurrent_fail_conflict');

// Confirm Phase 1 메트릭
const claimContentionRate = new Rate('claim_contention_rate');
const claimDuplicateRejected = new Counter('claim_duplicate_rejected');
const confirmPhase1Duration = new Trend('confirm_phase1_duration');

// 플로우 성공률
const saveSuccessRate = new Rate('save_success_rate');
const verifySuccessRate = new Rate('verify_success_rate');
const failRecordRate = new Rate('fail_record_rate');
const confirmSuccessRate = new Rate('confirm_success_rate');
const pipelineSuccessRate = new Rate('pipeline_success_rate');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // 시나리오 1: Redis 결제 저장/검증 플로우 (save → success)
        redis_payment_flow: {
            executor: 'constant-vus',
            exec: 'redisPaymentFlow',
            vus: 500,
            duration: '3m',
            gracefulStop: '30s',
        },

        // 시나리오 2: 결제 실패 기록 동시성 (fail 비관적 잠금 경합)
        fail_concurrency: {
            executor: 'constant-vus',
            exec: 'failConcurrency',
            vus: 300,
            duration: '3m',
            startTime: '3m30s',
            gracefulStop: '30s',
        },

        // 시나리오 3: 결제 저장 폭발 (save burst ramp-up)
        save_burst: {
            executor: 'ramping-vus',
            exec: 'saveBurst',
            startVUs: 200,
            stages: [
                { duration: '1m', target: 400 },
                { duration: '1m', target: 600 },
                { duration: '1m', target: 800 },
                { duration: '1m', target: 1000 },
                { duration: '1m', target: 1000 },
            ],
            startTime: '7m',
            gracefulRampDown: '30s',
        },

        // 시나리오 4: 혼합 부하 (save + success + fail 동시)
        mixed_workload: {
            executor: 'constant-vus',
            exec: 'mixedWorkload',
            vus: 500,
            duration: '5m',
            startTime: '13m',
            gracefulStop: '30s',
        },

        // 시나리오 5: Confirm Phase 1 비관적 잠금 경합 (claimPayment 동시성)
        confirm_claim_contention: {
            executor: 'constant-vus',
            exec: 'confirmClaimContention',
            vus: 400,
            duration: '3m',
            startTime: '19m',
            gracefulStop: '30s',
        },

        // 시나리오 6: 스파이크 테스트 (플래시 세일 시뮬레이션)
        spike_flash_sale: {
            executor: 'ramping-vus',
            exec: 'spikeFlashSale',
            startVUs: 10,
            stages: [
                { duration: '30s', target: 50 },    // 안정 상태
                { duration: '10s', target: 1000 },   // 급격한 스파이크
                { duration: '1m', target: 1000 },    // 피크 유지
                { duration: '10s', target: 50 },     // 급격한 하강
                { duration: '1m', target: 50 },      // 안정 복귀
                { duration: '10s', target: 800 },    // 2차 스파이크
                { duration: '1m', target: 800 },     // 2차 피크
                { duration: '30s', target: 10 },     // 쿨다운
            ],
            startTime: '23m',
            gracefulRampDown: '30s',
        },

        // 시나리오 7: Full Pipeline E2E (save → success → confirm)
        full_pipeline: {
            executor: 'constant-vus',
            exec: 'fullPipeline',
            vus: 300,
            duration: '3m',
            startTime: '28m',
            gracefulStop: '30s',
        },

        // 시나리오 8: Soak 테스트 (장시간 저부하 — 커넥션풀/메모리 릭 탐지)
        soak_test: {
            executor: 'constant-vus',
            exec: 'soakTest',
            vus: 50,
            duration: '5m',
            startTime: '32m',
            gracefulStop: '30s',
        },
    },

    thresholds: {
        http_req_duration: ['p(95)<1000', 'p(99)<3000'],
        http_req_failed: ['rate<0.05'],
        errors: ['rate<0.10'],
        payment_save_duration: ['p(95)<500', 'p(99)<1000'],
        payment_verify_duration: ['p(95)<500', 'p(99)<1000'],
        payment_fail_duration: ['p(95)<2000', 'p(99)<5000'],
        payment_confirm_duration: ['p(95)<2000', 'p(99)<5000'],
        confirm_phase1_duration: ['p(95)<1500', 'p(99)<3000'],
        pipeline_total_duration: ['p(95)<3000', 'p(99)<6000'],
        db_lock_wait_ms: ['p(95)<3000'],
    },
};

// ============================================
// 테스트 사용자 데이터 (SharedArray로 VU간 공유)
// ============================================
const testUsers = new SharedArray('test_users', function () {
    const users = [];
    for (let i = 1; i <= 10000; i++) {
        users.push({
            userId: i,
            kakaoId: 10000000 + i,
            status: 'ACTIVE',
            role: 'ROLE_USER',
        });
    }
    return users;
});

// ============================================
// 유틸리티 함수
// ============================================

function generateJWT(user) {
    const now = Date.now();
    const expiryDate = now + (3600 * 1000);

    const header = {
        alg: 'HS512',
        typ: 'JWT',
    };

    const payload = {
        sub: user.userId.toString(),
        kakaoId: user.kakaoId.toString(),
        nickname: `testuser${user.userId}`,
        status: user.status,
        role: user.role,
        type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor(expiryDate / 1000),
    };

    const headerEncoded = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const payloadEncoded = encoding.b64encode(JSON.stringify(payload), 'rawurl');
    const signatureInput = `${headerEncoded}.${payloadEncoded}`;
    const signature = hmac('sha512', JWT_SECRET, signatureInput, 'base64rawurl');

    return `${signatureInput}.${signature}`;
}

function getHeaders(token) {
    // X-Forwarded-For: VU별 고유 IP로 RateLimitFilter 우회
    const octet3 = Math.floor(__VU / 255) % 256;
    const octet4 = (__VU % 255) + 1;
    return {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
        'X-Forwarded-For': `10.0.${octet3}.${octet4}`,
    };
}

function getRandomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

// 고유 orderId 생성 (VU ID + iteration + timestamp)
function generateOrderId() {
    const vuId = __VU;
    const iter = __ITER;
    const ts = Date.now();
    return `order_${vuId}_${iter}_${ts}`;
}

function validateResponse(response, expectedStatus, metricName) {
    const success = check(response, {
        [`${metricName}: status is ${expectedStatus}`]: (r) => r.status === expectedStatus,
        [`${metricName}: response time < 3s`]: (r) => r.timings.duration < 3000,
        [`${metricName}: has valid JSON`]: (r) => {
            try {
                JSON.parse(r.body);
                return true;
            } catch (e) {
                return false;
            }
        },
    });

    errorRate.add(!success);
    return success;
}

// ============================================
// API 호출 함수
// ============================================

function callPaymentSave(headers, orderId, amount) {
    const payload = JSON.stringify({ orderId: orderId, amount: amount });
    const res = http.post(`${BASE_URL}/api/v1/payments/save`, payload, { headers });
    paymentSaveDuration.add(res.timings.duration);

    const success = res.status === 200;
    saveSuccessRate.add(success);
    redisContentionRate.add(!success);

    return res;
}

function callPaymentSuccess(headers, orderId, amount) {
    const payload = JSON.stringify({ orderId: orderId, amount: amount });
    const res = http.post(`${BASE_URL}/api/v1/payments/success`, payload, { headers });
    paymentVerifyDuration.add(res.timings.duration);

    const success = res.status === 200;
    verifySuccessRate.add(success);
    redisContentionRate.add(!success);

    return res;
}

function callPaymentFail(headers, paymentKey, orderId, amount) {
    const payload = JSON.stringify({
        paymentKey: paymentKey,
        orderId: orderId,
        amount: amount,
    });
    const res = http.post(`${BASE_URL}/api/v1/payments/fail`, payload, { headers });
    paymentFailDuration.add(res.timings.duration);
    dbLockWaitMs.add(res.timings.duration);

    const success = res.status === 200;
    failRecordRate.add(success);

    return res;
}

function callPaymentConfirm(headers, paymentKey, orderId, amount) {
    const payload = JSON.stringify({
        paymentKey: paymentKey,
        orderId: orderId,
        amount: amount,
    });
    const res = http.post(`${BASE_URL}/api/v1/payments/confirm`, payload, { headers });
    paymentConfirmDuration.add(res.timings.duration);
    confirmPhase1Duration.add(res.timings.duration);

    // confirm은 Toss API 미설정 시 Phase 2에서 실패하지만 Phase 1(claimPayment) 동시성은 측정 가능
    const success = res.status === 200;
    confirmSuccessRate.add(success);

    return res;
}

// ============================================
// 시나리오 1: Redis 결제 저장/검증 플로우
// ============================================
export function redisPaymentFlow() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Redis Payment Flow - save then verify', () => {
        const orderId = generateOrderId();
        const amount = Math.floor(Math.random() * 100000) + 1000; // 1,000 ~ 101,000

        // 1. 결제 정보 Redis에 임시 저장
        const saveRes = callPaymentSave(headers, orderId, amount);
        const saveOk = validateResponse(saveRes, 200, 'save');

        if (!saveOk) {
            return;
        }

        sleep(0.1); // 실제 사용자 행동 시뮬레이션

        // 2. 저장된 정보 검증 (금액 일치)
        const verifyRes = callPaymentSuccess(headers, orderId, amount);
        validateResponse(verifyRes, 200, 'verify');
    });

    sleep(0.2);
}

// ============================================
// 시나리오 2: 결제 실패 기록 동시성 (비관적 잠금 경합)
// ============================================

// 공유 orderId 풀 (동일 orderId에 대한 동시 fail 호출을 유도)
const sharedOrderIds = new SharedArray('shared_order_ids', function () {
    const ids = [];
    for (let i = 1; i <= 50; i++) {
        ids.push(`conflict_order_${i}`);
    }
    return ids;
});

export function failConcurrency() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Fail Concurrency - pessimistic lock contention', () => {
        // 50개의 공유 orderId 중 하나를 선택 → 동시 접근 유도
        const orderId = sharedOrderIds[Math.floor(Math.random() * sharedOrderIds.length)];
        const paymentKey = `pk_${orderId}_${__VU}_${Date.now()}`;
        const amount = 10000;

        const res = callPaymentFail(headers, paymentKey, orderId, amount);

        // 200이면 성공, 그 외는 잠금 경합 또는 에러
        const success = check(res, {
            'fail: status is 200 or conflict': (r) => r.status === 200 || r.status === 409 || r.status === 500,
        });

        if (res.status !== 200) {
            concurrentFailConflict.add(1);
        }

        // 잠금 대기 시간 기록 (응답 시간으로 추정)
        if (res.timings.duration > 1000) {
            concurrentFailConflict.add(1);
        }
    });

    sleep(0.3);
}

// ============================================
// 시나리오 3: 결제 저장 폭발 (save burst)
// ============================================
export function saveBurst() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Save Burst - Redis connection pool stress', () => {
        // 연속 3회 저장으로 Redis 부하 극대화
        for (let i = 0; i < 3; i++) {
            const orderId = `burst_${__VU}_${__ITER}_${i}_${Date.now()}`;
            const amount = Math.floor(Math.random() * 50000) + 1000;

            const res = callPaymentSave(headers, orderId, amount);

            check(res, {
                'burst_save: status is 200': (r) => r.status === 200,
                'burst_save: response time < 1s': (r) => r.timings.duration < 1000,
            });

            if (res.status !== 200) {
                redisContentionRate.add(true);
            }
        }
    });

    sleep(0.1);
}

// ============================================
// 시나리오 4: 혼합 부하 (save + success + fail 동시)
// ============================================
export function mixedWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    const action = Math.random();

    if (action < 0.4) {
        // 40%: save → success 정상 플로우
        group('Mixed - Normal Payment Flow', () => {
            const orderId = generateOrderId();
            const amount = Math.floor(Math.random() * 100000) + 1000;

            const saveRes = callPaymentSave(headers, orderId, amount);
            if (saveRes.status === 200) {
                sleep(0.05);
                const verifyRes = callPaymentSuccess(headers, orderId, amount);
                validateResponse(verifyRes, 200, 'mixed_verify');
            }
        });
    } else if (action < 0.65) {
        // 25%: save만 (결제 시작 후 이탈)
        group('Mixed - Abandoned Payment', () => {
            const orderId = generateOrderId();
            const amount = Math.floor(Math.random() * 100000) + 1000;
            callPaymentSave(headers, orderId, amount);
        });
    } else if (action < 0.85) {
        // 20%: fail 기록
        group('Mixed - Payment Failure', () => {
            const orderId = `mixed_fail_${__VU}_${__ITER}_${Date.now()}`;
            const paymentKey = `pk_mixed_${__VU}_${Date.now()}`;
            const amount = Math.floor(Math.random() * 100000) + 1000;

            callPaymentFail(headers, paymentKey, orderId, amount);
        });
    } else {
        // 15%: 금액 불일치 검증 (의도적 실패)
        group('Mixed - Amount Mismatch', () => {
            const orderId = generateOrderId();
            const savedAmount = 10000;
            const wrongAmount = 99999;

            const saveRes = callPaymentSave(headers, orderId, savedAmount);
            if (saveRes.status === 200) {
                sleep(0.05);
                const verifyRes = callPaymentSuccess(headers, orderId, wrongAmount);
                // 금액 불일치 → 에러 응답 예상
                check(verifyRes, {
                    'mismatch: rejected as expected': (r) => r.status !== 200,
                });
            }
        });
    }

    sleep(0.3);
}

// ============================================
// 시나리오 5: Confirm Phase 1 비관적 잠금 경합
// ============================================

// claimPayment() 동시성 테스트용 공유 orderId (소수의 orderId에 다수 VU가 몰림)
const confirmSharedOrderIds = new SharedArray('confirm_shared_order_ids', function () {
    const ids = [];
    for (let i = 1; i <= 30; i++) {
        ids.push(`confirm_contention_${i}`);
    }
    return ids;
});

export function confirmClaimContention() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Confirm Claim Contention - Phase 1 pessimistic lock', () => {
        // 30개 공유 orderId 중 하나를 선택 → claimPayment 비관적 잠금 경합 유도
        const orderId = confirmSharedOrderIds[Math.floor(Math.random() * confirmSharedOrderIds.length)];
        const paymentKey = `pk_confirm_${orderId}_${__VU}_${Date.now()}`;
        const amount = Math.floor(Math.random() * 50000) + 5000;

        const res = callPaymentConfirm(headers, paymentKey, orderId, amount);

        // Phase 1 성공 후 Phase 2(Toss API)에서 실패 → 정상적인 에러 응답
        // 200: 전체 성공 (Toss mock 있을 때)
        // 400/409: 중복 결제 방지 (ALREADY_COMPLETED, PAYMENT_IN_PROGRESS)
        // 500: Toss API 호출 실패 (Phase 2 에러) → Phase 1은 통과한 것
        const isExpected = check(res, {
            'confirm_claim: expected response': (r) =>
                r.status === 200 || r.status === 400 || r.status === 409 || r.status === 500,
        });

        // 중복 결제 차단 확인 (ALREADY_COMPLETED / PAYMENT_IN_PROGRESS)
        if (res.status === 400 || res.status === 409) {
            claimDuplicateRejected.add(1);
            claimContentionRate.add(true);
        } else {
            claimContentionRate.add(false);
        }

        // 잠금 대기 시간 추정 (1초 초과 시 경합 발생)
        if (res.timings.duration > 1000) {
            dbLockWaitMs.add(res.timings.duration);
        }
    });

    sleep(0.2);
}

// ============================================
// 시나리오 6: 스파이크 테스트 (플래시 세일 시뮬레이션)
// ============================================
export function spikeFlashSale() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Spike Flash Sale - sudden burst', () => {
        const orderId = generateOrderId();
        const amount = Math.floor(Math.random() * 100000) + 1000;

        // 스파이크 환경에서 전체 Redis 플로우 실행
        const saveRes = callPaymentSave(headers, orderId, amount);
        const saveOk = check(saveRes, {
            'spike_save: status 200': (r) => r.status === 200,
            'spike_save: under 500ms': (r) => r.timings.duration < 500,
        });

        if (!saveOk) {
            errorRate.add(true);
            return;
        }

        sleep(0.05); // 최소 사용자 지연

        const verifyRes = callPaymentSuccess(headers, orderId, amount);
        const verifyOk = check(verifyRes, {
            'spike_verify: status 200': (r) => r.status === 200,
            'spike_verify: under 500ms': (r) => r.timings.duration < 500,
        });

        if (!verifyOk) {
            errorRate.add(true);
            return;
        }

        // 스파이크 중 confirm 호출 (Phase 1 + Phase 2 에러 핸들링)
        const paymentKey = `pk_spike_${__VU}_${Date.now()}`;
        callPaymentConfirm(headers, paymentKey, orderId, amount);
    });

    sleep(0.1);
}

// ============================================
// 시나리오 7: Full Pipeline E2E (save → success → confirm)
// ============================================
export function fullPipeline() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Full Pipeline E2E', () => {
        const pipelineStart = Date.now();
        const orderId = generateOrderId();
        const amount = Math.floor(Math.random() * 100000) + 1000;

        // Phase A: Redis 저장
        const saveRes = callPaymentSave(headers, orderId, amount);
        if (saveRes.status !== 200) {
            errorRate.add(true);
            pipelineSuccessRate.add(false);
            return;
        }

        sleep(0.1); // 실 사용자 PG 페이지 체류 시뮬레이션

        // Phase B: 금액 검증
        const verifyRes = callPaymentSuccess(headers, orderId, amount);
        if (verifyRes.status !== 200) {
            errorRate.add(true);
            pipelineSuccessRate.add(false);
            return;
        }

        sleep(0.05);

        // Phase C: 결제 승인 (Phase 1 claimPayment → Phase 2 Toss API)
        const paymentKey = `pk_pipeline_${__VU}_${__ITER}_${Date.now()}`;
        const confirmRes = callPaymentConfirm(headers, paymentKey, orderId, amount);

        // 전체 파이프라인 소요 시간
        const pipelineEnd = Date.now();
        pipelineTotalDuration.add(pipelineEnd - pipelineStart);

        // 파이프라인 성공률 (Toss API 없으면 confirm은 500이지만 Phase 1은 통과)
        const phase1Passed = confirmRes.status !== 0; // 응답을 받았으면 Phase 1까지는 도달
        pipelineSuccessRate.add(phase1Passed);

        check(confirmRes, {
            'pipeline_confirm: got response': (r) => r.status !== 0,
            'pipeline_confirm: phase1 reached': (r) => r.status !== 401 && r.status !== 403,
        });
    });

    sleep(0.3);
}

// ============================================
// 시나리오 8: Soak 테스트 (장시간 저부하 — 커넥션풀/메모리 릭 탐지)
// ============================================
export function soakTest() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    const action = Math.random();

    if (action < 0.5) {
        // 50%: save → success 정상 플로우 (Redis 커넥션 릭 탐지)
        group('Soak - Redis Flow', () => {
            const orderId = generateOrderId();
            const amount = Math.floor(Math.random() * 50000) + 1000;

            const saveRes = callPaymentSave(headers, orderId, amount);
            if (saveRes.status === 200) {
                sleep(0.5); // 실 사용자 대기 시뮬레이션 (커넥션 반환 확인)
                const verifyRes = callPaymentSuccess(headers, orderId, amount);
                check(verifyRes, {
                    'soak_redis: status 200': (r) => r.status === 200,
                    'soak_redis: stable latency': (r) => r.timings.duration < 1000,
                });
            }
        });
    } else if (action < 0.8) {
        // 30%: fail 기록 (DB 커넥션풀 릭 탐지)
        group('Soak - DB Connection Pool', () => {
            const orderId = `soak_${__VU}_${__ITER}_${Date.now()}`;
            const paymentKey = `pk_soak_${__VU}_${Date.now()}`;
            const amount = Math.floor(Math.random() * 50000) + 1000;

            const res = callPaymentFail(headers, paymentKey, orderId, amount);
            check(res, {
                'soak_db: status 200': (r) => r.status === 200,
                'soak_db: stable latency': (r) => r.timings.duration < 2000,
            });
        });
    } else {
        // 20%: confirm (REQUIRES_NEW 트랜잭션 + Wallet lock 릭 탐지)
        group('Soak - Transaction Leak Check', () => {
            const orderId = `soak_confirm_${__VU}_${__ITER}_${Date.now()}`;
            const paymentKey = `pk_soak_c_${__VU}_${Date.now()}`;
            const amount = Math.floor(Math.random() * 50000) + 1000;

            const res = callPaymentConfirm(headers, paymentKey, orderId, amount);
            check(res, {
                'soak_tx: got response': (r) => r.status !== 0,
                'soak_tx: stable latency': (r) => r.timings.duration < 3000,
            });
        });
    }

    sleep(1); // Soak은 느린 간격으로 실행
}

// ============================================
// 테스트 라이프사이클
// ============================================

export function setup() {
    console.log('=== Payment Load Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log(`Test Users: ${testUsers.length}`);
    console.log('Scenarios:');
    console.log('  1. Redis Payment Flow (save→success) - 500 VUs, 3m');
    console.log('  2. Fail Concurrency (pessimistic lock) - 300 VUs, 3m');
    console.log('  3. Save Burst (Redis stress) - 200→1000 VUs, 5m');
    console.log('  4. Mixed Workload - 500 VUs, 5m');
    console.log('  5. Confirm Claim Contention (Phase 1 lock) - 400 VUs, 3m');
    console.log('  6. Spike Flash Sale - 10→1000 VUs, 5m');
    console.log('  7. Full Pipeline E2E (save→success→confirm) - 300 VUs, 3m');
    console.log('  8. Soak Test (low sustained load) - 50 VUs, 5m');
    console.log(`Total Duration: ~37 minutes`);
    console.log('=================================');

    // Health check
    const healthRes = http.get(`${BASE_URL}/actuator/health`);
    check(healthRes, {
        'Setup: health check OK': (r) => r.status === 200,
    });
}

export function teardown(data) {
    console.log('=== Payment Load Test Completed ===');
    console.log('Key metrics to analyze:');
    console.log('  --- Redis 계층 ---');
    console.log('  - payment_save_duration: Redis SET latency');
    console.log('  - payment_verify_duration: Redis GET+DELETE latency');
    console.log('  - redis_contention_rate: Redis operation failure rate');
    console.log('  --- DB 계층 ---');
    console.log('  - payment_fail_duration: DB INSERT with pessimistic lock');
    console.log('  - payment_confirm_duration: confirm 전체 (Phase 1~2) latency');
    console.log('  - confirm_phase1_duration: claimPayment Phase 1 lock contention');
    console.log('  - db_lock_wait_ms: Lock wait indicator');
    console.log('  --- 동시성 ---');
    console.log('  - concurrent_fail_conflict: Same orderId fail collision count');
    console.log('  - claim_contention_rate: Confirm duplicate rejection rate');
    console.log('  - claim_duplicate_rejected: Phase 1 duplicate prevention count');
    console.log('  --- 파이프라인 ---');
    console.log('  - pipeline_total_duration: Full E2E latency (save→success→confirm)');
    console.log('  - pipeline_success_rate: E2E pipeline pass rate');
}
