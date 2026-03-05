// =============================================================
// 검색 인덱스 동기화 검증 테스트 (Relevance + Consistency)
// =============================================================
//
// 목적:
//   1. 검색 관련성 검증 — 고빈도 키워드 검색 시 결과가 존재하는지,
//      의미 없는 키워드 검색 시 빈 결과를 반환하는지,
//      결과에 검색어가 포함되어 있는지 (name/description)
//   2. 결과 일관성 검증 — 동일 사용자, 동일 키워드로 3회 연속 검색 시
//      결과 건수와 정렬 순서가 동일한지
//
// 사전 조건:
//   - seed-search.sql 실행 완료 (club 200,000+개, user 100,000명)
//   - 검색 엔진 설정 완료 (ES 또는 MySQL FULLTEXT)
//   - 서버 기동 완료 (localhost:8080)
//
// 실행 (Docker, Git Bash):
//   MSYS_NO_PATHCONV=1 docker run --rm -i \
//     -v "$(pwd)/k6-tests:/scripts" \
//     --add-host=host.docker.internal:host-gateway \
//     grafana/k6 run /scripts/search/search-index-sync-test.js
//
// 환경변수:
//   BASE_URL       — 서버 주소 (기본 http://host.docker.internal:8080)
//   JWT_SECRET     — JWT 서명 키 (기본: 로컬 환경 키)
//   SEARCH_ENGINE  — 결과 태깅용 (기본: unknown) → ES/MySQL 구분
//
// Phase 구성 (4 Phase, 약 6분):
//   ┌─────┬──────────────────────────────────┬──────┬────────┐
//   │  #  │ Phase                            │  VU  │ 시간   │
//   ├─────┼──────────────────────────────────┼──────┼────────┤
//   │  1  │ Warmup — 커넥션풀/캐시 예열      │  50  │ 30s    │
//   │  2  │ Relevance — 검색 관련성 검증      │ 300  │ 2m     │
//   │  3  │ Consistency — 결과 일관성 검증    │ 200  │ 2m     │
//   │  4  │ Cooldown — 최종 검증              │   5  │ 30s    │
//   └─────┴──────────────────────────────────┴──────┴────────┘
//
// 메트릭:
//   search_relevance_hit              — Rate: 고빈도 키워드 결과에 검색어 포함
//   search_relevance_false_positive   — Rate: 의미없는 키워드에 결과 반환
//   search_consistency_count_match    — Rate: 3회 검색 결과 건수 일치
//   search_consistency_order_match    — Rate: 3회 검색 결과 순서 일치 (상위 3건 ID)
//   search_relevance_duration         — Trend(ms): 관련성 검증 응답시간
//   search_consistency_duration       — Trend(ms): 일관성 검증 응답시간
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, makeUser } from '../lib/common.js';

// ============================================
// 환경 설정
// ============================================
const SEARCH_ENGINE = __ENV.SEARCH_ENGINE || 'unknown';
const USER_COUNT = 100000;

// ============================================
// 테스트 데이터
// ============================================

// 고빈도 키워드 — 반드시 결과가 존재해야 함
const HIGH_FREQ_KEYWORDS = [
    '축구', '농구', '요가', '러닝', '등산',
    '독서', '영화', '여행', '영어', '주식',
];

// 매칭 불가 키워드 — 결과가 없어야 함 (false positive 탐지)
const NO_MATCH_KEYWORDS = [
    'qwfmpzxld', 'zzzznotexist', '마인크래프트서버호스팅',
];

// ============================================
// 커스텀 메트릭
// ============================================

// 관련성 메트릭
const relevanceHit           = new Rate('search_relevance_hit');
const relevanceFalsePositive = new Rate('search_relevance_false_positive');
const relevanceDuration      = new Trend('search_relevance_duration', true);

// 일관성 메트릭
const consistencyCountMatch  = new Rate('search_consistency_count_match');
const consistencyOrderMatch  = new Rate('search_consistency_order_match');
const consistencyDuration    = new Trend('search_consistency_duration', true);

// 전역 카운터
const totalRequests = new Counter('search_sync_total_requests');
const serverErrors  = new Counter('search_sync_5xx_errors');

// ============================================
// 유틸리티
// ============================================
function randomUser() {
    const userId = Math.floor(Math.random() * USER_COUNT) + 1;
    return makeUser(userId);
}

function pick(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
}

function parseResults(res) {
    try {
        const body = JSON.parse(res.body);
        if (Array.isArray(body.data)) return body.data;
        return [];
    } catch (e) {
        return [];
    }
}

function extractClubIds(results, limit) {
    const ids = [];
    const max = Math.min(results.length, limit || 3);
    for (let i = 0; i < max; i++) {
        ids.push(results[i].clubId);
    }
    return ids;
}

function arraysEqual(a, b) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
        if (a[i] !== b[i]) return false;
    }
    return true;
}

function recordGlobal(res) {
    totalRequests.add(1);
    if (res.status >= 500) serverErrors.add(1);
}

function searchClubs(token, keyword) {
    return http.get(
        `${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}&page=0&size=20`,
        { headers: headers(token), tags: { name: 'search_query' } }
    );
}

// ============================================
// k6 옵션
// ============================================
export const options = {
    scenarios: {
        // Phase 1: Warmup (30s)
        warmup: {
            executor: 'constant-vus',
            vus: 50,
            duration: '30s',
            exec: 'warmup',
            startTime: '0s',
            tags: { phase: '01_warmup' },
        },
        // Phase 2: Relevance validation (2m)
        relevance: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 300 },
                { duration: '1m30s', target: 300 },
                { duration: '15s', target: 0 },
            ],
            exec: 'relevanceValidation',
            startTime: '30s',
            tags: { phase: '02_relevance' },
        },
        // Phase 3: Result consistency (2m)
        consistency: {
            executor: 'ramping-vus',
            stages: [
                { duration: '15s', target: 200 },
                { duration: '1m30s', target: 200 },
                { duration: '15s', target: 0 },
            ],
            exec: 'resultConsistency',
            startTime: '2m30s',
            tags: { phase: '03_consistency' },
        },
        // Phase 4: Cooldown (30s)
        cooldown: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            exec: 'cooldown',
            startTime: '4m30s',
            tags: { phase: '04_cooldown' },
        },
    },
    thresholds: {
        // 관련성: 고빈도 키워드 80% 이상 결과에 검색어 포함
        'search_relevance_hit':            ['rate>0.80'],
        // 관련성: 의미없는 키워드 false positive 10% 미만
        'search_relevance_false_positive': ['rate<0.10'],
        // 일관성: 건수 일치 95% 이상
        'search_consistency_count_match':  ['rate>0.95'],
        // 일관성: 순서 일치 90% 이상
        'search_consistency_order_match':  ['rate>0.90'],
        // 응답시간
        'search_relevance_duration':       ['p(95)<1500', 'p(99)<3000'],
        'search_consistency_duration':     ['p(95)<1500', 'p(99)<3000'],
        // 전역
        'search_sync_5xx_errors':          ['count<20'],
    },
};

// ============================================
// Phase 1: Warmup — 커넥션풀/캐시 예열
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);

    // 고빈도 키워드 1회 검색
    const kw = pick(HIGH_FREQ_KEYWORDS);
    const res = searchClubs(token, kw);
    recordGlobal(res);

    check(res, {
        'warmup: 200': (r) => r.status === 200,
    });

    sleep(0.5);
}

// ============================================
// Phase 2: Relevance validation — 검색 관련성 검증
//   고빈도 키워드(70%): 결과 비어있지 않아야 하고,
//     결과의 name 또는 description에 키워드 포함 확인
//   매칭불가 키워드(30%): 결과가 비어있어야 함
// ============================================
export function relevanceValidation() {
    const user = randomUser();
    const token = generateJWT(user);
    const roll = Math.random();

    if (roll < 0.70) {
        // 고빈도 키워드 검색 → 결과 존재 + 관련성 검증
        const keyword = pick(HIGH_FREQ_KEYWORDS);
        const res = searchClubs(token, keyword);
        recordGlobal(res);
        relevanceDuration.add(res.timings.duration);

        const results = parseResults(res);

        check(res, {
            'relevance_high: 200': (r) => r.status === 200,
            'relevance_high: non-empty': () => results.length > 0,
        });

        // 결과 중 name 또는 description에 키워드가 포함된 항목이 있는지
        if (results.length > 0) {
            const hasRelevant = results.some(club => {
                const name = (club.name || '').toLowerCase();
                const desc = (club.description || '').toLowerCase();
                const kw = keyword.toLowerCase();
                return name.includes(kw) || desc.includes(kw);
            });
            relevanceHit.add(hasRelevant ? 1 : 0);
        } else {
            // 고빈도 키워드인데 결과가 없으면 hit 실패
            relevanceHit.add(0);
        }
    } else {
        // 매칭 불가 키워드 검색 → 결과가 비어있어야 함
        const keyword = pick(NO_MATCH_KEYWORDS);
        const res = searchClubs(token, keyword);
        recordGlobal(res);
        relevanceDuration.add(res.timings.duration);

        const results = parseResults(res);

        check(res, {
            'relevance_nomatch: 200': (r) => r.status === 200,
        });

        // 결과가 있으면 false positive
        relevanceFalsePositive.add(results.length > 0 ? 1 : 0);
    }

    sleep(0.2);
}

// ============================================
// Phase 3: Result consistency — 결과 일관성 검증
//   동일 사용자, 동일 키워드로 3회 연속 검색
//   → 결과 건수가 동일한지
//   → 상위 3건의 clubId 순서가 동일한지
// ============================================
export function resultConsistency() {
    const user = randomUser();
    const token = generateJWT(user);
    const keyword = pick(HIGH_FREQ_KEYWORDS);

    const responses = [];
    const resultSets = [];

    // 동일 키워드로 3회 연속 검색
    for (let i = 0; i < 3; i++) {
        const res = searchClubs(token, keyword);
        recordGlobal(res);
        consistencyDuration.add(res.timings.duration);

        if (res.status !== 200) {
            // 실패 시 일관성 측정 불가 — skip
            consistencyCountMatch.add(0);
            consistencyOrderMatch.add(0);
            sleep(0.3);
            return;
        }

        const results = parseResults(res);
        responses.push(res);
        resultSets.push(results);

        // 연속 요청 사이 짧은 대기 (서버 부하 분산)
        if (i < 2) sleep(0.05);
    }

    // 건수 일관성 검증: 3회 모두 같은 결과 건수
    const counts = resultSets.map(r => r.length);
    const countMatch = (counts[0] === counts[1]) && (counts[1] === counts[2]);
    consistencyCountMatch.add(countMatch ? 1 : 0);

    // 순서 일관성 검증: 상위 3건 clubId가 동일한 순서
    const ids0 = extractClubIds(resultSets[0], 3);
    const ids1 = extractClubIds(resultSets[1], 3);
    const ids2 = extractClubIds(resultSets[2], 3);
    const orderMatch = arraysEqual(ids0, ids1) && arraysEqual(ids1, ids2);
    consistencyOrderMatch.add(orderMatch ? 1 : 0);

    check(null, {
        'consistency: count match': () => countMatch,
        'consistency: order match': () => orderMatch,
    });

    sleep(0.3);
}

// ============================================
// Phase 4: Cooldown — 최종 검증
//   각 키워드 타입별 1회씩 순차 호출
// ============================================
export function cooldown() {
    const user = randomUser();
    const token = generateJWT(user);

    // 고빈도 키워드 검증
    const highRes = searchClubs(token, '축구');
    recordGlobal(highRes);
    check(highRes, {
        'cooldown_high: 200': (r) => r.status === 200,
        'cooldown_high: has results': (r) => {
            const results = parseResults(r);
            return results.length > 0;
        },
    });

    // 매칭 불가 키워드 검증
    const noMatchRes = searchClubs(token, 'qwfmpzxld');
    recordGlobal(noMatchRes);
    check(noMatchRes, {
        'cooldown_nomatch: 200': (r) => r.status === 200,
        'cooldown_nomatch: empty results': (r) => {
            const results = parseResults(r);
            return results.length === 0;
        },
    });

    // 일관성 최종 검증 (2회 연속)
    const kw = '영화';
    const r1 = searchClubs(token, kw);
    const r2 = searchClubs(token, kw);
    recordGlobal(r1);
    recordGlobal(r2);

    const res1 = parseResults(r1);
    const res2 = parseResults(r2);

    check(null, {
        'cooldown_consistency: same count': () => res1.length === res2.length,
        'cooldown_consistency: same order': () => {
            const a = extractClubIds(res1, 3);
            const b = extractClubIds(res2, 3);
            return arraysEqual(a, b);
        },
    });

    sleep(2);
}

// ============================================
// handleSummary — 검색 동기화 검증 리포트
// ============================================
export function handleSummary(data) {
    const engine = SEARCH_ENGINE;
    const line = '─'.repeat(60);

    let summary = `
╔${'═'.repeat(60)}╗
║   검색 인덱스 동기화 검증 결과 (engine: ${engine})${' '.repeat(Math.max(0, 23 - engine.length))}║
╚${'═'.repeat(60)}╝
`;

    // ────── 관련성 메트릭 ──────
    summary += `\n${'[Relevance — 검색 관련성]'.padEnd(40)}\n`;
    summary += `${line}\n`;

    const relHit = data.metrics['search_relevance_hit'];
    const relFP  = data.metrics['search_relevance_false_positive'];
    const relDur = data.metrics['search_relevance_duration'];

    summary += `  키워드 히트율 (name/desc 포함):  ${relHit ? (relHit.values.rate * 100).toFixed(1) + '%' : 'N/A'}\n`;
    summary += `  False Positive 비율:              ${relFP ? (relFP.values.rate * 100).toFixed(1) + '%' : 'N/A'}\n`;
    if (relDur && relDur.values) {
        summary += `  응답시간 p50/p95/p99:          ${fmt(relDur.values['p(50)'])} / ${fmt(relDur.values['p(95)'])} / ${fmt(relDur.values['p(99)'])}\n`;
    }
    summary += `${line}\n`;

    // ────── 일관성 메트릭 ──────
    summary += `\n${'[Consistency — 결과 일관성]'.padEnd(40)}\n`;
    summary += `${line}\n`;

    const conCount = data.metrics['search_consistency_count_match'];
    const conOrder = data.metrics['search_consistency_order_match'];
    const conDur   = data.metrics['search_consistency_duration'];

    summary += `  건수 일치율 (3회 동일 건수):        ${conCount ? (conCount.values.rate * 100).toFixed(1) + '%' : 'N/A'}\n`;
    summary += `  순서 일치율 (상위 3건 ID 동일):    ${conOrder ? (conOrder.values.rate * 100).toFixed(1) + '%' : 'N/A'}\n`;
    if (conDur && conDur.values) {
        summary += `  응답시간 p50/p95/p99:          ${fmt(conDur.values['p(50)'])} / ${fmt(conDur.values['p(95)'])} / ${fmt(conDur.values['p(99)'])}\n`;
    }
    summary += `${line}\n`;

    // ────── 전역 ──────
    const totalReq = data.metrics['search_sync_total_requests'];
    const total5xx = data.metrics['search_sync_5xx_errors'];
    summary += `\n총 요청: ${totalReq ? totalReq.values.count : 'N/A'}`;
    summary += `  |  5xx 에러: ${total5xx ? total5xx.values.count : 0}`;
    summary += `  |  엔진: ${engine}\n`;

    // Thresholds PASS/FAIL
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

    // 판정 기준
    summary += `\n판정 기준:\n`;
    summary += `  relevance_hit > 80%    |  false_positive < 10%\n`;
    summary += `  count_match > 95%      |  order_match > 90%\n`;
    summary += `  p95 < 1500ms           |  p99 < 3000ms\n`;

    console.log(summary);

    return {
        'stdout': summary,
        [`search-sync-result-${engine}-${Date.now()}.json`]: JSON.stringify(data, null, 2),
    };
}

function fmt(ms) {
    if (ms === undefined || ms === null) return 'N/A';
    if (ms < 1000) return ms.toFixed(0) + 'ms';
    return (ms / 1000).toFixed(2) + 's';
}
