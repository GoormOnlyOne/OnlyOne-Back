import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ===== ENV =====
const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080'; // 로컬 부트앱
const AUTH     = (__ENV.AUTH_TOKEN && __ENV.AUTH_TOKEN.trim() !== '') ? `Bearer ${__ENV.AUTH_TOKEN}` : null;
const CLUB_IDS = (__ENV.CLUB_IDS || '1').split(',').map(s => s.trim()).filter(Boolean);

// 프로파일: smoke | load
const TEST_TYPE = (__ENV.TEST_TYPE || 'smoke').toLowerCase();

// ===== Metrics =====
const httpFailures = new Rate('http_req_failed_custom');
const clubLatency  = new Trend('club_detail_latency', true);

// ===== Options =====
const profiles = {
    smoke: {
        vus: 5, duration: '30s',
        thresholds: {
            http_req_failed: ['rate<0.01'],
            http_req_duration: ['p(95)<500'],
            club_detail_latency: ['p(95)<300'],
        },
    },
    load: {
        scenarios: {
            steady: {
                executor: 'ramping-vus',
                startVUs: 0,
                stages: [
                    { duration: '1m', target: 100 },
                    { duration: '3m', target: 100 },
                    { duration: '30s', target: 0 },
                ],
                gracefulRampDown: '20s',
            },
        },
        thresholds: {
            http_req_failed: ['rate<0.01'],
            http_req_duration: ['p(95)<600'],
            club_detail_latency: ['p(95)<400'],
        },
    },
};

const profile = profiles[TEST_TYPE] || profiles.smoke;

export const options = {
    ...(profile.vus ? { vus: profile.vus } : {}),
    ...(profile.duration ? { duration: profile.duration } : {}),
    ...(profile.scenarios ? { scenarios: profile.scenarios } : {}),
    thresholds: profile.thresholds,
};

// ===== Test =====
export default function () {
    for (const clubId of CLUB_IDS) {
        const url = `${BASE_URL}/clubs/${clubId}`;
        const params = {
            headers: {
                'Accept': 'application/json',
                ...(AUTH ? { 'Authorization': AUTH } : {}),
            },
            tags: { endpoint: 'club_detail', clubId: String(clubId) },
            timeout: '10s',
        };

        const res = http.get(url, params);
        httpFailures.add(res.status >= 400);
        clubLatency.add(res.timings.duration);

        check(res, {
            'status 200': r => r.status === 200,
            'json parseable': r => { try { r.json(); return true; } catch { return false; } },
            'common.success == true': r => {
                try { const b = r.json(); return b && b.success === true; } catch { return false; }
            },
            'has data': r => {
                try { const b = r.json(); return !!b?.data; } catch { return false; }
            },
        });
    }
    sleep(0.2);
}
