import http from 'k6/http';
import { check, sleep } from 'k6';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key';

export const options = {
    vus: 3,
    duration: '20s',
    thresholds: {
        http_req_duration: ['p(95)<2000'],
        http_req_failed: ['rate<0.5'],
    },
};

function generateJWT(userId) {
    const now = Date.now();
    const header = { alg: 'HS512', typ: 'JWT' };
    const payload = {
        sub: userId.toString(),
        kakaoId: (10000000 + userId).toString(),
        nickname: `testuser${userId}`,
        status: 'ACTIVE',
        role: 'ROLE_USER',
        type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor((now + 3600000) / 1000),
    };
    const h = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const p = encoding.b64encode(JSON.stringify(payload), 'rawurl');
    const sig = hmac('sha512', JWT_SECRET, `${h}.${p}`, 'base64rawurl');
    return `${h}.${p}.${sig}`;
}

export default function () {
    const userId = (__VU % 10) + 1;
    const token = generateJWT(userId);
    const headers = {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
    };

    // 1. Health check
    const r1 = http.get(`${BASE_URL}/actuator/health`);
    check(r1, { 'health: 200': (r) => r.status === 200 });

    // 2. Notification list
    const r2 = http.get(`${BASE_URL}/api/v1/notifications?size=10`, { headers });
    check(r2, { 'notifications: status ok': (r) => r.status === 200 || r.status === 401 });

    // 3. Unread count
    const r3 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
    check(r3, { 'unread-count: status ok': (r) => r.status === 200 || r.status === 401 });

    console.log(`VU${__VU} iter${__ITER}: health=${r1.status}, notif=${r2.status}, unread=${r3.status}`);
    sleep(1);
}
