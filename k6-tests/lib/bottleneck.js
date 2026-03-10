// =============================================================
// 병목 탐지 공용 유틸리티 모듈
// =============================================================
// 각 도메인 k6 테스트에서 import하여 사용
//
// 기능:
//   - 엔드포인트별 커스텀 메트릭 (Trend, Rate, Counter)
//   - 병목 판정 (p95 > threshold)
//   - 결과 요약 리포트 출력
// =============================================================

import { Trend, Rate, Counter } from 'k6/metrics';

// ============================================
// 엔드포인트별 메트릭 팩토리
// ============================================
const metricStore = {};

/**
 * 엔드포인트별 메트릭 세트 생성/반환
 * @param {string} name - 엔드포인트 이름 (예: 'feed_list', 'notification_read')
 * @returns {{ duration: Trend, errors: Rate, count: Counter, p95Threshold: number }}
 */
export function getMetrics(name) {
    if (!metricStore[name]) {
        metricStore[name] = {
            duration: new Trend(`${name}_duration`, true),
            errors: new Rate(`${name}_errors`),
            count: new Counter(`${name}_count`),
        };
    }
    return metricStore[name];
}

/**
 * 응답을 메트릭에 기록
 * @param {string} name - 엔드포인트 이름
 * @param {object} res - k6 http response
 * @param {number} [expectedStatus=200] - 기대 상태 코드
 */
export function recordResponse(name, res, expectedStatus = 200) {
    const m = getMetrics(name);
    m.duration.add(res.timings.duration);
    m.errors.add(res.status !== expectedStatus ? 1 : 0);
    m.count.add(1);
}

/**
 * 커스텀 duration 기록 (수동 타이밍)
 * @param {string} name - 엔드포인트 이름
 * @param {number} durationMs - 밀리초
 * @param {boolean} [isError=false]
 */
export function recordCustom(name, durationMs, isError = false) {
    const m = getMetrics(name);
    m.duration.add(durationMs);
    m.errors.add(isError ? 1 : 0);
    m.count.add(1);
}

// ============================================
// 병목 임계값 설정
// ============================================
export const THRESHOLDS = {
    // 빠른 응답 (읽기, 캐시)
    FAST: 200,      // p95 < 200ms
    // 일반 응답 (DB 조회)
    NORMAL: 500,    // p95 < 500ms
    // 느린 응답 (복합 쿼리, 집계)
    SLOW: 1000,     // p95 < 1000ms
    // 매우 느린 (배치, 정산)
    VERY_SLOW: 3000, // p95 < 3000ms
};

/**
 * k6 thresholds 객체 생성
 * @param {Object<string, number>} endpointThresholds - { name: threshold_ms }
 * @returns {Object} k6 options.thresholds 형식
 */
export function buildThresholds(endpointThresholds) {
    const thresholds = {
        http_req_duration: ['p(95)<3000'],
        http_req_failed: ['rate<0.05'],
    };

    for (const [name, threshold] of Object.entries(endpointThresholds)) {
        thresholds[`${name}_duration`] = [`p(95)<${threshold}`];
        thresholds[`${name}_errors`] = ['rate<0.05'];
    }

    return thresholds;
}

// ============================================
// 단계별 부하 정의 유틸
// ============================================

/**
 * 5단계 점진적 부하 생성
 * @param {number} baseVUs - 시작 VU 수
 * @param {number} maxVUs - 최대 VU 수
 * @param {string} [stepDuration='2m'] - 각 단계 지속 시간
 */
export function progressiveStages(baseVUs, maxVUs, stepDuration = '2m') {
    const step = Math.floor((maxVUs - baseVUs) / 4);
    return [
        { duration: '30s', target: baseVUs },                    // 워밍업
        { duration: stepDuration, target: baseVUs },              // Phase 1: 기본
        { duration: stepDuration, target: baseVUs + step },       // Phase 2: 증가
        { duration: stepDuration, target: baseVUs + step * 2 },   // Phase 3: 중간
        { duration: stepDuration, target: baseVUs + step * 3 },   // Phase 4: 높음
        { duration: stepDuration, target: maxVUs },               // Phase 5: 최대
        { duration: '30s', target: 0 },                           // 쿨다운
    ];
}

/**
 * 버스트 부하 (짧은 시간 높은 동시성)
 * @param {number} burstVUs - 버스트 VU
 * @param {string} [burstDuration='1m']
 */
export function burstStages(burstVUs, burstDuration = '1m') {
    return [
        { duration: '15s', target: Math.floor(burstVUs * 0.1) }, // 워밍업
        { duration: burstDuration, target: burstVUs },            // 버스트
        { duration: '15s', target: 0 },                           // 쿨다운
    ];
}

// ============================================
// 유저 선택 유틸
// ============================================

/**
 * 테스트 유저 풀에서 랜덤 유저 반환
 * @param {number} [min=1]
 * @param {number} [max=1000]
 */
export function randomUser(min = 1, max = 100000) {
    const userId = Math.floor(Math.random() * (max - min + 1)) + min;
    return {
        userId: userId,
        kakaoId: 1000000 + userId,
        status: 'ACTIVE',
        role: 'ROLE_USER',
    };
}

/**
 * VU별 고정 유저 반환 (VU ID 기반)
 * @param {number} vuId - __VU
 * @param {number} [maxUsers=1000]
 */
export function vuUser(vuId, maxUsers = 100000) {
    const userId = ((vuId - 1) % maxUsers) + 1;
    return {
        userId: userId,
        kakaoId: 1000000 + userId,
        status: 'ACTIVE',
        role: 'ROLE_USER',
    };
}

// ============================================
// 응답 검증 유틸
// ============================================

/**
 * JSON 응답에서 data 추출
 * @param {object} res - k6 response
 * @returns {object|null}
 */
export function parseData(res) {
    try {
        const body = JSON.parse(res.body);
        return body.data || body;
    } catch (e) {
        return null;
    }
}

/**
 * 페이지네이션 응답에서 항목 수 추출
 * @param {object} res - k6 response
 * @param {string} [listKey] - 배열 필드명 (없으면 자동 탐색)
 * @returns {number}
 */
export function countItems(res, listKey) {
    const data = parseData(res);
    if (!data) return 0;

    if (listKey && data[listKey]) {
        return Array.isArray(data[listKey]) ? data[listKey].length : 0;
    }

    // 자동 탐색: 첫 번째 배열 필드
    for (const key of Object.keys(data)) {
        if (Array.isArray(data[key])) return data[key].length;
    }
    return 0;
}
