// =============================================================
// notification-extreme-test.js
// 알림 시스템 극한 스트레스 테스트 (500 VUs, zero-sleep)
// =============================================================
//
// 실행:
//   MSYS_NO_PATHCONV=1 docker run --rm \
//     -v "$(pwd)/k6-tests:/scripts" \
//     --add-host=host.docker.internal:host-gateway \
//     grafana/k6:latest run /scripts/notification-extreme-test.js
//
// =============================================================
// Phase 1  — Warmup (20s)
// Phase 2  — Read Avalanche: 250 VUs, sleep 0 (2m30s)
// Phase 3  — Write Avalanche: 200 VUs, sleep 0 (2m)
// Phase 4  — Mixed Hammer: 300 VUs 읽기+쓰기 동시 (2m30s)
// Phase 5  — Spike 500 VUs (1m30s)
// Phase 6  — Triple Spike: 400→50→500→50→400 (3m)
// Phase 7  — Sustained 400 VUs (5m) — 한계 탐색
// Phase 8  — Cooldown (30s)
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, fetchNotificationIds, connectSSE } from './lib/common.js';

// ============================================
// 메트릭
// ============================================
const readAvDur       = new Trend('ex_read_av_duration',     true);
const readAvSuccess   = new Rate('ex_read_av_success');

const writeAvDur      = new Trend('ex_write_av_duration',    true);
const writeAvSuccess  = new Rate('ex_write_av_success');

const mixedDur        = new Trend('ex_mixed_duration',       true);
const mixedSuccess    = new Rate('ex_mixed_success');
const mixedErrors     = new Counter('ex_mixed_errors');

const spike500Dur     = new Trend('ex_spike500_duration',    true);
const spike500Success = new Rate('ex_spike500_success');
const spike500Errors  = new Counter('ex_spike500_errors');

const triSpikeDur     = new Trend('ex_trispike_duration',    true);
const triSpikeSuccess = new Rate('ex_trispike_success');

const sustainDur      = new Trend('ex_sustain_duration',     true);
const sustainSuccess  = new Rate('ex_sustain_success');
const sustainErrors   = new Counter('ex_sustain_errors');

// ============================================
// 설정
// ============================================
const USER_COUNT = parseInt(__ENV.USER_COUNT || '1000');

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus',
            vus: 20,
            duration: '20s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },

        // Phase 2: Read Avalanche — 250 VUs, zero think time
        read_avalanche: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '15s', target: 250 },
                { duration: '120s', target: 250 },
                { duration: '15s', target: 0 },
            ],
            startTime: '25s',
            exec: 'readAvalanche',
            tags: { phase: 'read_avalanche' },
        },

        // Phase 3: Write Avalanche — 200 VUs, zero think time
        write_avalanche: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '15s', target: 200 },
                { duration: '90s', target: 200 },
                { duration: '15s', target: 0 },
            ],
            startTime: '180s',
            exec: 'writeAvalanche',
            tags: { phase: 'write_avalanche' },
        },

        // Phase 4: Mixed Hammer — 300 VUs 읽기+쓰기 동시
        mixed_hammer: {
            executor: 'ramping-vus',
            startVUs: 30,
            stages: [
                { duration: '20s', target: 300 },
                { duration: '110s', target: 300 },
                { duration: '20s', target: 0 },
            ],
            startTime: '305s',
            exec: 'mixedHammer',
            tags: { phase: 'mixed_hammer' },
        },

        // Phase 5: Spike 500 VUs
        spike_500: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '10s', target: 500 },
                { duration: '50s', target: 500 },
                { duration: '15s', target: 10 },
                { duration: '15s', target: 10 },
            ],
            startTime: '460s',
            exec: 'spike500',
            tags: { phase: 'spike_500' },
        },

        // Phase 6: Triple Spike — 400→50→500→50→400
        triple_spike: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '10s', target: 400 },
                { duration: '20s', target: 400 },
                { duration: '5s', target: 50 },
                { duration: '10s', target: 50 },
                { duration: '10s', target: 500 },
                { duration: '25s', target: 500 },
                { duration: '5s', target: 50 },
                { duration: '10s', target: 50 },
                { duration: '10s', target: 400 },
                { duration: '20s', target: 400 },
                { duration: '15s', target: 10 },
                { duration: '20s', target: 10 },
            ],
            startTime: '555s',
            exec: 'tripleSpike',
            tags: { phase: 'triple_spike' },
        },

        // Phase 7: Sustained 400 VUs — 5분 한계 탐색
        sustained_400: {
            executor: 'ramping-vus',
            startVUs: 30,
            stages: [
                { duration: '30s', target: 400 },
                { duration: '240s', target: 400 },
                { duration: '30s', target: 0 },
            ],
            startTime: '740s',
            exec: 'sustained400',
            tags: { phase: 'sustained_400' },
        },

        // Phase 8: Cooldown
        cooldown: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            startTime: '1045s',
            exec: 'warmup',
            tags: { phase: 'cooldown' },
        },
    },

    thresholds: {
        'ex_read_av_duration':   ['p(95)<2000', 'p(99)<5000'],
        'ex_read_av_success':    ['rate>0.90'],
        'ex_write_av_duration':  ['p(95)<1000', 'p(99)<3000'],
        'ex_write_av_success':   ['rate>0.90'],
        'ex_mixed_duration':     ['p(95)<2000', 'p(99)<5000'],
        'ex_mixed_success':      ['rate>0.85'],
        'ex_spike500_duration':  ['p(95)<5000'],
        'ex_spike500_success':   ['rate>0.80'],
        'ex_trispike_duration':  ['p(95)<5000'],
        'ex_trispike_success':   ['rate>0.80'],
        'ex_sustain_duration':   ['p(95)<3000', 'p(99)<5000'],
        'ex_sustain_success':    ['rate>0.85'],
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

// ============================================
// Phase 1: Warmup
// ============================================
export function warmup() {
    const u = rndUser();
    const t = generateJWT(u);
    http.get(`${BASE_URL}/api/v1/notifications?size=5`, { headers: headers(t), tags: { name: 'w_list' } });
    http.get(`${BASE_URL}/api/v1/notifications/unread-count`, { headers: headers(t), tags: { name: 'w_unread' } });
    sleep(0.2);
}

// ============================================
// Phase 2: Read Avalanche — 250 VUs, NO sleep
// 목록+커서+읽지않은개수를 쉬지 않고 연속 호출
// ============================================
export function readAvalanche() {
    const u = vuUser();
    const t = generateJWT(u);

    // 첫 페이지
    const r1 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
        headers: headers(t), tags: { name: 'ra_list1' },
    });
    readAvDur.add(r1.timings.duration);
    readAvSuccess.add(r1.status === 200);

    // 커서 2번째 페이지
    if (r1.status === 200) {
        try {
            const d = JSON.parse(r1.body);
            const data = d.data || d;
            if (data.hasMore && data.cursor) {
                const r2 = http.get(`${BASE_URL}/api/v1/notifications?size=20&cursor=${data.cursor}`, {
                    headers: headers(t), tags: { name: 'ra_list2' },
                });
                readAvDur.add(r2.timings.duration);
                readAvSuccess.add(r2.status === 200);

                // 3번째 페이지
                if (r2.status === 200) {
                    try {
                        const d2 = JSON.parse(r2.body);
                        const data2 = d2.data || d2;
                        if (data2.hasMore && data2.cursor) {
                            const r3 = http.get(`${BASE_URL}/api/v1/notifications?size=20&cursor=${data2.cursor}`, {
                                headers: headers(t), tags: { name: 'ra_list3' },
                            });
                            readAvDur.add(r3.timings.duration);
                            readAvSuccess.add(r3.status === 200);
                        }
                    } catch(e) {}
                }
            }
        } catch(e) {}
    }

    // unread
    const ru = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: headers(t), tags: { name: 'ra_unread' },
    });
    readAvDur.add(ru.timings.duration);
    readAvSuccess.add(ru.status === 200);

    // NO sleep — 최대 throughput
}

// ============================================
// Phase 3: Write Avalanche — 200 VUs, NO sleep
// ============================================
export function writeAvalanche() {
    const u = vuUser();
    const t = generateJWT(u);
    const roll = Math.random();

    if (roll < 0.40) {
        // 40%: mark read
        const ids = fetchNotificationIds(t, 10);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            const r = http.put(`${BASE_URL}/api/v1/notifications/${id}/read`, null, {
                headers: headers(t), tags: { name: 'wa_mark' },
            });
            writeAvDur.add(r.timings.duration);
            writeAvSuccess.add(r.status === 200);
        }
    } else if (roll < 0.65) {
        // 25%: delete
        const ids = fetchNotificationIds(t, 10);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            const r = http.del(`${BASE_URL}/api/v1/notifications/${id}`, null, {
                headers: headers(t), tags: { name: 'wa_delete' },
            });
            writeAvDur.add(r.timings.duration);
            writeAvSuccess.add(r.status === 200);
        }
    } else {
        // 35%: mark all read (가장 무거운 연산, 비중 높임)
        const r = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: headers(t), tags: { name: 'wa_markall' },
        });
        writeAvDur.add(r.timings.duration);
        writeAvSuccess.add(r.status === 200);
    }
    // NO sleep
}

// ============================================
// Phase 4: Mixed Hammer — 300 VUs 읽기+쓰기 동시, NO sleep
// ============================================
export function mixedHammer() {
    const u = vuUser();
    const t = generateJWT(u);
    const roll = Math.random();
    let ok = false;
    let dur = 0;

    if (roll < 0.35) {
        // 35%: list
        const r = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(t), tags: { name: 'mh_list' },
        });
        dur = r.timings.duration; ok = r.status === 200;

    } else if (roll < 0.55) {
        // 20%: unread
        const r = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(t), tags: { name: 'mh_unread' },
        });
        dur = r.timings.duration; ok = r.status === 200;

    } else if (roll < 0.70) {
        // 15%: mark read
        const ids = fetchNotificationIds(t, 5);
        if (ids.length > 0) {
            const r = http.put(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: headers(t), tags: { name: 'mh_mark' },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }

    } else if (roll < 0.85) {
        // 15%: mark all
        const r = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: headers(t), tags: { name: 'mh_markall' },
        });
        dur = r.timings.duration; ok = r.status === 200;

    } else {
        // 15%: delete
        const ids = fetchNotificationIds(t, 5);
        if (ids.length > 0) {
            const r = http.del(`${BASE_URL}/api/v1/notifications/${ids[0]}`, null, {
                headers: headers(t), tags: { name: 'mh_delete' },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }
    }

    mixedDur.add(dur);
    mixedSuccess.add(ok);
    if (!ok) mixedErrors.add(1);
    // NO sleep
}

// ============================================
// Phase 5: Spike 500 VUs
// ============================================
export function spike500() {
    const u = rndUser();
    const t = generateJWT(u);
    const roll = Math.random();
    let ok = false; let dur = 0;

    if (roll < 0.40) {
        const r = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(t), tags: { name: 's5_list' },
        });
        dur = r.timings.duration; ok = r.status === 200;
    } else if (roll < 0.65) {
        const r = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(t), tags: { name: 's5_unread' },
        });
        dur = r.timings.duration; ok = r.status === 200;
    } else if (roll < 0.85) {
        const ids = fetchNotificationIds(t, 3);
        if (ids.length > 0) {
            const r = http.put(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: headers(t), tags: { name: 's5_mark' },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }
    } else {
        const r = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: headers(t), tags: { name: 's5_markall' },
        });
        dur = r.timings.duration; ok = r.status === 200;
    }

    spike500Dur.add(dur);
    spike500Success.add(ok);
    if (!ok) spike500Errors.add(1);
    // NO sleep
}

// ============================================
// Phase 6: Triple Spike — 400→50→500→50→400
// ============================================
export function tripleSpike() {
    const u = rndUser();
    const t = generateJWT(u);
    const roll = Math.random();
    let ok = false; let dur = 0;

    if (roll < 0.40) {
        const r = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(t), tags: { name: 'ts_list' },
        });
        dur = r.timings.duration; ok = r.status === 200;
    } else if (roll < 0.65) {
        const r = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(t), tags: { name: 'ts_unread' },
        });
        dur = r.timings.duration; ok = r.status === 200;
    } else if (roll < 0.82) {
        const ids = fetchNotificationIds(t, 3);
        if (ids.length > 0) {
            const r = http.put(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: headers(t), tags: { name: 'ts_mark' },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }
    } else {
        const r = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: headers(t), tags: { name: 'ts_markall' },
        });
        dur = r.timings.duration; ok = r.status === 200;
    }

    triSpikeDur.add(dur);
    triSpikeSuccess.add(ok);
    // NO sleep
}

// ============================================
// Phase 7: Sustained 400 VUs — 5분 한계 탐색
// ============================================
export function sustained400() {
    const u = vuUser();
    const t = generateJWT(u);
    const roll = Math.random();
    let ok = false; let dur = 0;

    if (roll < 0.35) {
        const r = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(t), tags: { name: 'su_list' },
        });
        dur = r.timings.duration; ok = r.status === 200;
    } else if (roll < 0.55) {
        const r = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(t), tags: { name: 'su_unread' },
        });
        dur = r.timings.duration; ok = r.status === 200;
    } else if (roll < 0.72) {
        const ids = fetchNotificationIds(t, 5);
        if (ids.length > 0) {
            const r = http.put(`${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null, {
                headers: headers(t), tags: { name: 'su_mark' },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }
    } else if (roll < 0.87) {
        const r = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: headers(t), tags: { name: 'su_markall' },
        });
        dur = r.timings.duration; ok = r.status === 200;
    } else {
        const ids = fetchNotificationIds(t, 5);
        if (ids.length > 0) {
            const r = http.del(`${BASE_URL}/api/v1/notifications/${ids[0]}`, null, {
                headers: headers(t), tags: { name: 'su_delete' },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }
    }

    sustainDur.add(dur);
    sustainSuccess.add(ok);
    if (!ok) sustainErrors.add(1);
    // NO sleep
}

export default function () { warmup(); }
