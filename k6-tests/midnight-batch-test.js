import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// 자정 배치 임팩트 테스트
// @Scheduled(cron="0 0 0 * * *") updateScheduleStatus() 실행 시
// 동시 API 응답시간 영향 측정
// 패턴: 배경 부하 200VU 유지 → 5분 시점에 배치 트리거 → 응답시간 스파이크 측정
// 총 소요시간: ~15분
// ============================================

// 커스텀 메트릭
const errorRate = new Rate('errors');
const apiDurationPreBatch = new Trend('api_duration_pre_batch');
const apiDurationDuringBatch = new Trend('api_duration_during_batch');
const apiDurationPostBatch = new Trend('api_duration_post_batch');
const batchImpactSpike = new Gauge('batch_impact_spike_ms');
const scheduleListDuration = new Trend('schedule_list_duration');
const feedListDuration = new Trend('feed_list_duration');
const notificationDuration = new Trend('notification_duration');
const spikeDetected = new Rate('spike_detected');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

// 배치 트리거 시점 (5분 시점)
const BATCH_TRIGGER_TIME_SEC = 5 * 60;

export const options = {
    scenarios: {
        // 배경 부하: 일관된 200VU 유지 (전체 15분)
        background_load: {
            executor: 'constant-vus',
            exec: 'backgroundLoad',
            vus: 200,
            duration: '15m',
            gracefulStop: '30s',
        },

        // 배치 트리거 시뮬레이션 (5분 시점에 실행)
        batch_trigger: {
            executor: 'shared-iterations',
            exec: 'triggerBatch',
            vus: 1,
            iterations: 1,
            startTime: '5m',
            gracefulStop: '30s',
        },

        // 배치 중 추가 부하 (스파이크 감지 강화)
        batch_monitor: {
            executor: 'constant-vus',
            exec: 'batchMonitor',
            vus: 50,
            duration: '5m',
            startTime: '5m',
            gracefulStop: '30s',
        },
    },

    thresholds: {
        api_duration_pre_batch: ['p(95)<400'],
        api_duration_during_batch: ['p(95)<2000'],  // 배치 중 허용 여유
        api_duration_post_batch: ['p(95)<500'],
        spike_detected: ['rate<0.3'],  // 30% 미만 스파이크
        http_req_duration: ['p(95)<2000'],
        http_req_failed: ['rate<0.05'],
        errors: ['rate<0.05'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('midnight_test_users', function () {
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

// 테스트 시작 시간 (글로벌)
let testStartTime = 0;

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

// 현재 테스트 경과 시간(초)을 기반으로 배치 phase 판단
function getCurrentPhase() {
    // __ITER 기반 추정 (정확한 시간은 없으므로)
    // background_load: 0-15m
    // batch_trigger: 5m 시점
    // → 대략 __ITER * sleep_time으로 추정
    // 각 iteration은 ~2s이므로, 5분 = 150 iterations
    if (__ITER < 120) return 'pre_batch';
    if (__ITER < 270) return 'during_batch';
    return 'post_batch';
}

// ============================================
// 배경 부하: 다양한 API 호출 (전체 15분)
// ============================================
export function backgroundLoad() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const phase = getCurrentPhase();
    const clubId = ((user.userId - 1) % 10000) + 1;

    group('Background Load', () => {
        const action = Math.random();
        let duration = 0;

        if (action < 0.3) {
            // 30%: 일정 목록
            const res = http.get(
                `${BASE_URL}/api/v1/clubs/${clubId}/schedules`,
                { headers }
            );
            check(res, { 'bg schedule list: 200': (r) => r.status === 200 });
            duration = res.timings.duration;
            scheduleListDuration.add(duration);
        } else if (action < 0.6) {
            // 30%: 피드 목록
            const res = http.get(
                `${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&size=20`,
                { headers }
            );
            check(res, { 'bg feed list: 200': (r) => r.status === 200 });
            duration = res.timings.duration;
            feedListDuration.add(duration);
        } else if (action < 0.8) {
            // 20%: 알림
            const res = http.get(
                `${BASE_URL}/api/v1/notifications?size=20`,
                { headers }
            );
            check(res, { 'bg notifications: 200': (r) => r.status === 200 });
            duration = res.timings.duration;
            notificationDuration.add(duration);
        } else {
            // 20%: 인기 피드
            const res = http.get(
                `${BASE_URL}/api/v1/feeds/popular?page=0&size=20`,
                { headers }
            );
            check(res, { 'bg popular: 200': (r) => r.status === 200 });
            duration = res.timings.duration;
        }

        // Phase별 메트릭 기록
        if (phase === 'pre_batch') {
            apiDurationPreBatch.add(duration);
        } else if (phase === 'during_batch') {
            apiDurationDuringBatch.add(duration);
            // 스파이크 감지: pre_batch 평균 대비 3배 이상
            if (duration > 1000) {
                spikeDetected.add(1);
                batchImpactSpike.add(duration);
            } else {
                spikeDetected.add(0);
            }
        } else {
            apiDurationPostBatch.add(duration);
        }
    });

    sleep(1.5);
}

// ============================================
// 배치 트리거 시뮬레이션
// 실제 @Scheduled 배치를 시뮬레이션하기 위해 대량 업데이트 유발
// ============================================
export function triggerBatch() {
    const user = testUsers[0];  // admin-like user
    const token = generateJWT(user);
    const headers = getHeaders(token);

    console.log('');
    console.log('=== BATCH TRIGGER: Simulating midnight batch ===');
    console.log('Sending concurrent schedule status queries to simulate batch impact...');
    console.log('');

    // 배치 시뮬레이션: 대량의 일정 관련 쿼리 동시 실행
    // 실제 updateScheduleStatus()는 대량 UPDATE이므로,
    // 동일 테이블에 대한 대량 읽기/쓰기를 시뮬레이션
    for (let i = 0; i < 100; i++) {
        const clubId = (i % 10000) + 1;

        // 일정 목록 조회 (배치가 UPDATE하는 동일 테이블)
        http.get(`${BASE_URL}/api/v1/clubs/${clubId}/schedules`, { headers });

        // 일정 참여 시도 (row lock 경합 시뮬레이션)
        const scheduleId = (i % 5000) + 1;
        http.request(
            'PATCH',
            `${BASE_URL}/api/v1/clubs/${clubId}/schedules/${scheduleId}/users`,
            null,
            { headers }
        );

        if (i % 20 === 0) {
            console.log(`[Batch Sim] Progress: ${i}/100`);
        }

        sleep(0.5);
    }

    console.log('=== BATCH TRIGGER COMPLETED ===');
}

// ============================================
// 배치 중 모니터링 (응답시간 집중 추적)
// ============================================
export function batchMonitor() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const clubId = ((user.userId - 1) % 10000) + 1;

    group('Batch Monitor', () => {
        // 배치 영향을 받는 일정 관련 API 집중 호출
        const res1 = http.get(
            `${BASE_URL}/api/v1/clubs/${clubId}/schedules`,
            { headers }
        );

        check(res1, {
            'monitor schedule: status 200': (r) => r.status === 200,
        });

        const duration = res1.timings.duration;
        apiDurationDuringBatch.add(duration);
        scheduleListDuration.add(duration);

        if (duration > 1000) {
            spikeDetected.add(1);
            batchImpactSpike.add(duration);
            if (__ITER % 10 === 0) {
                console.warn(`[Batch Impact] Schedule query slow: ${duration.toFixed(0)}ms`);
            }
        } else {
            spikeDetected.add(0);
        }
    });

    sleep(1);
}

// ============================================
// 테스트 라이프사이클
// ============================================
export function setup() {
    console.log('=== Midnight Batch Impact Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log(`Test Users: ${testUsers.length}`);
    console.log('');
    console.log('Test Plan:');
    console.log('- 0-5m: Background load (200 VU) - baseline');
    console.log('- 5m: Batch trigger + monitor (50 VU added)');
    console.log('- 5-10m: Observe impact during batch');
    console.log('- 10-15m: Recovery observation');
    console.log('');
    console.log('Total Duration: ~15 minutes');
    console.log('==========================================');

    return { startTime: Date.now() };
}

export function teardown(data) {
    console.log('');
    console.log('=== Midnight Batch Impact Test Completed ===');
    console.log('Compare metrics:');
    console.log('- api_duration_pre_batch vs api_duration_during_batch');
    console.log('- spike_detected rate');
    console.log('- batch_impact_spike_ms (max spike)');
    console.log('=============================================');
}
