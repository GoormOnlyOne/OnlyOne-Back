import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// Search 도메인 Quick 부하 테스트 (~7분)
// 원본 29분 테스트의 압축 버전
// ============================================

const errorRate = new Rate('errors');
const teammatesSearchDuration = new Trend('teammates_search_duration');
const esSearchDuration = new Trend('es_search_duration');
const recommendationDuration = new Trend('recommendation_duration');
const interestFilterDuration = new Trend('interest_filter_duration');
const locationFilterDuration = new Trend('location_filter_duration');
const esTimeoutRate = new Rate('es_timeout_rate');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // Scenario 1: Teammates O(n^2) (0-2m)
        teammates_stress: {
            executor: 'ramping-vus',
            exec: 'teammatesQuery',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 100 },
                { duration: '1m', target: 200 },
                { duration: '30s', target: 0 },
            ],
            gracefulRampDown: '10s',
        },

        // Scenario 2: ES keyword search (2m-4m)
        es_keyword: {
            executor: 'constant-arrival-rate',
            exec: 'esKeywordSearch',
            rate: 150,
            timeUnit: '1s',
            duration: '2m',
            preAllocatedVUs: 30,
            maxVUs: 200,
            startTime: '2m10s',
            gracefulStop: '10s',
        },

        // Scenario 3: Mixed (all endpoints) (4m-6m)
        mixed_load: {
            executor: 'constant-vus',
            exec: 'mixedSearch',
            vus: 150,
            duration: '2m',
            startTime: '4m20s',
            gracefulStop: '10s',
        },
    },

    thresholds: {
        teammates_search_duration: ['p(95)<1500'],
        es_search_duration: ['p(95)<300'],
        es_timeout_rate: ['rate<0.05'],
        recommendation_duration: ['p(95)<500'],
        http_req_duration: ['p(95)<1500'],
        http_req_failed: ['rate<0.05'],
        errors: ['rate<0.05'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('search_test_users', function () {
    const users = [];
    for (let i = 1; i <= 1000; i++) {
        users.push({
            userId: i,
            kakaoId: 10000000 + i,
            status: 'ACTIVE',
            role: 'ROLE_USER',
        });
    }
    return users;
});

const searchKeywords = new SharedArray('search_keywords', function () {
    return [
        '운동', '등산', '러닝', '축구', '농구', '배드민턴', '테니스', '수영',
        '독서', '영화', '음악', '기타', '피아노', '드럼', '노래',
        '여행', '캠핑', '맛집', '카페', '사진',
        '공예', '그림', '뜨개질', '요리', '베이킹',
        '영어', '일본어', '중국어', '스페인어',
        '요가', '필라테스', '클라이밍', '서핑', '보드',
        '테스트', '클럽', '활동', '모임', '취미',
    ];
});

const cities = new SharedArray('search_cities', function () {
    return ['서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종'];
});

// ============================================
// 유틸리티
// ============================================
function generateJWT(user) {
    const now = Date.now();
    const expiryDate = now + (3600 * 1000);
    const header = { alg: 'HS512', typ: 'JWT' };
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
    return { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' };
}

function getRandomUser() { return testUsers[Math.floor(Math.random() * testUsers.length)]; }
function getRandomKeyword() { return searchKeywords[Math.floor(Math.random() * searchKeywords.length)]; }
function getRandomCity() { return cities[Math.floor(Math.random() * cities.length)]; }

// ============================================
// Scenario 1: Teammates O(n^2)
// ============================================
export function teammatesQuery() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    const res = http.get(`${BASE_URL}/api/v1/search/teammates-clubs`, { headers });
    check(res, { 'teammates: 200': (r) => r.status === 200 });
    teammatesSearchDuration.add(res.timings.duration);
    errorRate.add(res.status !== 200);
    sleep(0.3);
}

// ============================================
// Scenario 2: ES keyword search
// ============================================
export function esKeywordSearch() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const keyword = getRandomKeyword();

    const res = http.get(
        `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}&page=0&size=20`,
        { headers }
    );
    check(res, { 'ES search: 200': (r) => r.status === 200 });
    esSearchDuration.add(res.timings.duration);
    if (res.timings.duration > 3000 || res.status === 504) { esTimeoutRate.add(1); } else { esTimeoutRate.add(0); }
    errorRate.add(res.status !== 200);
}

// ============================================
// Scenario 3: Mixed (all endpoints)
// ============================================
export function mixedSearch() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    const action = Math.random();

    if (action < 0.30) {
        // 30%: ES keyword
        const keyword = getRandomKeyword();
        const res = http.get(
            `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}&page=0&size=20`,
            { headers }
        );
        check(res, { 'mixed ES: 200': (r) => r.status === 200 });
        esSearchDuration.add(res.timings.duration);
        errorRate.add(res.status !== 200);
    } else if (action < 0.50) {
        // 20%: Teammates
        const res = http.get(`${BASE_URL}/api/v1/search/teammates-clubs`, { headers });
        check(res, { 'mixed teammates: 200': (r) => r.status === 200 });
        teammatesSearchDuration.add(res.timings.duration);
        errorRate.add(res.status !== 200);
    } else if (action < 0.70) {
        // 20%: Recommendations
        const res = http.get(`${BASE_URL}/api/v1/search/recommendations`, { headers });
        check(res, { 'mixed recommendations: 200': (r) => r.status === 200 });
        recommendationDuration.add(res.timings.duration);
        errorRate.add(res.status !== 200);
    } else if (action < 0.85) {
        // 15%: ES keyword + location filter
        const keyword = getRandomKeyword();
        const city = getRandomCity();
        const res = http.get(
            `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}&city=${encodeURIComponent(city)}&district=${encodeURIComponent('강남구')}&page=0&size=20`,
            { headers }
        );
        check(res, { 'mixed ES+filter: 200': (r) => r.status === 200 });
        esSearchDuration.add(res.timings.duration);
        errorRate.add(res.status !== 200);
    } else {
        // 15%: ES keyword + interest filter
        const keyword = getRandomKeyword();
        const interestId = Math.floor(Math.random() * 8) + 1;
        const res = http.get(
            `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}&interestId=${interestId}&page=0&size=20`,
            { headers }
        );
        check(res, { 'mixed ES+interest: 200': (r) => r.status === 200 });
        esSearchDuration.add(res.timings.duration);
        errorRate.add(res.status !== 200);
    }

    sleep(0.3);
}

// ============================================
// 라이프사이클
// ============================================
export function setup() {
    console.log('=== Search Quick Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log('Scenarios:');
    console.log('1. Teammates (0-2m, 0->200 VU)');
    console.log('2. ES Keyword (2m-4m, 150/s)');
    console.log('3. Mixed (4m-6m, 150 VU)');
    console.log('Total: ~7 minutes');
    console.log('================================');
}

export function teardown() {
    console.log('=== Search Quick Test Completed ===');
}
