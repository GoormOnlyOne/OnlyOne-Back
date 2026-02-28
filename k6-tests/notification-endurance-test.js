// =============================================================
// notification-endurance-test.js
// 알림 시스템 내구성 테스트 (Sustained 15분 + 공격적 Write 패턴)
// =============================================================
//
// 실행:
//   MSYS_NO_PATHCONV=1 docker run --rm \
//     -v "$(pwd)/k6-tests:/scripts" \
//     --add-host=host.docker.internal:host-gateway \
//     grafana/k6:latest run /scripts/notification-endurance-test.js
//
// =============================================================
// Phase 1  — Warmup (30s)
// Phase 2  — Write Storm 80%: 300 VUs, 쓰기 80% (3m)
// Phase 3  — Hot User Contention: 200 VUs, user 1~10 집중 (2m)
// Phase 4  — Sustained 400 VUs Mixed: 15분 장기 (15m)
//            - 1~5분: read 60% write 40%
//            - 5~10분: read 30% write 70%
//            - 10~15분: read 20% write 80%
// Phase 5  — Post-Sustain Spike: 500 VUs (1m)
// Phase 6  — Cooldown (30s)
// =============================================================
// 총 예상 시간: ~22분
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, fetchNotificationIds } from './lib/common.js';

// ============================================
// 메트릭
// ============================================
// Phase 2: Write Storm
const writeStormDur     = new Trend('en_write_storm_duration',   true);
const writeStormSuccess = new Rate('en_write_storm_success');
const writeStormErrors  = new Counter('en_write_storm_errors');

// Phase 3: Hot User Contention
const hotUserDur        = new Trend('en_hotuser_duration',       true);
const hotUserSuccess    = new Rate('en_hotuser_success');

// Phase 4: Sustained 15min (3구간으로 세분화)
const sustain1Dur       = new Trend('en_sustain_early_duration', true);  // 1~5분
const sustain1Success   = new Rate('en_sustain_early_success');
const sustain2Dur       = new Trend('en_sustain_mid_duration',   true);  // 5~10분
const sustain2Success   = new Rate('en_sustain_mid_success');
const sustain3Dur       = new Trend('en_sustain_late_duration',  true);  // 10~15분
const sustain3Success   = new Rate('en_sustain_late_success');
const sustainErrors     = new Counter('en_sustain_errors');

// Phase 5: Post-Sustain Spike
const postSpikeDur      = new Trend('en_postspike_duration',     true);
const postSpikeSuccess  = new Rate('en_postspike_success');

// ============================================
// 설정
// ============================================
const USER_COUNT = parseInt(__ENV.USER_COUNT || '1000');

// Sustained 구간 시작/종료 시각 (Phase 4 내부 시간 분리용)
const SUSTAIN_START_SEC = 390;   // Phase 4 시작 = 30 + 200 + 160 = 390s

export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: 20,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },

        // Phase 2: Write Storm — 300 VUs, 쓰기 80% (극단적 write 부하)
        write_storm: {
            executor: 'ramping-vus',
            startVUs: 30,
            stages: [
                { duration: '20s', target: 300 },
                { duration: '140s', target: 300 },
                { duration: '20s', target: 0 },
            ],
            startTime: '35s',
            exec: 'writeStorm',
            tags: { phase: 'write_storm' },
        },

        // Phase 3: Hot User Contention — user 1~10에 200 VUs 집중
        hot_user_contention: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '15s', target: 200 },
                { duration: '120s', target: 200 },
                { duration: '15s', target: 0 },
            ],
            startTime: '225s',
            exec: 'hotUserContention',
            tags: { phase: 'hot_user' },
        },

        // Phase 4: Sustained 400 VUs — 15분 (ramp up 30s + hold 14m + ramp down 30s)
        sustained_endurance: {
            executor: 'ramping-vus',
            startVUs: 30,
            stages: [
                { duration: '30s', target: 400 },
                { duration: '840s', target: 400 },  // 14분 유지
                { duration: '30s', target: 0 },
            ],
            startTime: '390s',   // 6m30s
            exec: 'sustainedEndurance',
            tags: { phase: 'sustained' },
        },

        // Phase 5: Post-Sustain Spike — 15분 후 지친 상태에서 500 VU 스파이크
        post_sustain_spike: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '10s', target: 500 },
                { duration: '40s', target: 500 },
                { duration: '10s', target: 0 },
            ],
            startTime: '1295s',  // 21m35s (sustained 끝나고 5초 후)
            exec: 'postSustainSpike',
            tags: { phase: 'post_spike' },
        },

        // Phase 6: Cooldown
        cooldown: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            startTime: '1360s',
            exec: 'warmup',
            tags: { phase: 'cooldown' },
        },
    },

    thresholds: {
        // Write Storm (80% write)
        'en_write_storm_duration':   ['p(95)<1500', 'p(99)<3000'],
        'en_write_storm_success':    ['rate>0.90'],

        // Hot User Contention
        'en_hotuser_duration':       ['p(95)<2000', 'p(99)<5000'],
        'en_hotuser_success':        ['rate>0.85'],

        // Sustained 15분 3구간
        'en_sustain_early_duration': ['p(95)<2000', 'p(99)<4000'],  // 1~5분
        'en_sustain_early_success':  ['rate>0.90'],
        'en_sustain_mid_duration':   ['p(95)<3000', 'p(99)<5000'],  // 5~10분 (write 70%)
        'en_sustain_mid_success':    ['rate>0.85'],
        'en_sustain_late_duration':  ['p(95)<4000', 'p(99)<6000'],  // 10~15분 (write 80%)
        'en_sustain_late_success':   ['rate>0.80'],

        // Post-Sustain Spike
        'en_postspike_duration':     ['p(95)<5000'],
        'en_postspike_success':      ['rate>0.80'],
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
    const userId = Math.floor(Math.random() * 10) + 1;  // user 1~10
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

// 공통: write 작업 수행
function doWrite(t, tags_prefix) {
    const roll = Math.random();
    let dur = 0, ok = false;

    if (roll < 0.35) {
        // 35%: mark read (단건)
        const ids = fetchNotificationIds(t, 10);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            const r = http.put(`${BASE_URL}/api/v1/notifications/${id}/read`, null, {
                headers: headers(t), tags: { name: `${tags_prefix}_mark` },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }

    } else if (roll < 0.65) {
        // 30%: mark all read (가장 무거운 연산)
        const r = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: headers(t), tags: { name: `${tags_prefix}_markall` },
        });
        dur = r.timings.duration; ok = r.status === 200;

    } else {
        // 35%: delete
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

// 공통: read 작업 수행
function doRead(t, tags_prefix) {
    const roll = Math.random();
    let dur = 0, ok = false;

    if (roll < 0.55) {
        // 55%: list (pagination 2페이지)
        const r1 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(t), tags: { name: `${tags_prefix}_list` },
        });
        dur = r1.timings.duration; ok = r1.status === 200;

        // 커서 2번째 페이지
        if (r1.status === 200) {
            try {
                const d = JSON.parse(r1.body);
                const data = d.data || d;
                if (data.hasMore && data.cursor) {
                    const r2 = http.get(`${BASE_URL}/api/v1/notifications?size=20&cursor=${data.cursor}`, {
                        headers: headers(t), tags: { name: `${tags_prefix}_list2` },
                    });
                    // 2번째 페이지는 별도 기록 안 함 (1차 응답으로 대표)
                }
            } catch(e) {}
        }

    } else {
        // 45%: unread count
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
    sleep(0.2);
}

// ============================================
// Phase 2: Write Storm — 300 VUs, 쓰기 80%
// DB lock 경합, connection pool 고갈 유발
// ============================================
export function writeStorm() {
    const u = vuUser();
    const t = generateJWT(u);
    const roll = Math.random();

    let dur = 0, ok = false;

    if (roll < 0.80) {
        // 80%: write
        const result = doWrite(t, 'ws');
        dur = result.dur; ok = result.ok;
    } else {
        // 20%: read (write 사이에 끼인 read → 캐시 무효화 확인)
        const result = doRead(t, 'ws');
        dur = result.dur; ok = result.ok;
    }

    writeStormDur.add(dur);
    writeStormSuccess.add(ok);
    if (!ok) writeStormErrors.add(1);
    // NO sleep
}

// ============================================
// Phase 3: Hot User Contention — user 1~10에 200 VUs 집중
// 동일 유저 row에 대한 lock 경합 극대화
// ============================================
export function hotUserContention() {
    const u = hotUser();
    const t = generateJWT(u);
    const roll = Math.random();

    let dur = 0, ok = false;

    if (roll < 0.50) {
        // 50%: 같은 유저 알림 목록 + 커서 3페이지
        const r1 = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(t), tags: { name: 'hu_list1' },
        });
        dur = r1.timings.duration; ok = r1.status === 200;

        if (r1.status === 200) {
            try {
                const d = JSON.parse(r1.body);
                const data = d.data || d;
                if (data.hasMore && data.cursor) {
                    const r2 = http.get(`${BASE_URL}/api/v1/notifications?size=20&cursor=${data.cursor}`, {
                        headers: headers(t), tags: { name: 'hu_list2' },
                    });
                    if (r2.status === 200) {
                        try {
                            const d2 = JSON.parse(r2.body);
                            const data2 = d2.data || d2;
                            if (data2.hasMore && data2.cursor) {
                                http.get(`${BASE_URL}/api/v1/notifications?size=20&cursor=${data2.cursor}`, {
                                    headers: headers(t), tags: { name: 'hu_list3' },
                                });
                            }
                        } catch(e) {}
                    }
                }
            } catch(e) {}
        }
    } else if (roll < 0.70) {
        // 20%: mark read (hot user row lock 경합)
        const ids = fetchNotificationIds(t, 20);
        if (ids.length > 0) {
            const id = ids[Math.floor(Math.random() * ids.length)];
            const r = http.put(`${BASE_URL}/api/v1/notifications/${id}/read`, null, {
                headers: headers(t), tags: { name: 'hu_mark' },
            });
            dur = r.timings.duration; ok = r.status === 200;
        } else { ok = true; }
    } else if (roll < 0.85) {
        // 15%: mark all (hot user 전체 UPDATE → 1,100건 대상)
        const r = http.put(`${BASE_URL}/api/v1/notifications/read-all`, null, {
            headers: headers(t), tags: { name: 'hu_markall' },
        });
        dur = r.timings.duration; ok = r.status === 200;
    } else {
        // 15%: delete
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
    // NO sleep
}

// ============================================
// Phase 4: Sustained 400 VUs — 15분
// 시간에 따라 write 비율이 점진적으로 증가
//   0~5분: read 60% / write 40%
//   5~10분: read 30% / write 70%
//   10~15분: read 20% / write 80%
// ============================================
export function sustainedEndurance() {
    const u = vuUser();
    const t = generateJWT(u);

    // 현재 시간으로 구간 판별
    const nowSec = new Date().getTime() / 1000;
    const elapsed = Math.max(0, nowSec - __ENV.__SUSTAIN_START || 0);

    // __ITER 기반으로 구간 추정 (VU별 iteration 수 기준)
    // 대안: 시나리오 내 경과 시간은 직접 추적 불가하므로
    // __ITER를 활용 — 대략 iteration 속도로 5분/10분 경과 추정
    // 400 VUs × ~30 iter/sec/VU = ~12000 iter/sec → 5분 = ~3,600,000
    // 하지만 VU별 __ITER이므로 VU당 ~30/sec → 5분 = ~9,000 iter
    const iterPerVU = __ITER;
    let writeRatio;
    let trendDur, trendSuccess;

    if (iterPerVU < 3000) {
        // 초반 (~5분): write 40%
        writeRatio = 0.40;
        trendDur = sustain1Dur;
        trendSuccess = sustain1Success;
    } else if (iterPerVU < 6000) {
        // 중반 (~5~10분): write 70%
        writeRatio = 0.70;
        trendDur = sustain2Dur;
        trendSuccess = sustain2Success;
    } else {
        // 후반 (~10~15분): write 80%
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
    // NO sleep
}

// ============================================
// Phase 5: Post-Sustain Spike — 15분 지친 후 500 VU
// GC 누적, 커넥션 풀 고갈 상태에서 스파이크 견디는지 확인
// ============================================
export function postSustainSpike() {
    const u = rndUser();
    const t = generateJWT(u);
    const roll = Math.random();
    let dur = 0, ok = false;

    if (roll < 0.50) {
        // 50%: write (지친 상태에서 write 스파이크)
        const result = doWrite(t, 'ps');
        dur = result.dur; ok = result.ok;
    } else {
        // 50%: read
        const result = doRead(t, 'ps');
        dur = result.dur; ok = result.ok;
    }

    postSpikeDur.add(dur);
    postSpikeSuccess.add(ok);
    // NO sleep
}

export default function () { warmup(); }
