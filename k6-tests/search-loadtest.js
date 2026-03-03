// =============================================================
// 검색 도메인 종합 부하 테스트 (Elasticsearch vs MySQL FULLTEXT)
// =============================================================
//
// 목적:
//   1. 검색 API 전체 엔드포인트 병목 식별
//   2. Elasticsearch ↔ MySQL FULLTEXT 성능 비교
//      - 동일 Phase, 동일 데이터, 동일 VU 수로 각 엔진 테스트
//      - 결과의 p50/p95/p99/max, 처리량(req/s), 성공률 비교
//   3. 키워드 길이/복잡도별 응답 시간 분포
//   4. 캐시 히트율 측정 (추천/팀메이트)
//   5. 동시 사용자 2000+ 스파이크 내성 검증
//
// 사전 조건:
//   - seed-search.sql 실행 완료 (club 200,000+개, user 100,000명, 관심사/가입 매핑)
//   - ES 모드: app.search.engine=elasticsearch + ES 인덱스 동기화 (reindex)
//   - MySQL 모드: app.search.engine=mysql (FULLTEXT 인덱스 자동 생성)
//   - 서버 기동 완료 (localhost:8080)
//
// 실행 (Docker, Git Bash):
//   MSYS_NO_PATHCONV=1 docker run --rm -i \
//     -v "$(pwd)/k6-tests:/scripts" \
//     --add-host=host.docker.internal:host-gateway \
//     grafana/k6 run /scripts/search-loadtest.js
//
// 환경변수:
//   BASE_URL     — 서버 주소 (기본 http://host.docker.internal:8080)
//   JWT_SECRET   — JWT 서명 키 (기본: 로컬 환경 키)
//   SEARCH_ENGINE — 결과 태깅용 (기본: unknown) → ES/MySQL 구분
//
// Phase 구성 (13 Phase, 약 22분):
//   ┌─────┬──────────────────────────────────────┬──────┬────────┐
//   │  #  │ Phase                                │  VU  │ 시간   │
//   ├─────┼──────────────────────────────────────┼──────┼────────┤
//   │  1  │ Warmup — 커넥션풀/캐시 예열          │  50  │ 30s    │
//   │  2  │ 키워드 검색 (단일 키워드)            │ 600  │ 2m     │
//   │  3  │ 키워드 검색 (복합 키워드)            │ 600  │ 1.5m   │
//   │  4  │ 키워드 + 지역 필터                   │ 750  │ 2m     │
//   │  5  │ 키워드 + 관심사 필터                 │ 750  │ 1.5m   │
//   │  6  │ 키워드 + 지역 + 관심사 (풀 필터)     │1000  │ 2m     │
//   │  7  │ 필터 전용 (키워드 없음, MySQL)       │ 600  │ 1.5m   │
//   │  8  │ 추천 모임 (캐시 히트 측정)           │ 600  │ 1.5m   │
//   │  9  │ 팀메이트 모임 (캐시 히트 측정)       │ 600  │ 1.5m   │
//   │ 10  │ 정렬 비교 (LATEST vs MEMBER_COUNT)   │ 600  │ 1.5m   │
//   │ 11  │ 페이징 심층 (page 0~9)               │ 500  │ 1.5m   │
//   │ 12  │ 스파이크 — 전 API 혼합 폭증          │2000  │ 1m     │
//   │ 13  │ Cooldown / 최종 검증                  │   5  │ 30s    │
//   └─────┴──────────────────────────────────────┴──────┴────────┘
//
// 메트릭 키 네이밍:
//   search_{카테고리}_duration  — Trend(ms), p50/p95/p99/max
//   search_{카테고리}_success   — Rate(0~1)
//   search_{카테고리}_result_count — Trend, 결과 건수 분포
//   search_{카테고리}_empty_rate — Rate, 빈 결과 비율 (검색 품질)
//   search_total_requests — Counter, 전체 요청 수
//   search_5xx_errors     — Counter, 서버 에러 수
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, makeUser } from './lib/common.js';

// ============================================
// 환경 설정
// ============================================
const SEARCH_ENGINE = __ENV.SEARCH_ENGINE || 'unknown';
const VALID_USER_COUNT = 100000;

// ============================================
// 테스트 데이터
// ============================================

// 단일 키워드 (고빈도 — 많은 결과 기대)
const KEYWORDS_HIGH_FREQ = [
    '축구', '농구', '요가', '러닝', '등산',
    '기타', '노래', '독서', '영화', '여행',
    '영어', '주식', '보드게임', '커피', '맛집',
];

// 단일 키워드 (저빈도 — 적은 결과 기대)
const KEYWORDS_LOW_FREQ = [
    '스쿠버다이빙', '캘리그라피', '목공', '블록체인', '프랑스어',
    '필라테스', '볼링', '오페라', '핸드드립', '히스패닉',
];

// 복합 키워드 (두 단어 조합)
const KEYWORDS_COMPOUND = [
    '축구 동호회', '기타 연주', '영어 회화', '주식 투자', '독서 토론',
    '요가 필라테스', '캠핑 아웃도어', '피아노 클래식', '와인 시음', '부동산 투자',
    '도자기 공예', '일본어 스터디', '보드게임 전략', '러닝 마라톤', '밴드 합주',
];

// 매칭 안 되는 키워드 (빈 결과 기대)
const KEYWORDS_NO_MATCH = [
    '마인크래프트', '로블록스', '포켓몬', '스타크래프트', '리그오브레전드',
];

// 도시/구 (10개) — DB의 실제 city/district 값과 일치시킴
const LOCATIONS = [
    { city: '서울', district: '강남구' },
    { city: '서울', district: '서구' },
    { city: '서울', district: '남구' },
    { city: '서울', district: '중구' },
    { city: '부산', district: '해운대구' },
    { city: '부산', district: '사하구' },
    { city: '부산', district: '북구' },
    { city: '대구', district: '서구' },
    { city: '인천', district: '서구' },
    { city: '광주', district: '남구' },
];

// 관심사 ID (1~8, Category enum 순서)
const INTEREST_IDS = [1, 2, 3, 4, 5, 6, 7, 8];
const INTEREST_NAMES = ['문화', '운동', '여행', '음악', '공예', '사교', '외국어', '재테크'];

// ============================================
// 커스텀 메트릭 (카테고리별 세분화)
// ============================================

// 전역 카운터
const totalRequests   = new Counter('search_total_requests');
const serverErrors    = new Counter('search_5xx_errors');

// Phase 2: 단일 키워드
const kwSingleDur     = new Trend('search_kw_single_duration', true);
const kwSingleOk      = new Rate('search_kw_single_success');
const kwSingleCount   = new Trend('search_kw_single_result_count');
const kwSingleEmpty   = new Rate('search_kw_single_empty_rate');

// Phase 3: 복합 키워드
const kwCompoundDur   = new Trend('search_kw_compound_duration', true);
const kwCompoundOk    = new Rate('search_kw_compound_success');
const kwCompoundCount = new Trend('search_kw_compound_result_count');
const kwCompoundEmpty = new Rate('search_kw_compound_empty_rate');

// Phase 4: 키워드 + 지역
const kwLocDur        = new Trend('search_kw_location_duration', true);
const kwLocOk         = new Rate('search_kw_location_success');
const kwLocCount      = new Trend('search_kw_location_result_count');
const kwLocEmpty      = new Rate('search_kw_location_empty_rate');

// Phase 5: 키워드 + 관심사
const kwIntDur        = new Trend('search_kw_interest_duration', true);
const kwIntOk         = new Rate('search_kw_interest_success');
const kwIntCount      = new Trend('search_kw_interest_result_count');

// Phase 6: 키워드 + 지역 + 관심사 (풀 필터)
const kwFullDur       = new Trend('search_kw_full_filter_duration', true);
const kwFullOk        = new Rate('search_kw_full_filter_success');
const kwFullCount     = new Trend('search_kw_full_filter_result_count');

// Phase 7: 필터 전용 (키워드 없음)
const filterOnlyDur   = new Trend('search_filter_only_duration', true);
const filterOnlyOk    = new Rate('search_filter_only_success');

// Phase 8: 추천 모임
const recommendDur    = new Trend('search_recommend_duration', true);
const recommendOk     = new Rate('search_recommend_success');
const recommendCount  = new Trend('search_recommend_result_count');

// Phase 9: 팀메이트 모임
const teammateDur     = new Trend('search_teammate_duration', true);
const teammateOk      = new Rate('search_teammate_success');
const teammateCount   = new Trend('search_teammate_result_count');

// Phase 10: 정렬 비교
const sortLatestDur   = new Trend('search_sort_latest_duration', true);
const sortLatestOk    = new Rate('search_sort_latest_success');
const sortMemberDur   = new Trend('search_sort_member_count_duration', true);
const sortMemberOk    = new Rate('search_sort_member_count_success');

// Phase 11: 페이징 심층
const pagingDur       = new Trend('search_paging_duration', true);
const pagingOk        = new Rate('search_paging_success');

// Phase 12: 스파이크
const spikeDur        = new Trend('search_spike_duration', true);
const spikeOk         = new Rate('search_spike_success');
const spike5xx        = new Rate('search_spike_5xx_rate');

// ============================================
// 유틸리티
// ============================================
function randomUser() {
    const userId = Math.floor(Math.random() * VALID_USER_COUNT) + 1;
    return makeUser(userId);
}

function pick(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function randomKeywordHigh() { return pick(KEYWORDS_HIGH_FREQ); }
function randomKeywordLow()  { return pick(KEYWORDS_LOW_FREQ); }
function randomKeywordCompound() { return pick(KEYWORDS_COMPOUND); }
function randomLocation()    { return pick(LOCATIONS); }
function randomInterestId()  { return pick(INTEREST_IDS); }

function parseResultCount(res) {
    try {
        const body = JSON.parse(res.body);
        if (Array.isArray(body.data)) return body.data.length;
        return 0;
    } catch (e) { return -1; }
}

function recordGlobal(res) {
    totalRequests.add(1);
    if (res.status >= 500) serverErrors.add(1);
}

// ============================================
// k6 옵션
// ============================================
export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus', vus: 50, duration: '30s',
            exec: 'warmup', startTime: '0s',
            tags: { phase: '01_warmup' },
        },
        // Phase 2: 단일 키워드 검색
        kw_single: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 600 },
                { duration: '1m30s', target: 600 },
                { duration: '15s', target: 0 },
            ],
            exec: 'keywordSingle', startTime: '30s',
            tags: { phase: '02_kw_single' },
        },
        // Phase 3: 복합 키워드 검색
        kw_compound: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 600 },
                { duration: '1m', target: 600 },
                { duration: '15s', target: 0 },
            ],
            exec: 'keywordCompound', startTime: '2m30s',
            tags: { phase: '03_kw_compound' },
        },
        // Phase 4: 키워드 + 지역 필터
        kw_location: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 750 },
                { duration: '1m30s', target: 750 },
                { duration: '15s', target: 0 },
            ],
            exec: 'keywordWithLocation', startTime: '4m',
            tags: { phase: '04_kw_location' },
        },
        // Phase 5: 키워드 + 관심사 필터
        kw_interest: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 750 },
                { duration: '1m', target: 750 },
                { duration: '15s', target: 0 },
            ],
            exec: 'keywordWithInterest', startTime: '6m',
            tags: { phase: '05_kw_interest' },
        },
        // Phase 6: 풀 필터 (키워드 + 지역 + 관심사)
        kw_full_filter: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 1000 },
                { duration: '1m30s', target: 1000 },
                { duration: '15s', target: 0 },
            ],
            exec: 'keywordFullFilter', startTime: '7m30s',
            tags: { phase: '06_kw_full_filter' },
        },
        // Phase 7: 필터 전용 (키워드 없음 → MySQL)
        filter_only: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 600 },
                { duration: '1m', target: 600 },
                { duration: '15s', target: 0 },
            ],
            exec: 'filterOnly', startTime: '9m30s',
            tags: { phase: '07_filter_only' },
        },
        // Phase 8: 추천 모임
        recommend: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 600 },
                { duration: '1m', target: 600 },
                { duration: '15s', target: 0 },
            ],
            exec: 'recommendClubs', startTime: '11m',
            tags: { phase: '08_recommend' },
        },
        // Phase 9: 팀메이트 모임
        teammate: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 600 },
                { duration: '1m', target: 600 },
                { duration: '15s', target: 0 },
            ],
            exec: 'teammateClubs', startTime: '12m30s',
            tags: { phase: '09_teammate' },
        },
        // Phase 10: 정렬 비교
        sort_compare: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 600 },
                { duration: '1m', target: 600 },
                { duration: '15s', target: 0 },
            ],
            exec: 'sortCompare', startTime: '14m',
            tags: { phase: '10_sort' },
        },
        // Phase 11: 페이징 심층
        paging_deep: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 500 },
                { duration: '1m', target: 500 },
                { duration: '15s', target: 0 },
            ],
            exec: 'pagingDeep', startTime: '15m30s',
            tags: { phase: '11_paging' },
        },
        // Phase 12: 스파이크
        spike: {
            executor: 'ramping-vus',
            stages: [
                { duration: '10s', target: 2000 },
                { duration: '40s', target: 2000 },
                { duration: '10s', target: 0 },
            ],
            exec: 'spikeTest', startTime: '17m',
            tags: { phase: '12_spike' },
        },
        // Phase 13: Cooldown + 최종 검증
        cooldown: {
            executor: 'constant-vus', vus: 5, duration: '30s',
            exec: 'finalCheck', startTime: '18m',
            tags: { phase: '13_cooldown' },
        },
    },
    thresholds: {
        // ────── 키워드 검색 (핵심 비교 지표) ──────
        'search_kw_single_duration':       ['p(95)<1000', 'p(99)<2000'],
        'search_kw_single_success':        ['rate>0.95'],
        'search_kw_compound_duration':     ['p(95)<1200', 'p(99)<2500'],
        'search_kw_compound_success':      ['rate>0.95'],

        // ────── 키워드 + 필터 (추가 조건 오버헤드) ──────
        'search_kw_location_duration':     ['p(95)<1000', 'p(99)<2000'],
        'search_kw_location_success':      ['rate>0.95'],
        'search_kw_interest_duration':     ['p(95)<1000', 'p(99)<2000'],
        'search_kw_interest_success':      ['rate>0.95'],
        'search_kw_full_filter_duration':  ['p(95)<1200', 'p(99)<2500'],
        'search_kw_full_filter_success':   ['rate>0.95'],

        // ────── 필터 전용 (MySQL only) ──────
        'search_filter_only_duration':     ['p(95)<500'],
        'search_filter_only_success':      ['rate>0.95'],

        // ────── 추천/팀메이트 (캐시 기대) ──────
        'search_recommend_duration':       ['p(95)<800'],
        'search_recommend_success':        ['rate>0.95'],
        'search_teammate_duration':        ['p(95)<800'],
        'search_teammate_success':         ['rate>0.95'],

        // ────── 정렬/페이징 ──────
        'search_sort_latest_duration':     ['p(95)<1000'],
        'search_sort_member_count_duration': ['p(95)<1000'],
        'search_paging_duration':          ['p(95)<1500'],
        'search_paging_success':           ['rate>0.95'],

        // ────── 스파이크 (완화된 임계값) ──────
        'search_spike_duration':           ['p(95)<3000'],
        'search_spike_success':            ['rate>0.90'],
        'search_spike_5xx_rate':           ['rate<0.05'],

        // ────── 전역 ──────
        'search_5xx_errors':               ['count<50'],
    },
};

// ============================================
// Phase 1: Warmup — 커넥션풀/캐시 예열
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 키워드 검색 1회
    http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(randomKeywordHigh())}`, {
        headers: hdrs, tags: { name: 'warmup_keyword' },
    });
    // 추천 1회
    http.get(`${BASE_URL}/api/v1/search/recommendations?page=0&size=5`, {
        headers: hdrs, tags: { name: 'warmup_recommend' },
    });
    // 필터 검색 1회
    const loc = randomLocation();
    http.get(`${BASE_URL}/api/v1/search/locations?city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}`, {
        headers: hdrs, tags: { name: 'warmup_filter' },
    });
    sleep(0.5);
}

// ============================================
// Phase 2: 단일 키워드 검색
//   고빈도(80%) vs 저빈도(15%) vs 매칭 없음(5%)
// ============================================
export function keywordSingle() {
    const user = randomUser();
    const token = generateJWT(user);
    const roll = Math.random();

    let keyword;
    if (roll < 0.80)      keyword = randomKeywordHigh();
    else if (roll < 0.95) keyword = randomKeywordLow();
    else                  keyword = pick(KEYWORDS_NO_MATCH);

    const res = http.get(
        `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}`,
        { headers: headers(token), tags: { name: 'kw_single' } }
    );
    recordGlobal(res);

    kwSingleDur.add(res.timings.duration);
    const count = parseResultCount(res);
    kwSingleCount.add(count >= 0 ? count : 0);
    kwSingleEmpty.add(count === 0 ? 1 : 0);

    const ok = check(res, {
        'kw_single: 200': (r) => r.status === 200,
        'kw_single: valid json': (r) => {
            try { JSON.parse(r.body); return true; } catch (e) { return false; }
        },
    });
    kwSingleOk.add(ok ? 1 : 0);

    sleep(0.2);
}

// ============================================
// Phase 3: 복합 키워드 검색 (두 단어 조합)
//   ES: multi_match minimum_should_match 50% 테스트
//   MySQL: BOOLEAN MODE +word1 +word2 테스트
// ============================================
export function keywordCompound() {
    const user = randomUser();
    const token = generateJWT(user);

    const keyword = randomKeywordCompound();
    const res = http.get(
        `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}`,
        { headers: headers(token), tags: { name: 'kw_compound' } }
    );
    recordGlobal(res);

    kwCompoundDur.add(res.timings.duration);
    const count = parseResultCount(res);
    kwCompoundCount.add(count >= 0 ? count : 0);
    kwCompoundEmpty.add(count === 0 ? 1 : 0);

    const ok = check(res, {
        'kw_compound: 200': (r) => r.status === 200,
    });
    kwCompoundOk.add(ok ? 1 : 0);

    sleep(0.2);
}

// ============================================
// Phase 4: 키워드 + 지역 필터
//   검색 엔진 키워드 매칭 + WHERE city/district
// ============================================
export function keywordWithLocation() {
    const user = randomUser();
    const token = generateJWT(user);
    const keyword = randomKeywordHigh();
    const loc = randomLocation();

    const url = `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}`
        + `&city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}`;

    const res = http.get(url, {
        headers: headers(token), tags: { name: 'kw_location' },
    });
    recordGlobal(res);

    kwLocDur.add(res.timings.duration);
    const count = parseResultCount(res);
    kwLocCount.add(count >= 0 ? count : 0);
    kwLocEmpty.add(count === 0 ? 1 : 0);

    const ok = check(res, {
        'kw_loc: 200': (r) => r.status === 200,
        'kw_loc: results filtered': (r) => {
            try {
                const body = JSON.parse(r.body);
                if (!Array.isArray(body.data) || body.data.length === 0) return true;
                return body.data.every(c => c.district === loc.district);
            } catch (e) { return false; }
        },
    });
    kwLocOk.add(ok ? 1 : 0);

    sleep(0.2);
}

// ============================================
// Phase 5: 키워드 + 관심사 필터
// ============================================
export function keywordWithInterest() {
    const user = randomUser();
    const token = generateJWT(user);
    const keyword = randomKeywordHigh();
    const interestId = randomInterestId();

    const url = `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}&interestId=${interestId}`;

    const res = http.get(url, {
        headers: headers(token), tags: { name: 'kw_interest' },
    });
    recordGlobal(res);

    kwIntDur.add(res.timings.duration);
    const count = parseResultCount(res);
    kwIntCount.add(count >= 0 ? count : 0);

    const ok = check(res, {
        'kw_int: 200': (r) => r.status === 200,
    });
    kwIntOk.add(ok ? 1 : 0);

    sleep(0.2);
}

// ============================================
// Phase 6: 풀 필터 (키워드 + 지역 + 관심사)
//   가장 무거운 검색 — 검색 엔진 + 3중 필터
// ============================================
export function keywordFullFilter() {
    const user = randomUser();
    const token = generateJWT(user);
    const keyword = Math.random() < 0.6 ? randomKeywordHigh() : randomKeywordCompound();
    const loc = randomLocation();
    const interestId = randomInterestId();

    const url = `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}`
        + `&city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}`
        + `&interestId=${interestId}`;

    const res = http.get(url, {
        headers: headers(token), tags: { name: 'kw_full_filter' },
    });
    recordGlobal(res);

    kwFullDur.add(res.timings.duration);
    const count = parseResultCount(res);
    kwFullCount.add(count >= 0 ? count : 0);

    const ok = check(res, {
        'kw_full: 200': (r) => r.status === 200,
    });
    kwFullOk.add(ok ? 1 : 0);

    sleep(0.2);
}

// ============================================
// Phase 7: 필터 전용 (키워드 없음 → 항상 MySQL)
//   지역, 관심사, 지역+관심사 혼합
// ============================================
export function filterOnly() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();

    let res;
    if (roll < 0.33) {
        // 지역 전용
        const loc = randomLocation();
        res = http.get(
            `${BASE_URL}/api/v1/search/locations?city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}`,
            { headers: hdrs, tags: { name: 'filter_location' } }
        );
    } else if (roll < 0.66) {
        // 관심사 전용
        res = http.get(
            `${BASE_URL}/api/v1/search/interests?interestId=${randomInterestId()}`,
            { headers: hdrs, tags: { name: 'filter_interest' } }
        );
    } else {
        // 지역 + 관심사 (키워드 없이 통합 검색)
        const loc = randomLocation();
        res = http.get(
            `${BASE_URL}/api/v1/search?city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}&interestId=${randomInterestId()}`,
            { headers: hdrs, tags: { name: 'filter_combined' } }
        );
    }
    recordGlobal(res);

    filterOnlyDur.add(res.timings.duration);
    const ok = check(res, {
        'filter_only: 200': (r) => r.status === 200,
    });
    filterOnlyOk.add(ok ? 1 : 0);

    sleep(0.3);
}

// ============================================
// Phase 8: 추천 모임 (캐시 @Cacheable 히트 측정)
//   같은 userId로 반복 호출 → Redis 캐시 효과 측정
// ============================================
export function recommendClubs() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 1차 호출 (cold)
    const r1 = http.get(`${BASE_URL}/api/v1/search/recommendations?page=0&size=20`, {
        headers: hdrs, tags: { name: 'recommend_cold' },
    });
    recordGlobal(r1);
    recommendDur.add(r1.timings.duration);
    recommendCount.add(parseResultCount(r1));

    // 2차 호출 (warm — 같은 userId, 캐시 히트 기대)
    const r2 = http.get(`${BASE_URL}/api/v1/search/recommendations?page=0&size=20`, {
        headers: hdrs, tags: { name: 'recommend_warm' },
    });
    recordGlobal(r2);
    recommendDur.add(r2.timings.duration);

    const ok = check(r1, { 'recommend: 200': (r) => r.status === 200 });
    recommendOk.add(ok ? 1 : 0);

    sleep(0.3);
}

// ============================================
// Phase 9: 팀메이트 모임 (캐시 히트 측정)
// ============================================
export function teammateClubs() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 1차 호출 (cold)
    const r1 = http.get(`${BASE_URL}/api/v1/search/teammates-clubs?page=0&size=20`, {
        headers: hdrs, tags: { name: 'teammate_cold' },
    });
    recordGlobal(r1);
    teammateDur.add(r1.timings.duration);
    teammateCount.add(parseResultCount(r1));

    // 2차 호출 (warm)
    const r2 = http.get(`${BASE_URL}/api/v1/search/teammates-clubs?page=0&size=20`, {
        headers: hdrs, tags: { name: 'teammate_warm' },
    });
    recordGlobal(r2);
    teammateDur.add(r2.timings.duration);

    const ok = check(r1, { 'teammate: 200': (r) => r.status === 200 });
    teammateOk.add(ok ? 1 : 0);

    sleep(0.3);
}

// ============================================
// Phase 10: 정렬 비교 (LATEST vs MEMBER_COUNT)
//   같은 키워드로 두 정렬 호출, 각각 응답시간 측정
// ============================================
export function sortCompare() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const keyword = randomKeywordHigh();

    // MEMBER_COUNT 정렬
    const r1 = http.get(
        `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}&sortBy=MEMBER_COUNT`,
        { headers: hdrs, tags: { name: 'sort_member_count' } }
    );
    recordGlobal(r1);
    sortMemberDur.add(r1.timings.duration);
    const ok1 = check(r1, { 'sort_member: 200': (r) => r.status === 200 });
    sortMemberOk.add(ok1 ? 1 : 0);

    // LATEST 정렬
    const r2 = http.get(
        `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}&sortBy=LATEST`,
        { headers: hdrs, tags: { name: 'sort_latest' } }
    );
    recordGlobal(r2);
    sortLatestDur.add(r2.timings.duration);
    const ok2 = check(r2, { 'sort_latest: 200': (r) => r.status === 200 });
    sortLatestOk.add(ok2 ? 1 : 0);

    sleep(0.3);
}

// ============================================
// Phase 11: 페이징 심층 (page 0~9)
//   깊은 페이지 오프셋 성능 — ES scroll vs MySQL OFFSET
// ============================================
export function pagingDeep() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const keyword = randomKeywordHigh();

    // 얕은 페이지 (0~2) 70% / 깊은 페이지 (3~9) 30%
    const page = Math.random() < 0.7
        ? Math.floor(Math.random() * 3)
        : Math.floor(Math.random() * 7) + 3;

    const res = http.get(
        `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}&page=${page}`,
        { headers: hdrs, tags: { name: `paging_p${page}` } }
    );
    recordGlobal(res);

    pagingDur.add(res.timings.duration);
    const ok = check(res, {
        'paging: 200': (r) => r.status === 200,
    });
    pagingOk.add(ok ? 1 : 0);

    sleep(0.2);
}

// ============================================
// Phase 12: 스파이크 — 전 API 혼합 폭증 (600 VUs)
//   실제 트래픽 분포 시뮬레이션:
//     키워드 검색 40% / 복합 필터 20% / 필터 전용 15%
//     추천 10% / 팀메이트 10% / 내 모임 5%
// ============================================
export function spikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();

    let res;

    if (roll < 0.22) {
        // 단일 키워드 (22%)
        const kw = randomKeywordHigh();
        res = http.get(
            `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(kw)}`,
            { headers: hdrs, tags: { name: 'spike_kw_single' } }
        );
    } else if (roll < 0.42) {
        // 복합 키워드 (20%)
        const kw = randomKeywordCompound();
        res = http.get(
            `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(kw)}`,
            { headers: hdrs, tags: { name: 'spike_kw_compound' } }
        );
    } else if (roll < 0.57) {
        // 키워드 + 지역 (15%)
        const kw = randomKeywordHigh();
        const loc = randomLocation();
        res = http.get(
            `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(kw)}&city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}`,
            { headers: hdrs, tags: { name: 'spike_kw_loc' } }
        );
    } else if (roll < 0.62) {
        // 풀 필터 (5%)
        const kw = randomKeywordHigh();
        const loc = randomLocation();
        res = http.get(
            `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(kw)}&city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}&interestId=${randomInterestId()}`,
            { headers: hdrs, tags: { name: 'spike_kw_full' } }
        );
    } else if (roll < 0.78) {
        // 필터 전용 (16%)
        const loc = randomLocation();
        res = http.get(
            `${BASE_URL}/api/v1/search/locations?city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}`,
            { headers: hdrs, tags: { name: 'spike_filter' } }
        );
    } else if (roll < 0.88) {
        // 추천 (10%)
        res = http.get(
            `${BASE_URL}/api/v1/search/recommendations?page=0&size=20`,
            { headers: hdrs, tags: { name: 'spike_recommend' } }
        );
    } else if (roll < 0.98) {
        // 팀메이트 (10%)
        res = http.get(
            `${BASE_URL}/api/v1/search/teammates-clubs?page=0&size=20`,
            { headers: hdrs, tags: { name: 'spike_teammate' } }
        );
    } else {
        // 내 모임 조회 (2%)
        res = http.get(
            `${BASE_URL}/api/v1/search/user`,
            { headers: hdrs, tags: { name: 'spike_my_clubs' } }
        );
    }

    recordGlobal(res);
    spikeDur.add(res.timings.duration);
    spike5xx.add(res.status >= 500 ? 1 : 0);

    const ok = check(res, {
        'spike: not 5xx': (r) => r.status < 500,
    });
    spikeOk.add(ok ? 1 : 0);

    sleep(0.1);
}

// ============================================
// Phase 13: Cooldown + 최종 검증
//   각 API 엔드포인트를 1회씩 순차 호출
// ============================================
export function finalCheck() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 1. 키워드 검색 (단일)
    const r1 = http.get(
        `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent('축구')}`,
        { headers: hdrs, tags: { name: 'final_kw' } }
    );
    check(r1, { 'final kw: 200': (r) => r.status === 200 });

    // 2. 키워드 + 지역 + 관심사
    const r2 = http.get(
        `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent('등산')}&city=${encodeURIComponent('서울')}&district=${encodeURIComponent('강남구')}&interestId=3`,
        { headers: hdrs, tags: { name: 'final_kw_full' } }
    );
    check(r2, { 'final kw_full: 200': (r) => r.status === 200 });

    // 3. 필터 전용 (지역)
    const r3 = http.get(
        `${BASE_URL}/api/v1/search/locations?city=${encodeURIComponent('부산')}&district=${encodeURIComponent('해운대구')}`,
        { headers: hdrs, tags: { name: 'final_loc' } }
    );
    check(r3, { 'final loc: 200': (r) => r.status === 200 });

    // 4. 필터 전용 (관심사)
    const r4 = http.get(
        `${BASE_URL}/api/v1/search/interests?interestId=4`,
        { headers: hdrs, tags: { name: 'final_interest' } }
    );
    check(r4, { 'final interest: 200': (r) => r.status === 200 });

    // 5. 추천 모임
    const r5 = http.get(
        `${BASE_URL}/api/v1/search/recommendations?page=0&size=5`,
        { headers: hdrs, tags: { name: 'final_recommend' } }
    );
    check(r5, { 'final recommend: 200': (r) => r.status === 200 });

    // 6. 팀메이트 모임
    const r6 = http.get(
        `${BASE_URL}/api/v1/search/teammates-clubs?page=0&size=5`,
        { headers: hdrs, tags: { name: 'final_teammate' } }
    );
    check(r6, { 'final teammate: 200': (r) => r.status === 200 });

    // 7. 내 모임 조회
    const r7 = http.get(
        `${BASE_URL}/api/v1/search/user`,
        { headers: hdrs, tags: { name: 'final_my_clubs' } }
    );
    check(r7, { 'final my_clubs: 200': (r) => r.status === 200 });

    // 8. 정렬 검증 (LATEST)
    const r8 = http.get(
        `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent('요가')}&sortBy=LATEST`,
        { headers: hdrs, tags: { name: 'final_sort' } }
    );
    check(r8, { 'final sort: 200': (r) => r.status === 200 });

    sleep(2);
}

// ============================================
// handleSummary — 엔진별 비교 리포트 출력
// ============================================
export function handleSummary(data) {
    const engine = SEARCH_ENGINE;
    const line = '─'.repeat(60);

    let summary = `
╔════════════════════════════════════════════════════════════╗
║          검색 부하 테스트 결과 (engine: ${engine.padEnd(14)})      ║
╚════════════════════════════════════════════════════════════╝
`;

    const metrics = [
        ['단일 키워드 검색',   'search_kw_single_duration'],
        ['복합 키워드 검색',   'search_kw_compound_duration'],
        ['키워드+지역',        'search_kw_location_duration'],
        ['키워드+관심사',      'search_kw_interest_duration'],
        ['키워드+풀필터',      'search_kw_full_filter_duration'],
        ['필터 전용(MySQL)',   'search_filter_only_duration'],
        ['추천 모임',          'search_recommend_duration'],
        ['팀메이트 모임',      'search_teammate_duration'],
        ['정렬:LATEST',        'search_sort_latest_duration'],
        ['정렬:MEMBER_COUNT',  'search_sort_member_count_duration'],
        ['페이징 심층',        'search_paging_duration'],
        ['스파이크(600VU)',    'search_spike_duration'],
    ];

    summary += `\n${line}\n`;
    summary += `${'API'.padEnd(22)} ${'p50'.padStart(8)} ${'p95'.padStart(8)} ${'p99'.padStart(8)} ${'max'.padStart(8)}  ${'avg'.padStart(8)}\n`;
    summary += `${line}\n`;

    for (const [label, key] of metrics) {
        const m = data.metrics[key];
        if (m && m.values) {
            const v = m.values;
            summary += `${label.padEnd(22)} ${fmt(v['p(50)'])} ${fmt(v['p(95)'])} ${fmt(v['p(99)'])} ${fmt(v['max'])}  ${fmt(v['avg'])}\n`;
        }
    }
    summary += `${line}\n`;

    // 결과 건수/빈 결과 비율
    const countMetrics = [
        ['단일 키워드 결과수',     'search_kw_single_result_count',   'search_kw_single_empty_rate'],
        ['복합 키워드 결과수',     'search_kw_compound_result_count', 'search_kw_compound_empty_rate'],
        ['키워드+지역 결과수',     'search_kw_location_result_count', 'search_kw_location_empty_rate'],
        ['추천 모임 결과수',       'search_recommend_result_count',   null],
        ['팀메이트 모임 결과수',   'search_teammate_result_count',    null],
    ];

    summary += `\n${'API'.padEnd(22)} ${'avg건수'.padStart(8)} ${'빈결과%'.padStart(8)}\n`;
    summary += `${line}\n`;

    for (const [label, countKey, emptyKey] of countMetrics) {
        const cm = data.metrics[countKey];
        const em = emptyKey ? data.metrics[emptyKey] : null;
        const avgCount = cm && cm.values ? cm.values['avg'].toFixed(1) : 'N/A';
        const emptyRate = em && em.values ? (em.values['rate'] * 100).toFixed(1) + '%' : 'N/A';
        summary += `${label.padEnd(22)} ${avgCount.padStart(8)} ${emptyRate.padStart(8)}\n`;
    }
    summary += `${line}\n`;

    // 전역 카운터
    const totalReq = data.metrics['search_total_requests'];
    const total5xx = data.metrics['search_5xx_errors'];
    summary += `\n총 요청: ${totalReq ? totalReq.values.count : 'N/A'}`;
    summary += `  |  5xx 에러: ${total5xx ? total5xx.values.count : 0}`;
    summary += `  |  엔진: ${engine}\n`;

    // Thresholds PASS/FAIL
    const thresholds = data.root_group ? data.root_group.checks : null;
    let passCount = 0, failCount = 0;
    if (data.metrics) {
        for (const [key, val] of Object.entries(data.metrics)) {
            if (val.thresholds) {
                for (const [, th] of Object.entries(val.thresholds)) {
                    if (th.ok) passCount++;
                    else failCount++;
                }
            }
        }
    }
    summary += `Thresholds: ${passCount} PASS / ${failCount} FAIL\n`;

    console.log(summary);

    return {
        'stdout': summary,
        [`search-result-${engine}-${Date.now()}.json`]: JSON.stringify(data, null, 2),
    };
}

function fmt(ms) {
    if (ms === undefined || ms === null) return 'N/A'.padStart(8);
    if (ms < 1000) return (ms.toFixed(0) + 'ms').padStart(8);
    return ((ms / 1000).toFixed(2) + 's').padStart(8);
}
