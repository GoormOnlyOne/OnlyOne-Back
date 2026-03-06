// =============================================================
// 개인/인기 피드 집중 부하 테스트 (UNION ALL 최적화 검증)
// =============================================================
// Phase 구성 (~5분, 최대 800 VUs):
// ┌────────┬──────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                  │ VU   │ 시간  │
// ├────────┼──────────────────────────┼──────┼───────┤
// │ 1      │ Warmup                   │ 30   │ 15s   │
// │ 2      │ Personal Feed 집중       │ 500  │ 90s   │
// │ 3      │ Popular Feed 집중        │ 500  │ 90s   │
// │ 4      │ 혼합 극한 (개인+인기)     │ 800  │ 90s   │
// └────────┴──────────────────────────┴──────┴───────┘
// =============================================================

import http from 'k6/http';
import { sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, makeUser } from '../lib/common.js';

const VALID_USER_COUNT = parseInt(__ENV.USER_COUNT || '100000');

// 커스텀 메트릭
const personalDur = new Trend('pf_personal_duration', true);
const popularDur  = new Trend('pf_popular_duration', true);
const personalSuccess = new Rate('pf_personal_success');
const popularSuccess  = new Rate('pf_popular_success');
const phase2Success = new Rate('pf_phase2_success');
const phase3Success = new Rate('pf_phase3_success');
const phase4Success = new Rate('pf_phase4_success');
const totalErrors = new Counter('pf_total_errors');

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus',
            vus: 30,
            duration: '15s',
            exec: 'warmup',
            tags: { phase: '1_warmup' },
        },
        personal_feed: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 500 },
                { duration: '60s', target: 500 },
                { duration: '15s', target: 0 },
            ],
            startTime: '20s',
            exec: 'personalFeed',
            tags: { phase: '2_personal' },
        },
        popular_feed: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 500 },
                { duration: '60s', target: 500 },
                { duration: '15s', target: 0 },
            ],
            startTime: '115s',
            exec: 'popularFeed',
            tags: { phase: '3_popular' },
        },
        mixed_extreme: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '15s', target: 800 },
                { duration: '60s', target: 800 },
                { duration: '15s', target: 0 },
            ],
            startTime: '210s',
            exec: 'mixedExtreme',
            tags: { phase: '4_mixed' },
        },
    },
    thresholds: {
        'pf_personal_duration': ['p(95)<1000'],
        'pf_popular_duration':  ['p(95)<1000'],
        'pf_phase2_success':    ['rate>0.95'],
        'pf_phase3_success':    ['rate>0.95'],
        'pf_phase4_success':    ['rate>0.90'],
    },
};

function randomUser() {
    const userId = Math.floor(Math.random() * VALID_USER_COUNT) + 1;
    return makeUser(userId);
}

function vuUser(vuId) {
    const userId = ((vuId - 1) % VALID_USER_COUNT) + 1;
    return makeUser(userId);
}

// Phase 1: Warmup
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    http.get(`${BASE_URL}/api/v1/feeds?page=0&limit=20`, {
        headers: hdrs, tags: { name: 'wu_personal' },
    });
    http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&limit=20`, {
        headers: hdrs, tags: { name: 'wu_popular' },
    });
    sleep(0.3);
}

// Phase 2: Personal Feed 집중 — 500 VUs
export function personalFeed() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const page = Math.floor(Math.random() * 5);

    const res = http.get(`${BASE_URL}/api/v1/feeds?page=${page}&limit=20`, {
        headers: hdrs, tags: { name: 'pf_personal' },
    });
    personalDur.add(res.timings.duration);
    personalSuccess.add(res.status === 200);
    phase2Success.add(res.status === 200);
    if (res.status !== 200) totalErrors.add(1);

    sleep(0.05 + Math.random() * 0.1);
}

// Phase 3: Popular Feed 집중 — 500 VUs
export function popularFeed() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&limit=20`, {
        headers: hdrs, tags: { name: 'pf_popular' },
    });
    popularDur.add(res.timings.duration);
    popularSuccess.add(res.status === 200);
    phase3Success.add(res.status === 200);
    if (res.status !== 200) totalErrors.add(1);

    sleep(0.05 + Math.random() * 0.1);
}

// Phase 4: 혼합 극한 — 800 VUs (개인 60%, 인기 40%)
export function mixedExtreme() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    if (Math.random() < 0.6) {
        const res = http.get(`${BASE_URL}/api/v1/feeds?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'mx_personal' },
        });
        personalDur.add(res.timings.duration);
        personalSuccess.add(res.status === 200);
        phase4Success.add(res.status === 200);
        if (res.status !== 200) totalErrors.add(1);
    } else {
        const res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'mx_popular' },
        });
        popularDur.add(res.timings.duration);
        popularSuccess.add(res.status === 200);
        phase4Success.add(res.status === 200);
        if (res.status !== 200) totalErrors.add(1);
    }

    sleep(0.02 + Math.random() * 0.05);
}

// default
export default function () {
    warmup();
}

// 결과 리포트
export function handleSummary(data) {
    const line = '─'.repeat(55);
    let summary = `
╔═══════════════════════════════════════════════════════╗
║      개인/인기 피드 집중 테스트 결과 (UNION ALL)       ║
╚═══════════════════════════════════════════════════════╝
`;

    const metrics = [
        ['개인 피드 (personal)', 'pf_personal_duration'],
        ['인기 피드 (popular)',  'pf_popular_duration'],
    ];

    summary += `\n${line}\n`;
    summary += `${'API'.padEnd(25)} ${'p50'.padStart(8)} ${'p95'.padStart(8)} ${'p99'.padStart(8)} ${'max'.padStart(8)}  ${'avg'.padStart(8)}\n`;
    summary += `${line}\n`;

    for (const [label, key] of metrics) {
        const m = data.metrics[key];
        if (m && m.values) {
            const v = m.values;
            summary += `${label.padEnd(25)} ${fmt(v['p(50)'])} ${fmt(v['p(95)'])} ${fmt(v['p(99)'])} ${fmt(v['max'])}  ${fmt(v['avg'])}\n`;
        }
    }
    summary += `${line}\n`;

    const phases = [
        ['Phase 2 Personal (500VU)', 'pf_phase2_success'],
        ['Phase 3 Popular (500VU)',  'pf_phase3_success'],
        ['Phase 4 Mixed (800VU)',    'pf_phase4_success'],
    ];

    summary += `\n${'Phase'.padEnd(30)} ${'성공률'.padStart(10)}\n`;
    summary += `${line}\n`;
    for (const [label, key] of phases) {
        const m = data.metrics[key];
        if (m && m.values) {
            const rate = (m.values['rate'] * 100).toFixed(2) + '%';
            summary += `${label.padEnd(30)} ${rate.padStart(10)}\n`;
        }
    }
    summary += `${line}\n`;

    const errMetric = data.metrics['pf_total_errors'];
    summary += `\n총 에러: ${errMetric ? errMetric.values.count : 0}\n`;

    let passCount = 0, failCount = 0;
    if (data.metrics) {
        for (const [, val] of Object.entries(data.metrics)) {
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
    return { 'stdout': summary };
}

function fmt(ms) {
    if (ms === undefined || ms === null) return 'N/A'.padStart(8);
    if (ms < 1000) return (ms.toFixed(0) + 'ms').padStart(8);
    return ((ms / 1000).toFixed(2) + 's').padStart(8);
}
