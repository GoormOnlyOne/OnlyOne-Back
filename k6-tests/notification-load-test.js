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
const notificationListDuration = new Trend('notification_list_duration');
const unreadCountDuration = new Trend('unread_count_duration');
const markAsReadDuration = new Trend('mark_as_read_duration');
const markAllAsReadDuration = new Trend('mark_all_as_read_duration');
const deleteNotificationDuration = new Trend('delete_notification_duration');


const cacheHits = new Counter('cache_hits');
const cacheMisses = new Counter('cache_misses');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

// 테스트 시나리오별 옵션
export const options = {
    scenarios: {
        // 시나리오 1: 일반적인 읽기 중심 워크로드 (70% 읽기, 30% 쓰기)
        normal_workload: {
            executor: 'ramping-vus',
            exec: 'normalWorkload',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 100 },   // 1분 동안 100명까지 증가
                { duration: '3m', target: 100 },   // 3분 동안 100명 유지
                { duration: '1m', target: 0 },     // 1분 동안 0명까지 감소
            ],
            gracefulRampDown: '30s',
        },

        // 시나리오 2: 스파이크 테스트 (갑작스런 트래픽 증가)
        spike_test: {
            executor: 'ramping-vus',
            exec: 'spikeWorkload',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 50 },   // 정상 로드
                { duration: '10s', target: 500 },  // 갑작스런 스파이크
                { duration: '1m', target: 500 },   // 스파이크 유지
                { duration: '30s', target: 50 },   // 정상으로 복귀
                { duration: '30s', target: 0 },
            ],
            startTime: '6m',  // normal_workload 이후 실행
            gracefulRampDown: '30s',
        },

        // 시나리오 3: 스트레스 테스트 (점진적 부하 증가)
        stress_test: {
            executor: 'ramping-vus',
            exec: 'stressWorkload',
            startVUs: 0,
            stages: [
                { duration: '2m', target: 200 },
                { duration: '3m', target: 400 },
                { duration: '2m', target: 600 },
                { duration: '2m', target: 800 },   // 시스템 한계 테스트
                { duration: '2m', target: 400 },
                { duration: '1m', target: 0 },
            ],
            startTime: '10m',  // spike_test 이후 실행
            gracefulRampDown: '30s',
        },

        // 시나리오 4: 소크 테스트 (장시간 지속 부하)
        soak_test: {
            executor: 'constant-vus',
            exec: 'soakWorkload',
            vus: 200,
            duration: '10m',
            startTime: '23m',  // stress_test 이후 실행
            gracefulStop: '30s',
        },

        // 시나리오 5: 읽기 전용 워크로드 (캐시 효율성 테스트)
        read_only_workload: {
            executor: 'constant-vus',
            exec: 'readOnlyWorkload',
            vus: 300,
            duration: '3m',
            startTime: '34m',  // soak_test 이후 실행
            gracefulStop: '30s',
        },

        // 시나리오 6: 쓰기 중심 워크로드 (DB 부하 테스트)
        write_heavy_workload: {
            executor: 'constant-vus',
            exec: 'writeHeavyWorkload',
            vus: 100,
            duration: '3m',
            startTime: '38m',  // read_only_workload 이후 실행
            gracefulStop: '30s',
        },
    },

    thresholds: {
        http_req_duration: ['p(95)<500', 'p(99)<1000'],  // 95%는 500ms 이하, 99%는 1초 이하
        http_req_failed: ['rate<0.01'],                   // 에러율 1% 미만
        errors: ['rate<0.05'],                            // 비즈니스 에러율 5% 미만
        notification_list_duration: ['p(95)<300'],
        unread_count_duration: ['p(95)<100'],             // 캐시 활용으로 매우 빠름
        mark_as_read_duration: ['p(95)<200'],
    },
};

// ============================================
// 테스트 사용자 데이터 (SharedArray로 VU간 공유)
// ============================================
const testUsers = new SharedArray('test_users', function () {
    const users = [];
    for (let i = 1; i <= 1000; i++) {
        users.push({
            userId: i,
            kakaoId: 10000000 + i,
            status: i % 100 === 0 ? 'INACTIVE' : (i % 50 === 0 ? 'GUEST' : 'ACTIVE'),
            role: i % 1000 === 0 ? 'ROLE_ADMIN' : 'ROLE_USER',
        });
    }
    return users;
});

// ============================================
// 유틸리티 함수
// ============================================

// JWT 토큰 생성 (k6 테스트용 - 직접 생성)
function generateJWT(user) {
    const now = Date.now();
    const expiryDate = now + (3600 * 1000);  // 1시간 후

    // JWT Header
    const header = {
        alg: 'HS512',
        typ: 'JWT'
    };

    // JWT Payload
    const payload = {
        sub: user.userId.toString(),
        kakaoId: user.kakaoId.toString(),
        nickname: `testuser${user.userId}`,
        status: user.status,
        role: user.role,
        type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor(expiryDate / 1000)
    };

    // Base64 URL Encoding
    const headerEncoded = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const payloadEncoded = encoding.b64encode(JSON.stringify(payload), 'rawurl');

    // Signature
    const signatureInput = `${headerEncoded}.${payloadEncoded}`;
    const signature = hmac('sha512', JWT_SECRET, signatureInput, 'base64rawurl');

    // JWT Token
    return `${signatureInput}.${signature}`;
}

// API 요청 헤더
function getHeaders(token) {
    return {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
    };
}

// 랜덤 사용자 선택
function getRandomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

// 응답 검증
function validateResponse(response, expectedStatus, metricName) {
    const success = check(response, {
        [`${metricName}: status is ${expectedStatus}`]: (r) => r.status === expectedStatus,
        [`${metricName}: response time < 1s`]: (r) => r.timings.duration < 1000,
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
// 시나리오 1: 일반적인 워크로드 (70% 읽기, 30% 쓰기)
// ============================================
export function normalWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    group('Normal Workload - Read Operations (70%)', () => {
        // 1. 읽지 않은 알림 개수 조회 (캐시 히트 예상)
        if (Math.random() < 0.7) {
            const res1 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
            const success = validateResponse(res1, 200, 'unread_count');
            unreadCountDuration.add(res1.timings.duration);

            if (success) {
                const body = JSON.parse(res1.body);
                if (body.cached) cacheHits.add(1);
                else cacheMisses.add(1);
            }
            sleep(0.5);

            // 2. 알림 목록 조회
            const res2 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, { headers });
            validateResponse(res2, 200, 'notification_list');
            notificationListDuration.add(res2.timings.duration);
            sleep(1);

            // 3. 커서 페이징 (다음 페이지)
            if (res2.status === 200) {
                const body = JSON.parse(res2.body);
                if (body.data && body.data.cursor) {
                    const res3 = http.get(
                        `${BASE_URL}/api/v1/notifications?cursor=${body.data.cursor}&size=20`,
                        { headers }
                    );
                    validateResponse(res3, 200, 'notification_list_page2');
                    notificationListDuration.add(res3.timings.duration);
                }
            }
            sleep(1);
        }
    });

    group('Normal Workload - Write Operations (30%)', () => {
        if (Math.random() < 0.3) {
            // 4. 알림 읽음 처리 (랜덤 ID)
            const notificationId = Math.floor(Math.random() * 10000000) + 1;
            const res4 = http.put(
                `${BASE_URL}/api/v1/notifications/${notificationId}/read`,
                null,
                { headers }
            );
            // 404도 정상 (해당 알림이 없을 수 있음)
            check(res4, {
                'mark_as_read: status is 200 or 404': (r) => r.status === 200 || r.status === 404,
            });
            markAsReadDuration.add(res4.timings.duration);
            sleep(0.5);

            // 5. 알림 삭제
            if (Math.random() < 0.05) {  // 5% 확률
                const deleteId = Math.floor(Math.random() * 10000000) + 1;
                const res6 = http.del(`${BASE_URL}/api/v1/notifications/${deleteId}`, null, { headers });
                check(res6, {
                    'delete_notification: status is 200 or 404': (r) => r.status === 200 || r.status === 404,
                });
                deleteNotificationDuration.add(res6.timings.duration);
            }
        }
    });

    sleep(1);
}

// ============================================
// 시나리오 2: 스파이크 워크로드 (갑작스런 트래픽)
// ============================================
export function spikeWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    // 스파이크 시 주로 읽기 작업 (알림 확인)
    const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
    validateResponse(res, 200, 'spike_unread_count');
    unreadCountDuration.add(res.timings.duration);

    const res2 = http.get(`${BASE_URL}/api/v1/notifications?size=10`, { headers });
    validateResponse(res2, 200, 'spike_notification_list');
    notificationListDuration.add(res2.timings.duration);

    sleep(0.2);  // 짧은 대기 시간
}

// ============================================
// 시나리오 3: 스트레스 워크로드 (점진적 부하 증가)
// ============================================
export function stressWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    // 모든 API를 골고루 호출
    const res1 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
    unreadCountDuration.add(res1.timings.duration);

    const res2 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, { headers });
    notificationListDuration.add(res2.timings.duration);

    const notificationId = Math.floor(Math.random() * 10000000) + 1;
    const res3 = http.put(`${BASE_URL}/api/v1/notifications/${notificationId}/read`, null, { headers });
    markAsReadDuration.add(res3.timings.duration);

    // 스트레스 상황에서 모든 읽음 처리 (무거운 작업)
    if (Math.random() < 0.05) {  // 5% 확률
        const res4 = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, { headers });
        markAllAsReadDuration.add(res4.timings.duration);
    }

    sleep(0.5);
}

// ============================================
// 시나리오 4: 소크 워크로드 (장시간 지속)
// ============================================
export function soakWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    // 실제 사용자 행동 패턴 모방
    group('Soak - User checks notifications', () => {
        // 1. 개수 확인
        const res1 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
        unreadCountDuration.add(res1.timings.duration);
        sleep(1);

        // 2. 목록 조회
        const res2 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, { headers });
        notificationListDuration.add(res2.timings.duration);
        sleep(2);

        // 3. 하나씩 읽음 (2-3개)
        const readCount = Math.floor(Math.random() * 3) + 2;
        for (let i = 0; i < readCount; i++) {
            const notificationId = Math.floor(Math.random() * 10000000) + 1;
            const res = http.put(
                `${BASE_URL}/api/v1/notifications/${notificationId}/read`,
                null,
                { headers }
            );
            markAsReadDuration.add(res.timings.duration);
            sleep(1);
        }
    });

    sleep(5);  // 다음 세션까지 대기
}

// ============================================
// 시나리오 5: 읽기 전용 워크로드 (캐시 효율성)
// ============================================
export function readOnlyWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    // 동일한 사용자가 반복적으로 조회 (캐시 히트율 측정)
    for (let i = 0; i < 5; i++) {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers });
        validateResponse(res, 200, 'cache_test_unread_count');
        unreadCountDuration.add(res.timings.duration);

        if (res.status === 200) {
            const body = JSON.parse(res.body);
            if (body.cached) cacheHits.add(1);
            else cacheMisses.add(1);
        }

        sleep(0.2);
    }

    const res2 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, { headers });
    notificationListDuration.add(res2.timings.duration);

    sleep(1);
}

// ============================================
// 시나리오 6: 쓰기 중심 워크로드 (DB 부하)
// ============================================
export function writeHeavyWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);

    // 읽음 처리 반복 (캐시 무효화 트리거)
    for (let i = 0; i < 5; i++) {
        const notificationId = Math.floor(Math.random() * 10000000) + 1;
        const res = http.put(
            `${BASE_URL}/api/v1/notifications/${notificationId}/read`,
            null,
            { headers }
        );
        markAsReadDuration.add(res.timings.duration);
        sleep(0.3);
    }

    // 모든 읽음 처리
    if (Math.random() < 0.2) {  // 20% 확률
        const res = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, { headers });
        markAllAsReadDuration.add(res.timings.duration);
    }

    // 삭제
    if (Math.random() < 0.1) {  // 10% 확률
        const notificationId = Math.floor(Math.random() * 10000000) + 1;
        const res = http.del(`${BASE_URL}/api/v1/notifications/${notificationId}`, null, { headers });
        deleteNotificationDuration.add(res.timings.duration);
    }

    sleep(1);
}

// ============================================
// 테스트 라이프사이클
// ============================================

export function setup() {
    console.log('=== Notification Load Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log(`Test Users: ${testUsers.length}`);
    console.log(`Total Duration: ~42 minutes`);
    console.log('========================================');
}

export function teardown(data) {
    console.log('=== Notification Load Test Completed ===');
}
