import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    vus: 2,
    duration: '30s',
    thresholds: {
        http_req_failed: ['rate<0.50'], // 스모크이므로 느슨한 기준
    },
};

function generateJWT(userId) {
    const now = Date.now();
    const header = { alg: 'HS512', typ: 'JWT' };
    const payload = {
        sub: userId.toString(),
        kakaoId: (10000000 + userId).toString(),
        nickname: `smokeuser${userId}`,
        status: 'ACTIVE',
        role: 'ROLE_USER',
        type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor(now / 1000) + 3600,
    };
    const h = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const p = encoding.b64encode(JSON.stringify(payload), 'rawurl');
    const sig = hmac('sha512', JWT_SECRET, `${h}.${p}`, 'base64rawurl');
    return `${h}.${p}.${sig}`;
}

export default function () {
    const userId = __VU;
    const token = generateJWT(userId);
    const headers = {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
        'X-Forwarded-For': `10.0.0.${userId}`,
    };

    // 1. Health check
    group('Health Check', () => {
        const res = http.get(`${BASE_URL}/actuator/health`);
        check(res, { 'health: 200': (r) => r.status === 200 });
    });

    // 2. Payment Save (Redis)
    const orderId = `smoke_${__VU}_${__ITER}_${Date.now()}`;
    const amount = 10000;

    group('Payment Save', () => {
        const res = http.post(`${BASE_URL}/api/v1/payments/save`,
            JSON.stringify({ orderId, amount }), { headers });
        check(res, {
            'save: status 200': (r) => r.status === 200,
            'save: under 1s': (r) => r.timings.duration < 1000,
        });
        console.log(`  save: ${res.status} (${res.timings.duration.toFixed(0)}ms)`);
    });

    sleep(0.1);

    // 3. Payment Success (Redis verify)
    group('Payment Verify', () => {
        const res = http.post(`${BASE_URL}/api/v1/payments/success`,
            JSON.stringify({ orderId, amount }), { headers });
        check(res, {
            'verify: status 200': (r) => r.status === 200,
            'verify: under 1s': (r) => r.timings.duration < 1000,
        });
        console.log(`  verify: ${res.status} (${res.timings.duration.toFixed(0)}ms)`);
    });

    sleep(0.1);

    // 4. Payment Fail (DB write)
    const failOrderId = `smoke_fail_${__VU}_${__ITER}_${Date.now()}`;
    group('Payment Fail', () => {
        const res = http.post(`${BASE_URL}/api/v1/payments/fail`,
            JSON.stringify({ paymentKey: `pk_smoke_${__VU}_${__ITER}_${Date.now()}`, orderId: failOrderId, amount: 5000 }),
            { headers });
        check(res, {
            'fail: got response': (r) => r.status !== 0,
            'fail: under 2s': (r) => r.timings.duration < 2000,
        });
        console.log(`  fail: ${res.status} (${res.timings.duration.toFixed(0)}ms)`);
    });

    sleep(0.1);

    // 5. Payment Confirm (Phase 1 claimPayment → Phase 2 Toss fail expected)
    const confirmOrderId = `smoke_confirm_${__VU}_${__ITER}_${Date.now()}`;
    group('Payment Confirm', () => {
        const res = http.post(`${BASE_URL}/api/v1/payments/confirm`,
            JSON.stringify({ paymentKey: `pk_smoke_c_${__VU}_${__ITER}_${Date.now()}`, orderId: confirmOrderId, amount: 10000 }),
            { headers });
        // Toss API 미설정 → Phase 2에서 에러 예상 (500 또는 400)
        check(res, {
            'confirm: got response': (r) => r.status !== 0,
            'confirm: under 3s': (r) => r.timings.duration < 3000,
        });
        console.log(`  confirm: ${res.status} (${res.timings.duration.toFixed(0)}ms) body=${res.body ? res.body.substring(0, 100) : 'empty'}`);
    });

    sleep(0.5);
}
