import http from 'k6/http';
import { check } from 'k6';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key';

export const options = { vus: 1, iterations: 1 };

function generateJWT(userId) {
    const kakaoId = 10000000 + userId;
    const now = Date.now();
    const header = { alg: 'HS512', typ: 'JWT' };
    const payload = {
        sub: userId.toString(),
        kakaoId: kakaoId.toString(),
        nickname: 'testuser' + userId,
        status: 'ACTIVE',
        role: 'USER',
        type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor((now + 3600000) / 1000),
    };
    const h = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const p = encoding.b64encode(JSON.stringify(payload), 'rawurl');
    const sig = hmac('sha512', JWT_SECRET, h + '.' + p, 'base64rawurl');
    return h + '.' + p + '.' + sig;
}

export default function () {
    const token = generateJWT(1);
    console.log('JWT token (first 80): ' + token.substring(0, 80) + '...');

    // Decode header and payload for debugging
    const parts = token.split('.');
    console.log('Header: ' + encoding.b64decode(parts[0], 'rawurl', 's'));
    console.log('Payload: ' + encoding.b64decode(parts[1], 'rawurl', 's'));

    const hdrs = { 'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json' };

    // Test health first (no auth needed)
    const h = http.get(BASE_URL + '/actuator/health');
    console.log('Health: ' + h.status);

    // Test chat message list
    const res = http.get(BASE_URL + '/api/v1/chat/1/messages?size=3', { headers: hdrs });
    console.log('Chat GET status: ' + res.status + ' body: ' + res.body.substring(0, 300));
    check(res, { 'chat GET 200': (r) => r.status === 200 });

    // Test notification list for comparison
    const res2 = http.get(BASE_URL + '/api/v1/notifications?size=3', { headers: hdrs });
    console.log('Notif GET status: ' + res2.status + ' body: ' + (res2.body || '').substring(0, 300));
    check(res2, { 'notif GET 200': (r) => r.status === 200 });
}
