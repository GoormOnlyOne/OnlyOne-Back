// =============================================================
// 검색 도메인 빠른 성능 측정 (100 VU, ~4분)
// — 각 검색 유형별 순수 응답 속도 집중 측정
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, makeUser } from '../lib/common.js';

const SEARCH_ENGINE = __ENV.SEARCH_ENGINE || 'mysql';
const VALID_USER_COUNT = parseInt(__ENV.USER_COUNT || '10000');
const MAX_VUS = parseInt(__ENV.MAX_VUS || '100');
const DURATION = __ENV.DURATION || '3m';
const WARMUP_VUS = Math.max(1, Math.round(MAX_VUS * 0.1));
const COOLDOWN_VUS = Math.max(1, Math.round(MAX_VUS * 0.05));

// 키워드
const KW_HIGH = ['축구','농구','요가','러닝','등산','기타','노래','독서','영화','여행','영어','주식','보드게임','커피','맛집'];
const KW_LOW = ['스쿠버다이빙','캘리그라피','목공','블록체인','프랑스어'];
const KW_COMPOUND = ['축구 동호회','기타 연주','영어 회화','주식 투자','독서 토론','요가 필라테스','캠핑 아웃도어'];
const LOCATIONS = [
    {city:'서울',district:'강남구'},{city:'서울',district:'서구'},{city:'서울',district:'남구'},
    {city:'부산',district:'해운대구'},{city:'대구',district:'서구'},{city:'인천',district:'서구'},
];
const INTEREST_IDS = [1,2,3,4,5,6,7,8];

function pick(a) { return a[Math.floor(Math.random()*a.length)]; }
function rndUser() { return makeUser(Math.floor(Math.random()*VALID_USER_COUNT)+1); }
function parseDurationSec(d) {
    const m = d.match(/^(\d+)([sm])$/);
    if (!m) return 180;
    return m[2] === 'm' ? parseInt(m[1]) * 60 : parseInt(m[1]);
}

// 메트릭
const kwSingleDur   = new Trend('kw_single_dur', true);
const kwCompoundDur = new Trend('kw_compound_dur', true);
const kwLocDur      = new Trend('kw_location_dur', true);
const kwIntDur      = new Trend('kw_interest_dur', true);
const kwFullDur     = new Trend('kw_full_filter_dur', true);
const filterOnlyDur = new Trend('filter_only_dur', true);
const recommendDur  = new Trend('recommend_dur', true);
const teammateDur   = new Trend('teammate_dur', true);
const sortLatestDur = new Trend('sort_latest_dur', true);
const sortMemberDur = new Trend('sort_member_dur', true);
const pagingDur     = new Trend('paging_dur', true);
const totalOk       = new Rate('success_rate');
const err5xx        = new Counter('errors_5xx');

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus', vus: WARMUP_VUS, duration: '10s',
            exec: 'mixedSearch', startTime: '0s',
        },
        main: {
            executor: 'constant-vus', vus: MAX_VUS, duration: DURATION,
            exec: 'mixedSearch', startTime: '15s',
        },
        cooldown: {
            executor: 'constant-vus', vus: COOLDOWN_VUS, duration: '15s',
            exec: 'mixedSearch', startTime: `${15 + parseDurationSec(DURATION) + 5}s`,
        },
    },
    thresholds: {
        'kw_single_dur':      ['p(95)<1000'],
        'kw_compound_dur':    ['p(95)<1200'],
        'kw_location_dur':    ['p(95)<1000'],
        'kw_interest_dur':    ['p(95)<1000'],
        'kw_full_filter_dur': ['p(95)<1200'],
        'filter_only_dur':    ['p(95)<500'],
        'recommend_dur':      ['p(95)<800'],
        'teammate_dur':       ['p(95)<800'],
        'sort_latest_dur':    ['p(95)<1000'],
        'sort_member_dur':    ['p(95)<1000'],
        'paging_dur':         ['p(95)<1500'],
        'success_rate':       ['rate>0.95'],
    },
};

export function mixedSearch() {
    const user = rndUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let res;

    if (roll < 0.18) {
        // 단일 키워드
        const kw = Math.random() < 0.85 ? pick(KW_HIGH) : pick(KW_LOW);
        res = http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(kw)}`,
            { headers: hdrs, tags: { name: 'kw_single' } });
        kwSingleDur.add(res.timings.duration);
    } else if (roll < 0.30) {
        // 복합 키워드
        res = http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(pick(KW_COMPOUND))}`,
            { headers: hdrs, tags: { name: 'kw_compound' } });
        kwCompoundDur.add(res.timings.duration);
    } else if (roll < 0.40) {
        // 키워드 + 지역
        const loc = pick(LOCATIONS);
        res = http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(pick(KW_HIGH))}&city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}`,
            { headers: hdrs, tags: { name: 'kw_location' } });
        kwLocDur.add(res.timings.duration);
    } else if (roll < 0.48) {
        // 키워드 + 관심사
        res = http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(pick(KW_HIGH))}&interestId=${pick(INTEREST_IDS)}`,
            { headers: hdrs, tags: { name: 'kw_interest' } });
        kwIntDur.add(res.timings.duration);
    } else if (roll < 0.55) {
        // 키워드 + 풀필터
        const loc = pick(LOCATIONS);
        res = http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(pick(KW_HIGH))}&city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}&interestId=${pick(INTEREST_IDS)}`,
            { headers: hdrs, tags: { name: 'kw_full_filter' } });
        kwFullDur.add(res.timings.duration);
    } else if (roll < 0.67) {
        // 필터 전용 (키워드 없음)
        const loc = pick(LOCATIONS);
        const r2 = Math.random();
        if (r2 < 0.33)
            res = http.get(`${BASE_URL}/api/v1/search/locations?city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}`,
                { headers: hdrs, tags: { name: 'filter_loc' } });
        else if (r2 < 0.66)
            res = http.get(`${BASE_URL}/api/v1/search/interests?interestId=${pick(INTEREST_IDS)}`,
                { headers: hdrs, tags: { name: 'filter_interest' } });
        else
            res = http.get(`${BASE_URL}/api/v1/search?city=${encodeURIComponent(loc.city)}&district=${encodeURIComponent(loc.district)}&interestId=${pick(INTEREST_IDS)}`,
                { headers: hdrs, tags: { name: 'filter_combined' } });
        filterOnlyDur.add(res.timings.duration);
    } else if (roll < 0.76) {
        // 추천
        res = http.get(`${BASE_URL}/api/v1/search/recommendations?page=0&size=20`,
            { headers: hdrs, tags: { name: 'recommend' } });
        recommendDur.add(res.timings.duration);
    } else if (roll < 0.84) {
        // 팀메이트
        res = http.get(`${BASE_URL}/api/v1/search/teammates-clubs?page=0&size=20`,
            { headers: hdrs, tags: { name: 'teammate' } });
        teammateDur.add(res.timings.duration);
    } else if (roll < 0.90) {
        // 정렬 비교
        const kw = pick(KW_HIGH);
        if (Math.random() < 0.5) {
            res = http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(kw)}&sortBy=LATEST`,
                { headers: hdrs, tags: { name: 'sort_latest' } });
            sortLatestDur.add(res.timings.duration);
        } else {
            res = http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(kw)}&sortBy=MEMBER_COUNT`,
                { headers: hdrs, tags: { name: 'sort_member' } });
            sortMemberDur.add(res.timings.duration);
        }
    } else {
        // 페이징
        const page = Math.random() < 0.7 ? Math.floor(Math.random()*3) : Math.floor(Math.random()*7)+3;
        res = http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(pick(KW_HIGH))}&page=${page}`,
            { headers: hdrs, tags: { name: `paging_p${page}` } });
        pagingDur.add(res.timings.duration);
    }

    if (res.status >= 500) err5xx.add(1);
    totalOk.add(res.status < 500);
    sleep(0.1);
}

export function handleSummary(data) {
    const line = '\u2500'.repeat(60);
    let s = `
\u2554${'='.repeat(58)}\u2557
\u2551     Search Quick Test (engine: ${SEARCH_ENGINE}, 100 VUs)        \u2551
\u255A${'='.repeat(58)}\u255D
`;
    const metrics = [
        ['Single keyword',    'kw_single_dur'],
        ['Compound keyword',  'kw_compound_dur'],
        ['Keyword+Location',  'kw_location_dur'],
        ['Keyword+Interest',  'kw_interest_dur'],
        ['Keyword+FullFilter','kw_full_filter_dur'],
        ['Filter only(MySQL)','filter_only_dur'],
        ['Recommend',         'recommend_dur'],
        ['Teammate',          'teammate_dur'],
        ['Sort:LATEST',       'sort_latest_dur'],
        ['Sort:MEMBER_COUNT', 'sort_member_dur'],
        ['Paging(deep)',      'paging_dur'],
    ];

    s += `\n${line}\n`;
    s += `${'API'.padEnd(22)} ${'p50'.padStart(8)} ${'p95'.padStart(8)} ${'p99'.padStart(8)} ${'max'.padStart(8)}  ${'avg'.padStart(8)}\n`;
    s += `${line}\n`;

    for (const [label, key] of metrics) {
        const m = data.metrics[key];
        if (m && m.values) {
            const v = m.values;
            s += `${label.padEnd(22)} ${fmt(v['p(50)'])} ${fmt(v['p(95)'])} ${fmt(v['p(99)'])} ${fmt(v['max'])}  ${fmt(v['avg'])}\n`;
        }
    }
    s += `${line}\n`;

    let pass=0, fail=0;
    for (const [,val] of Object.entries(data.metrics)) {
        if (val.thresholds) for (const [,th] of Object.entries(val.thresholds)) { if(th.ok) pass++; else fail++; }
    }
    const e5 = data.metrics['errors_5xx'];
    s += `\nThresholds: ${pass} PASS / ${fail} FAIL  |  5xx: ${e5?e5.values.count:0}\n`;

    console.log(s);
    return { 'stdout': s };
}

function fmt(ms) {
    if (ms === undefined || ms === null) return 'N/A'.padStart(8);
    if (ms < 1000) return (ms.toFixed(0) + 'ms').padStart(8);
    return ((ms / 1000).toFixed(2) + 's').padStart(8);
}
