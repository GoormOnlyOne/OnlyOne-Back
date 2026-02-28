// =============================================================
// notification-endurance-light.js
// 알림 시스템 내구성 테스트 — 대용량 데이터(14M+) 버전
// =============================================================
//
// 실행:
//   MSYS_NO_PATHCONV=1 docker run --rm \
//     -v "$(pwd)/k6-tests:/scripts" \
//     --add-host=host.docker.internal:host-gateway \
//     grafana/k6:latest run /scripts/notification-endurance-light.js
//
// =============================================================
// Phase 1  — Warmup (30s, 15 VUs)
// Phase 2  — Write Storm 80%: 300 VUs (2m30s)
// Phase 3  — Hot User Contention: 200 VUs, user 1~10 집중 (2m)
// Phase 4  — Sustained 400 VUs Mixed: 8분
//            - write 비율 점진 증가 40%→60%→80%
// Phase 5  — Post-Sustain Spike: 600 VUs (1m)
// Phase 6  — Cooldown (30s)
// =============================================================
// 총 예상 시간: ~16분
// Max VUs: 600
// Think time: 0.1s (최소 브레이크 — zero-sleep 대비 커넥션 과포화 방지)
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, fetchNotificationIds } from './lib/common.js';

// ============================================
// 메트릭
// ============================================
// Phase 2: Write Storm
const writeStormDur     = new Trend('el_write_storm_duration',   true);
const writeStormSuccess = new Rate('el_write_storm_success');
const writeStormErrors  = new Counter('el_write_storm_errors');

// Phase 3: Hot User Contention
const hotUserDur        = new Trend('el_hotuser_duration',       true);
const hotUserSuccess    = new Rate('el_hotuser_success');

// Phase 4: Sustained (3구간)
const sustain1Dur       = new Trend('el_sustain_early_duration', true);
const sustain1Success   = new Rate('el_sustain_early_success');
const sustain2Dur       = new Trend('el_sustain_mid_duration',   true);
const sustain2Success   = new Rate('el_sustain_mid_success');
const sustain3Dur       = new Trend('el_sustain_late_duration',  true);
const sustain3Success   = new Rate('el_sustain_late_success');
const sustainErrors     = new Counter('el_sustain_errors');

// Phase 5: Post-Sustain Spike
const postSpikeDur      = new Trend('el_postspike_duration',     true);
const postSpikeSuccess  = new Rate('el_postspike_success');

// ============================================
// 설정
// ============================================
const USER_COUNT = parseInt(__ENV.USER_COUNT || '1000');

export const options = {
    scenarios: {
        // Phase 1: Warmup — 15 VUs, 30s
        warmup: {
            executor: 'constant-vus',
            vus: 15,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },

        // Phase 2: Write Storm — 300 VUs, 쓰기 80% (2분 30초)
        write_storm: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '15s', target: 300 },
                { duration: '120s', target: 300 },
                { duration: '15s', target: 0 },
            ],
            startTime: '35s',
            exec: 'writeStorm',
            tags: { phase: 'write_storm' },
        },

        // Phase 3: Hot User Contention — user 1~10에 200 VUs 집중 (2분)
        hot_user_contention: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '15s', target: 200 },
                { duration: '90s', target: 200 },
                { duration: '15s', target: 0 },
            ],
            startTime: '190s',
            exec: 'hotUserContention',
            tags: { phase: 'hot_user' },
        },

        // Phase 4: Sustained 400 VUs — 8분 (ramp 20s + hold 7m20s + ramp 20s)
        sustained_endurance: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '20s', target: 400 },
                { duration: '440s', target: 400 },  // 7분 20초 유지
                { duration: '20s', target: 0 },
            ],
            startTime: '315s',
            exec: 'sustainedEndurance',
            tags: { phase: 'sustained' },
        },

        // Phase 5: Post-Sustain Spike — 600 VUs, 1분
        post_sustain_spike: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '10s', target: 600 },
                { duration: '40s', target: 600 },
                { duration: '10s', target: 0 },
            ],
            startTime: '800s',
            exec: 'postSustainSpike',
            tags: { phase: 'post_spike' },
        },

        // Phase 6: Cooldown — 5 VUs, 30s
        cooldown: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            startTime: '865s',
            exec: 'warmup',
            tags: { phase: 'cooldown' },
        },
    },

    thresholds: {
        // Write Storm — 14M 데이터 감안 임계치
        'el_write_storm_duration':   ['p(95)<2000', 'p(99)<4000'],
        'el_write_storm_success':    ['rate>0.90'],

        // Hot User Contention
        'el_hotuser_duration':       ['p(95)<3000', 'p(99)<6000'],
        'el_hotuser_success':        ['rate>0.85'],

        // Sustained 3구간
        'el_sustain_early_duration': ['p(95)<2500', 'p(99)<5000'],
        'el_sustain_early_success':  ['rate>0.90'],
        'el_sustain_mid_duration':   ['p(95)<4000', 'p(99)<7000'],
        'el_sustain_mid_success':    ['rate>0.85'],
        'el_sustain_late_duration':  ['p(95)<5000', 'p(99)<8000'],
        'el_sustain_late_success':   ['rate>0.80'],

        // Post-Sustain Spike
        'el_postspike_duration':     ['p(95)<6000'],
        'el_postspike_success':      ['rate>0.80'],
    },
};

// ============================================
// 유틸
// ============================================
function rndUser() {
    const userId = Math.floor(Math.random() * USER_COUNT) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}
function vuUser() {
    const userId = (__VU % USER_COUNT) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}
function hotUser() {
    const userId = Math.floor(Math.random() * 10) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

// 공통: write 작업
function doWrite(t, tags_prefix) {
    const roll = Math.random();
    let dur = 0, ok = false;

    if (roll < 0.35) {
        const ids = fetchNotificationIds(t, 10);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            const r = http.put(`${BASE_URL}/api/v1/notifications/${id}/read`, null, {
                headers: headers(t), tags: { name: `${tags_prefix}_mark` },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }
    } else if (roll < 0.65) {
        const r = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: headers(t), tags: { name: `${tags_prefix}_markall` },
        });
        dur = r.timings.duration; ok = r.status === 200;
    } else {
        const ids = fetchNotificationIds(t, 10);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            const r = http.del(`${BASE_URL}/api/v1/notifications/${id}`, null, {
                headers: headers(t), tags: { name: `${tags_prefix}_delete` },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }
    }

    return { dur, ok };
}

// 공통: read 작업
function doRead(t, tags_prefix) {
    const roll = Math.random();
    let dur = 0, ok = false;

    if (roll < 0.55) {
        const r1 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(t), tags: { name: `${tags_prefix}_list` },
        });
        dur = r1.timings.duration; ok = r1.status === 200;

        if (r1.status === 200) {
            try {
                const d = JSON.parse(r1.body);
                const data = d.data || d;
                if (data.hasMore && data.cursor) {
                    http.get(`${BASE_URL}/api/v1/notifications?size=20&cursor=${data.cursor}`, {
                        headers: headers(t), tags: { name: `${tags_prefix}_list2` },
                    });
                }
            } catch(e) {}
        }
    } else {
        const r = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(t), tags: { name: `${tags_prefix}_unread` },
        });
        dur = r.timings.duration; ok = r.status === 200;
    }

    return { dur, ok };
}

// ============================================
// Phase 1: Warmup
// ============================================
export function warmup() {
    const u = rndUser();
    const t = generateJWT(u);
    http.get(`${BASE_URL}/api/v1/notifications?size=5`, { headers: headers(t), tags: { name: 'w_list' } });
    http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers: headers(t), tags: { name: 'w_unread' } });
    sleep(0.3);
}

// ============================================
// Phase 2: Write Storm — 200 VUs, 쓰기 80%
// ============================================
export function writeStorm() {
    const u = vuUser();
    const t = generateJWT(u);
    const roll = Math.random();

    let dur = 0, ok = false;

    if (roll < 0.80) {
        const result = doWrite(t, 'ws');
        dur = result.dur; ok = result.ok;
    } else {
        const result = doRead(t, 'ws');
        dur = result.dur; ok = result.ok;
    }

    writeStormDur.add(dur);
    writeStormSuccess.add(ok);
    if (!ok) writeStormErrors.add(1);
    sleep(0.1);
}

// ============================================
// Phase 3: Hot User Contention — user 1~10에 150 VUs
// ============================================
export function hotUserContention() {
    const u = hotUser();
    const t = generateJWT(u);
    const roll = Math.random();

    let dur = 0, ok = false;

    if (roll < 0.50) {
        const r1 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(t), tags: { name: 'hu_list1' },
        });
        dur = r1.timings.duration; ok = r1.status === 200;

        if (r1.status === 200) {
            try {
                const d = JSON.parse(r1.body);
                const data = d.data || d;
                if (data.hasMore && data.cursor) {
                    http.get(`${BASE_URL}/api/v1/notifications?size=20&cursor=${data.cursor}`, {
                        headers: headers(t), tags: { name: 'hu_list2' },
                    });
                }
            } catch(e) {}
        }
    } else if (roll < 0.70) {
        const ids = fetchNotificationIds(t, 20);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            const r = http.put(`${BASE_URL}/api/v1/notifications/${id}/read`, null, {
                headers: headers(t), tags: { name: 'hu_mark' },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }
    } else if (roll < 0.85) {
        const r = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: headers(t), tags: { name: 'hu_markall' },
        });
        dur = r.timings.duration; ok = r.status === 200;
    } else {
        const ids = fetchNotificationIds(t, 20);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            const r = http.del(`${BASE_URL}/api/v1/notifications/${id}`, null, {
                headers: headers(t), tags: { name: 'hu_delete' },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }
    }

    hotUserDur.add(dur);
    hotUserSuccess.add(ok);
    sleep(0.1);
}

// ============================================
// Phase 4: Sustained 300 VUs — 8분
// __ITER 기반 write 비율 점진 증가
//   0~700:   write 40%  (초반 ~2.5분)
//   700~1400: write 60%  (중반 ~2.5분)
//   1400+:    write 80%  (후반 ~3분)
// ============================================
export function sustainedEndurance() {
    const u = vuUser();
    const t = generateJWT(u);

    const iterPerVU = __ITER;
    let writeRatio;
    let trendDur, trendSuccess;

    if (iterPerVU < 700) {
        writeRatio = 0.40;
        trendDur = sustain1Dur;
        trendSuccess = sustain1Success;
    } else if (iterPerVU < 1400) {
        writeRatio = 0.60;
        trendDur = sustain2Dur;
        trendSuccess = sustain2Success;
    } else {
        writeRatio = 0.80;
        trendDur = sustain3Dur;
        trendSuccess = sustain3Success;
    }

    const roll = Math.random();
    let dur = 0, ok = false;

    if (roll < writeRatio) {
        const result = doWrite(t, 'se');
        dur = result.dur; ok = result.ok;
    } else {
        const result = doRead(t, 'se');
        dur = result.dur; ok = result.ok;
    }

    trendDur.add(dur);
    trendSuccess.add(ok);
    if (!ok) sustainErrors.add(1);
    sleep(0.1);
}

// ============================================
// Phase 5: Post-Sustain Spike — 400 VUs
// ============================================
export function postSustainSpike() {
    const u = rndUser();
    const t = generateJWT(u);
    const roll = Math.random();
    let dur = 0, ok = false;

    if (roll < 0.50) {
        const result = doWrite(t, 'ps');
        dur = result.dur; ok = result.ok;
    } else {
        const result = doRead(t, 'ps');
        dur = result.dur; ok = result.ok;
    }

    postSpikeDur.add(dur);
    postSpikeSuccess.add(ok);
    sleep(0.1);
}

export default function () { warmup(); }
