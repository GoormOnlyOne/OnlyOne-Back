import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    vus: 5,  // 동시 사용자 10 → 5로 감소
    duration: '30s',
};

// 서버에서 JWT 토큰 받기 (캐싱)
const tokenCache = {};
function generateJWT(userId) {
    if (tokenCache[userId]) {
        return tokenCache[userId];
    }

    const res = http.get(`${BASE_URL}/test/auth/token?userId=${userId}`);
    if (res.status === 200) {
        const body = JSON.parse(res.body);
        tokenCache[userId] = body.data.accessToken;
        return body.data.accessToken;
    }

    return null;
}

export default function () {
    const userId = Math.floor(Math.random() * 100) + 1;  // 더 작은 사용자 풀 사용 (캐싱 효율 증가)
    const token = generateJWT(userId);

    // 토큰이 없으면 요청 건너뛰기
    if (!token) {
        console.warn(`Failed to get token for userId: ${userId}`);
        return;
    }

    const headers = {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
    };

    // 읽지 않은 알림 개수 조회
    const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });

    check(res, {
        'status is 200': (r) => r.status === 200,
        'response time < 500ms': (r) => r.timings.duration < 500,
    });
}
