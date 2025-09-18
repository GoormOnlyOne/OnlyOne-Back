import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ===== ENV =====
const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const AUTH     = (__ENV.AUTH_TOKEN && __ENV.AUTH_TOKEN.trim() !== '') ? `Bearer ${__ENV.AUTH_TOKEN}` : null;
const CLUB_ID  = __ENV.CLUB_ID || '21018';
const FEED_ID  = __ENV.FEED_ID || '1';
const TEST_TYPE = (__ENV.TEST_TYPE || 'smoke').toLowerCase();

// ===== Metrics =====
const httpFailures = new Rate('http_req_failed_feed_like');
const likeLatency  = new Trend('feed_like_toggle_latency', true);

const TOKENS = [
    "ACCESS_TOKEN_USER1",
    "ACCESS_TOKEN_USER2",
    "ACCESS_TOKEN_USER3",
];

// ===== Options =====
const profiles = {
    smoke: {
        vus: 5, duration: '20s',
        thresholds: {
            http_req_failed: ['rate<0.01'],
            http_req_duration: ['p(95)<500'],
            feed_like_toggle_latency: ['p(95)<300'],
        },
    },
    load: {
        scenarios: {
            steady: {
                executor: 'ramping-vus',
                startVUs: 0,
                stages: [
                    { duration: '1m', target: 100 },   // 1분간 100명까지 올림
                    { duration: '2m', target: 100 },   // 2분간 유지
                    { duration: '30s', target: 0 },    // 내려가기
                ],
                gracefulRampDown: '20s',
            },
        },
        thresholds: {
            http_req_failed: ['rate<0.01'],
            http_req_duration: ['p(95)<600'],
            feed_like_toggle_latency: ['p(95)<400'],
        },
    },
};

export const options = profiles[TEST_TYPE] || profiles.smoke;

// ===== Test =====
export default function () {
    const url = `${BASE_URL}/clubs/${CLUB_ID}/feeds/${FEED_ID}/likes`;
    const params = {
        headers: {
            'Accept': 'application/json',
            ...(AUTH ? { 'Authorization': AUTH } : {}),
        },
        tags: { endpoint: 'feed_like_toggle', clubId: CLUB_ID, feedId: FEED_ID },
        timeout: '10s',
    };

    const res = http.put(url, null, params);
    httpFailures.add(res.status >= 400);
    likeLatency.add(res.timings.duration);

    check(res, {
        'status is 200 or 204': r => r.status === 200 || r.status === 204,
    });

    sleep(0.3); // 사용자가 요청 후 잠깐 쉬는 것처럼
}
