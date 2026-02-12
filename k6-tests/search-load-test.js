import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// Search 도메인 부하 테스트
// 대상 병목: Teammates O(n^2) EXISTS, ES MostFields, 3단계 폴백
// 총 소요시간: ~29분
// ============================================

// 커스텀 메트릭
const errorRate = new Rate('errors');
const teammatesSearchDuration = new Trend('teammates_search_duration');
const esSearchDuration = new Trend('es_search_duration');
const recommendationDuration = new Trend('recommendation_duration');
const interestFilterDuration = new Trend('interest_filter_duration');
const locationFilterDuration = new Trend('location_filter_duration');
const esTimeoutRate = new Rate('es_timeout_rate');
const teammatesSlowQueryRate = new Rate('teammates_slow_query_rate');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // 시나리오 1: Teammates O(n^2) 4-way self-join EXISTS
        teammates_query_stress: {
            executor: 'ramping-vus',
            exec: 'teammatesQueryStress',
            startVUs: 0,
            stages: [
                { duration: '2m', target: 200 },
                { duration: '3m', target: 500 },
                { duration: '2m', target: 300 },
                { duration: '1m', target: 0 },
            ],
            gracefulRampDown: '30s',
        },

        // 시나리오 2: ES 키워드 검색 (MostFields + 512MB 힙)
        es_keyword_search: {
            executor: 'constant-arrival-rate',
            exec: 'esKeywordSearch',
            rate: 200,
            timeUnit: '1s',
            duration: '5m',
            preAllocatedVUs: 50,
            maxVUs: 300,
            startTime: '9m',
            gracefulStop: '30s',
        },

        // 시나리오 3: 3단계 폴백 추천 (DB 왕복)
        recommendation_fallback: {
            executor: 'constant-vus',
            exec: 'recommendationFallback',
            vus: 200,
            duration: '5m',
            startTime: '15m',
            gracefulStop: '30s',
        },

        // 시나리오 4: ES+MySQL 동시 부하
        hybrid_search_mixed: {
            executor: 'constant-vus',
            exec: 'hybridSearchMixed',
            vus: 150,
            duration: '4m',
            startTime: '21m',
            gracefulStop: '30s',
        },

        // 시나리오 5: Interest/Location 필터
        mysql_filter_search: {
            executor: 'constant-vus',
            exec: 'mysqlFilterSearch',
            vus: 200,
            duration: '3m',
            startTime: '26m',
            gracefulStop: '30s',
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
        '주식', '부동산', '재테크', '코딩', '프로그래밍',
        '요가', '필라테스', '클라이밍', '서핑', '보드',
    ];
});

const categories = new SharedArray('search_categories', function () {
    return ['CULTURE', 'EXERCISE', 'TRAVEL', 'MUSIC', 'CRAFT', 'SOCIAL', 'LANGUAGE', 'FINANCE'];
});

const cities = new SharedArray('search_cities', function () {
    return ['서울', '부산', '대구', '인천', '광주', '대전', '울산', '세종'];
});

// ============================================
// 유틸리티 함수
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
    return {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
    };
}

function getRandomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

function getRandomKeyword() {
    return searchKeywords[Math.floor(Math.random() * searchKeywords.length)];
}

function getRandomCategory() {
    return categories[Math.floor(Math.random() * categories.length)];
}

function getRandomCity() {
    return cities[Math.floor(Math.random() * cities.length)];
}

// ============================================
// 시나리오 1: Teammates O(n^2) 4-way self-join EXISTS
// ============================================
export function teammatesQueryStress() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Teammates Query Stress', () => {
        const res = http.get(
            `${BASE_URL}/search/teammates-clubs`,
            { headers }
        );

        check(res, {
            'teammates search: status 200': (r) => r.status === 200,
        });

        teammatesSearchDuration.add(res.timings.duration);

        if (res.timings.duration > 1500) {
            teammatesSlowQueryRate.add(1);
            if (__ITER % 20 === 0) {
                console.warn(`[Teammates Slow] userId=${user.userId}, duration=${res.timings.duration.toFixed(0)}ms`);
            }
        } else {
            teammatesSlowQueryRate.add(0);
        }
    });

    sleep(0.5);
}

// ============================================
// 시나리오 2: ES 키워드 검색 (MostFields + 512MB 힙)
// ============================================
export function esKeywordSearch() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const keyword = getRandomKeyword();

    const res = http.get(
        `${BASE_URL}/search?keyword=${encodeURIComponent(keyword)}&page=0&size=20`,
        { headers }
    );

    check(res, {
        'ES search: status 200': (r) => r.status === 200,
    });

    esSearchDuration.add(res.timings.duration);

    if (res.timings.duration > 3000 || res.status === 504 || res.status === 408) {
        esTimeoutRate.add(1);
        if (__ITER % 50 === 0) {
            console.warn(`[ES Timeout] keyword="${keyword}", duration=${res.timings.duration.toFixed(0)}ms, status=${res.status}`);
        }
    } else {
        esTimeoutRate.add(0);
    }

    errorRate.add(res.status !== 200);
}

// ============================================
// 시나리오 3: 3단계 폴백 추천
// ============================================
export function recommendationFallback() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Recommendation Fallback', () => {
        const res = http.get(
            `${BASE_URL}/search/recommendations`,
            { headers }
        );

        check(res, {
            'recommendations: status 200': (r) => r.status === 200,
        });

        recommendationDuration.add(res.timings.duration);

        if (res.timings.duration > 500 && __ITER % 30 === 0) {
            console.warn(`[Recommendation Slow] userId=${user.userId}, duration=${res.timings.duration.toFixed(0)}ms`);
        }
    });

    sleep(1);
}

// ============================================
// 시나리오 4: ES + MySQL 동시 부하
// ============================================
export function hybridSearchMixed() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Hybrid Search Mixed', () => {
        const action = Math.random();

        if (action < 0.35) {
            // 35%: ES 키워드 검색
            const keyword = getRandomKeyword();
            const res = http.get(
                `${BASE_URL}/search?keyword=${encodeURIComponent(keyword)}&page=0&size=20`,
                { headers }
            );
            check(res, { 'hybrid ES: status 200': (r) => r.status === 200 });
            esSearchDuration.add(res.timings.duration);
        } else if (action < 0.6) {
            // 25%: Teammates
            const res = http.get(
                `${BASE_URL}/search/teammates-clubs`,
                { headers }
            );
            check(res, { 'hybrid teammates: status 200': (r) => r.status === 200 });
            teammatesSearchDuration.add(res.timings.duration);
        } else if (action < 0.8) {
            // 20%: 추천
            const res = http.get(
                `${BASE_URL}/search/recommendations`,
                { headers }
            );
            check(res, { 'hybrid recommendations: status 200': (r) => r.status === 200 });
            recommendationDuration.add(res.timings.duration);
        } else {
            // 20%: Interest 필터
            const category = getRandomCategory();
            const res = http.get(
                `${BASE_URL}/search/interests?category=${category}&page=0&size=20`,
                { headers }
            );
            check(res, { 'hybrid interest: status 200': (r) => r.status === 200 });
            interestFilterDuration.add(res.timings.duration);
        }
    });

    sleep(0.5);
}

// ============================================
// 시나리오 5: Interest/Location MySQL 필터
// ============================================
export function mysqlFilterSearch() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('MySQL Filter Search', () => {
        const action = Math.random();

        if (action < 0.5) {
            // 50%: Interest 필터
            const category = getRandomCategory();
            const res = http.get(
                `${BASE_URL}/search/interests?category=${category}&page=0&size=20`,
                { headers }
            );
            check(res, {
                'interest filter: status 200': (r) => r.status === 200,
            });
            interestFilterDuration.add(res.timings.duration);
        } else {
            // 50%: Location 필터
            const city = getRandomCity();
            const res = http.get(
                `${BASE_URL}/search/locations?city=${encodeURIComponent(city)}&page=0&size=20`,
                { headers }
            );
            check(res, {
                'location filter: status 200': (r) => r.status === 200,
            });
            locationFilterDuration.add(res.timings.duration);
        }
    });

    sleep(0.5);
}

// ============================================
// 테스트 라이프사이클
// ============================================
export function setup() {
    console.log('=== Search Domain Load Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log(`Test Users: ${testUsers.length}`);
    console.log('');
    console.log('Testing 5 scenarios:');
    console.log('1. Teammates Query Stress (0-8m, 0->500 VU)');
    console.log('2. ES Keyword Search (9-14m, 200/s)');
    console.log('3. Recommendation Fallback (15-20m, 200 VU)');
    console.log('4. Hybrid Search Mixed (21-25m, 150 VU)');
    console.log('5. MySQL Filter Search (26-29m, 200 VU)');
    console.log('');
    console.log('Total Duration: ~29 minutes');
    console.log('=======================================');
}

export function teardown(data) {
    console.log('');
    console.log('=== Search Domain Load Test Completed ===');
    console.log('Review metrics: teammates_search_duration, es_search_duration,');
    console.log('es_timeout_rate, recommendation_duration');
    console.log('==========================================');
}
