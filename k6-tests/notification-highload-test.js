// =============================================================
// notification-highload-test.js
// 알림 시스템 고부하 스트레스 테스트
// =============================================================
//
// 실행 방법 (Docker):
//   MSYS_NO_PATHCONV=1 docker run --rm \
//     -v "$(pwd)/k6-tests:/scripts" \
//     --add-host=host.docker.internal:host-gateway \
//     grafana/k6:latest run /scripts/notification-highload-test.js
//
// 사전 준비:
//   mysql -h 127.0.0.1 -P 3340 -u root -p onlyone < k6-tests/seed-notification-highload.sql
//
// 환경 변수:
//   BASE_URL     (기본: http://host.docker.internal:8080)
//   USER_COUNT   (기본: 1000) — 테스트 유저 수 (시드 데이터 1~1000)
//   JWT_SECRET   (기본: 로컬 시크릿)
//
// =============================================================
// 테스트 구조 (11 Phase, 총 ~18분):
//
//   Phase 1  — Warmup (30s): JIT + 커넥션 풀 워밍업
//   Phase 2  — Read Storm (2m): 목록+읽지않은개수 동시 고부하 (150 VUs)
//   Phase 3  — Write Storm (2m): 읽음+삭제+전체읽음 동시 쓰기 (100 VUs)
//   Phase 4  — Hot User Deep Paging (1.5m): 1,100건 유저 깊은 페이지네이션 (50 VUs)
//   Phase 5  — SSE Flood (1.5m): SSE 연결 대량 동시 생성 (80 VUs)
//   Phase 6  — Read-Write Contention (2m): 동일 유저에 읽기+쓰기 동시 (100 VUs)
//   Phase 7  — Sustained High Load (3m): 혼합 부하 장시간 유지 (200 VUs)
//   Phase 8  — Spike 300 VUs (1.5m): 극한 스파이크
//   Phase 9  — Double Spike (2m): 이중 스파이크 (회복 후 재폭주)
//   Phase 10 — Soak Test (3m): 안정성 테스트 (중간 부하 장시간)
//   Phase 11 — Cooldown (30s): 잔여 요청 소화
// =============================================================

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, fetchNotificationIds, connectSSE } from './lib/common.js';

// ============================================
// 커스텀 메트릭
// ============================================

// --- 읽기 ---
const readStormListDur    = new Trend('hl_read_storm_list',       true);
const readStormUnreadDur  = new Trend('hl_read_storm_unread',     true);
const readStormSuccess    = new Rate('hl_read_storm_success');

// --- 쓰기 ---
const writeStormMarkDur   = new Trend('hl_write_storm_mark',      true);
const writeStormDelDur    = new Trend('hl_write_storm_delete',    true);
const writeStormAllDur    = new Trend('hl_write_storm_markall',   true);
const writeStormSuccess   = new Rate('hl_write_storm_success');

// --- 핫 유저 딥 페이징 ---
const deepPageDur         = new Trend('hl_deep_page_duration',    true);
const deepPageCount       = new Counter('hl_deep_page_count');
const deepPageSuccess     = new Rate('hl_deep_page_success');

// --- SSE 대량 연결 ---
const sseFloodDur         = new Trend('hl_sse_flood_duration',    true);
const sseFloodSuccess     = new Rate('hl_sse_flood_success');
const sseFloodErrors      = new Counter('hl_sse_flood_errors');

// --- 읽기-쓰기 경합 ---
const contentionReadDur   = new Trend('hl_contention_read',       true);
const contentionWriteDur  = new Trend('hl_contention_write',      true);
const contentionSuccess   = new Rate('hl_contention_success');

// --- 장시간 고부하 ---
const sustainedDur        = new Trend('hl_sustained_duration',    true);
const sustainedSuccess    = new Rate('hl_sustained_success');
const sustainedErrors     = new Counter('hl_sustained_errors');

// --- 스파이크 ---
const spikeDur            = new Trend('hl_spike_duration',        true);
const spikeSuccess        = new Rate('hl_spike_success');
const spikeErrors         = new Counter('hl_spike_errors');

// --- 이중 스파이크 ---
const dblSpikeDur         = new Trend('hl_dbl_spike_duration',    true);
const dblSpikeSuccess     = new Rate('hl_dbl_spike_success');

// --- Soak ---
const soakDur             = new Trend('hl_soak_duration',         true);
const soakSuccess         = new Rate('hl_soak_success');

// ============================================
// 테스트 설정
// ============================================
const USER_COUNT = parseInt(__ENV.USER_COUNT || '1000');
const HOT_USER_MAX = 10;  // user 1~10은 1,100건 보유

export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: 10,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },

        // Phase 2: Read Storm — 읽기 폭풍 (150 VUs)
        read_storm: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 150 },
                { duration: '80s', target: 150 },
                { duration: '20s', target: 0 },
            ],
            startTime: '35s',
            exec: 'readStorm',
            tags: { phase: 'read_storm' },
        },

        // Phase 3: Write Storm — 쓰기 폭풍 (100 VUs)
        write_storm: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 100 },
                { duration: '80s', target: 100 },
                { duration: '20s', target: 0 },
            ],
            startTime: '160s',
            exec: 'writeStorm',
            tags: { phase: 'write_storm' },
        },

        // Phase 4: Hot User Deep Paging (50 VUs, 핫 유저만)
        deep_paging: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '15s', target: 50 },
                { duration: '60s', target: 50 },
                { duration: '15s', target: 0 },
            ],
            startTime: '285s',
            exec: 'deepPaging',
            tags: { phase: 'deep_paging' },
        },

        // Phase 5: SSE Flood — 대량 SSE 동시 연결 (80 VUs)
        sse_flood: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '15s', target: 80 },
                { duration: '60s', target: 80 },
                { duration: '15s', target: 0 },
            ],
            startTime: '380s',
            exec: 'sseFlood',
            tags: { phase: 'sse_flood' },
        },

        // Phase 6: Read-Write Contention — 동일 유저 읽기+쓰기 동시 (100 VUs)
        contention: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 100 },
                { duration: '80s', target: 100 },
                { duration: '20s', target: 0 },
            ],
            startTime: '475s',
            exec: 'readWriteContention',
            tags: { phase: 'contention' },
        },

        // Phase 7: Sustained High Load — 장시간 고부하 (200 VUs, 3분)
        sustained: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '30s', target: 200 },
                { duration: '120s', target: 200 },
                { duration: '30s', target: 0 },
            ],
            startTime: '600s',
            exec: 'sustainedLoad',
            tags: { phase: 'sustained' },
        },

        // Phase 8: Spike 300 VUs — 극한 스파이크
        spike_300: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '10s', target: 300 },
                { duration: '40s', target: 300 },
                { duration: '20s', target: 5 },
                { duration: '20s', target: 5 },
            ],
            startTime: '790s',
            exec: 'spikeTest',
            tags: { phase: 'spike_300' },
        },

        // Phase 9: Double Spike — 이중 스파이크 (회복→재폭주)
        double_spike: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '10s', target: 200 },
                { duration: '20s', target: 200 },
                { duration: '10s', target: 10 },   // 급감
                { duration: '15s', target: 10 },    // 회복 시간
                { duration: '10s', target: 250 },   // 재폭주
                { duration: '25s', target: 250 },
                { duration: '15s', target: 5 },
                { duration: '15s', target: 5 },
            ],
            startTime: '885s',
            exec: 'doubleSpikeTest',
            tags: { phase: 'double_spike' },
        },

        // Phase 10: Soak Test — 안정성 테스트 (80 VUs 장시간)
        soak: {
            executor: 'constant-vus',
            vus: 80,
            duration: '180s',
            startTime: '1010s',
            exec: 'soakTest',
            tags: { phase: 'soak' },
        },

        // Phase 11: Cooldown
        cooldown: {
            executor: 'constant-vus',
            vus: 3,
            duration: '30s',
            startTime: '1195s',
            exec: 'warmup',
            tags: { phase: 'cooldown' },
        },
    },

    thresholds: {
        // Read Storm
        'hl_read_storm_list':      ['p(95)<800', 'p(99)<2000'],
        'hl_read_storm_unread':    ['p(95)<500', 'p(99)<1000'],
        'hl_read_storm_success':   ['rate>0.98'],
        // Write Storm
        'hl_write_storm_mark':     ['p(95)<500'],
        'hl_write_storm_delete':   ['p(95)<500'],
        'hl_write_storm_markall':  ['p(95)<2000'],
        'hl_write_storm_success':  ['rate>0.95'],
        // Deep Paging
        'hl_deep_page_duration':   ['p(95)<1000'],
        'hl_deep_page_success':    ['rate>0.98'],
        // SSE Flood
        'hl_sse_flood_success':    ['rate>0.90'],
        // Contention
        'hl_contention_read':      ['p(95)<1000'],
        'hl_contention_write':     ['p(95)<1000'],
        'hl_contention_success':   ['rate>0.95'],
        // Sustained
        'hl_sustained_duration':   ['p(95)<1000', 'p(99)<3000'],
        'hl_sustained_success':    ['rate>0.95'],
        // Spike 300
        'hl_spike_duration':       ['p(95)<3000'],
        'hl_spike_success':        ['rate>0.85'],
        // Double Spike
        'hl_dbl_spike_success':    ['rate>0.85'],
        // Soak
        'hl_soak_duration':        ['p(95)<500', 'p(99)<1500'],
        'hl_soak_success':         ['rate>0.98'],
    },
};

// ============================================
// 유저 유틸
// ============================================
function testUser(vuId) {
    const userId = (vuId % USER_COUNT) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

function randomUser() {
    const userId = Math.floor(Math.random() * USER_COUNT) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

function hotUser() {
    const userId = Math.floor(Math.random() * HOT_USER_MAX) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

// ============================================
// Phase 1: Warmup
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    http.get(`${BASE_URL}/api/v1/notifications?size=5`, {
        headers: headers(token), tags: { name: 'warmup_list' },
    });
    http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: headers(token), tags: { name: 'warmup_unread' },
    });
    sleep(0.3);
}

// ============================================
// Phase 2: Read Storm — 150 VUs 읽기 폭풍
// ============================================
export function readStorm() {
    const user = testUser(__VU);
    const token = generateJWT(user);

    // 목록 조회
    const listRes = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
        headers: headers(token), tags: { name: 'rs_list' },
    });
    readStormListDur.add(listRes.timings.duration);
    readStormSuccess.add(listRes.status === 200);

    // 커서 페이지네이션 (2번째 페이지)
    if (listRes.status === 200) {
        try {
            const body = JSON.parse(listRes.body);
            const data = body.data || body;
            if (data.hasMore && data.cursor) {
                const res2 = http.get(
                    `${BASE_URL}/api/v1/notifications?size=20&cursor=${data.cursor}`, {
                        headers: headers(token), tags: { name: 'rs_list_p2' },
                    });
                readStormListDur.add(res2.timings.duration);
                readStormSuccess.add(res2.status === 200);
            }
        } catch (e) { /* ignore */ }
    }

    // 읽지않은 개수
    const unreadRes = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: headers(token), tags: { name: 'rs_unread' },
    });
    readStormUnreadDur.add(unreadRes.timings.duration);
    readStormSuccess.add(unreadRes.status === 200);

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 3: Write Storm — 100 VUs 쓰기 폭풍
// ============================================
export function writeStorm() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roll = Math.random();

    if (roll < 0.50) {
        // 50%: 단건 읽음
        const ids = fetchNotificationIds(token, 10);
        if (ids.length > 0) {
            const targetId = ids[Math.floor(Math.random() * ids.length)];
            const res = http.put(
                `${BASE_URL}/api/v1/notifications/${targetId}/read`, null,
                { headers: headers(token), tags: { name: 'ws_mark_read' } }
            );
            writeStormMarkDur.add(res.timings.duration);
            writeStormSuccess.add(res.status === 200);
        }
    } else if (roll < 0.80) {
        // 30%: 삭제
        const ids = fetchNotificationIds(token, 10);
        if (ids.length > 0) {
            const targetId = ids[Math.floor(Math.random() * ids.length)];
            const res = http.del(
                `${BASE_URL}/api/v1/notifications/${targetId}`, null,
                { headers: headers(token), tags: { name: 'ws_delete' } }
            );
            writeStormDelDur.add(res.timings.duration);
            writeStormSuccess.add(res.status === 200);
        }
    } else {
        // 20%: 전체 읽음 (가장 무거운 연산)
        const res = http.put(
            `${BASE_URL}/api/v1/notifications/read-all`, null,
            { headers: headers(token), tags: { name: 'ws_mark_all' } }
        );
        writeStormAllDur.add(res.timings.duration);
        writeStormSuccess.add(res.status === 200);
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 4: Hot User Deep Paging
// user 1~10은 1,100건 보유 → 5~10 페이지 깊이까지 탐색
// ============================================
export function deepPaging() {
    const user = hotUser();
    const token = generateJWT(user);
    let cursor = null;
    let pageNum = 0;
    const maxPages = 10;

    while (pageNum < maxPages) {
        const url = cursor
            ? `${BASE_URL}/api/v1/notifications?size=20&cursor=${cursor}`
            : `${BASE_URL}/api/v1/notifications?size=20`;

        const res = http.get(url, {
            headers: headers(token),
            tags: { name: `dp_page_${Math.min(pageNum, 5)}` },
        });

        deepPageDur.add(res.timings.duration);
        deepPageCount.add(1);
        deepPageSuccess.add(res.status === 200);
        pageNum++;

        if (res.status !== 200) break;

        try {
            const body = JSON.parse(res.body);
            const data = body.data || body;
            if (!data.hasMore || !data.cursor) break;
            cursor = data.cursor;
        } catch (e) { break; }

        sleep(0.02);
    }

    sleep(0.1);
}

// ============================================
// Phase 5: SSE Flood — 80 VUs 동시 SSE 연결
// ============================================
export function sseFlood() {
    const user = testUser(__VU);

    const sseResult = connectSSE(user, '5s');
    sseFloodDur.add(sseResult.duration);
    sseFloodSuccess.add(sseResult.success);
    if (!sseResult.success) sseFloodErrors.add(1);

    check(null, {
        'sse_flood connected': () => sseResult.connectedEvent,
    });

    // SSE 연결 직후 즉시 API 호출 (SSE recovery와 경합)
    const token = generateJWT(user);
    const listRes = http.get(`${BASE_URL}/api/v1/notifications?size=10`, {
        headers: headers(token), tags: { name: 'sse_flood_list' },
    });
    sseFloodSuccess.add(listRes.status === 200);

    sleep(0.5);
}

// ============================================
// Phase 6: Read-Write Contention
// 소수 유저 (1~50)에 읽기+쓰기를 동시에 집중
// ============================================
export function readWriteContention() {
    // 동일 유저 풀 (1~50)에서 선택 — 경합 극대화
    const userId = ((__VU - 1) % 50) + 1;
    const user = { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
    const token = generateJWT(user);
    const roll = Math.random();

    if (roll < 0.40) {
        // 40%: 목록 조회
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(token), tags: { name: 'ct_list' },
        });
        contentionReadDur.add(res.timings.duration);
        contentionSuccess.add(res.status === 200);

    } else if (roll < 0.60) {
        // 20%: 읽지않은 개수
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(token), tags: { name: 'ct_unread' },
        });
        contentionReadDur.add(res.timings.duration);
        contentionSuccess.add(res.status === 200);

    } else if (roll < 0.80) {
        // 20%: 단건 읽음
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.put(
                `${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null,
                { headers: headers(token), tags: { name: 'ct_mark' } }
            );
            contentionWriteDur.add(res.timings.duration);
            contentionSuccess.add(res.status === 200);
        }

    } else if (roll < 0.90) {
        // 10%: 전체 읽음
        const res = http.put(
            `${BASE_URL}/api/v1/notifications/read-all`, null,
            { headers: headers(token), tags: { name: 'ct_mark_all' } }
        );
        contentionWriteDur.add(res.timings.duration);
        contentionSuccess.add(res.status === 200);

    } else {
        // 10%: 삭제
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.del(
                `${BASE_URL}/api/v1/notifications/${ids[0]}`, null,
                { headers: headers(token), tags: { name: 'ct_delete' } }
            );
            contentionWriteDur.add(res.timings.duration);
            contentionSuccess.add(res.status === 200);
        }
    }

    sleep(0.02 + Math.random() * 0.05);
}

// ============================================
// Phase 7: Sustained High Load — 200 VUs 3분
// ============================================
export function sustainedLoad() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roll = Math.random();
    let ok = false;
    let dur = 0;

    if (roll < 0.45) {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(token), tags: { name: 'sus_list' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;

    } else if (roll < 0.70) {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(token), tags: { name: 'sus_unread' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;

    } else if (roll < 0.85) {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.put(
                `${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null,
                { headers: headers(token), tags: { name: 'sus_mark' } }
            );
            dur = res.timings.duration;
            ok = res.status === 200;
        } else { ok = true; }

    } else if (roll < 0.93) {
        const res = http.put(
            `${BASE_URL}/api/v1/notifications/read-all`, null,
            { headers: headers(token), tags: { name: 'sus_mark_all' } }
        );
        dur = res.timings.duration;
        ok = res.status === 200;

    } else {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.del(
                `${BASE_URL}/api/v1/notifications/${ids[0]}`, null,
                { headers: headers(token), tags: { name: 'sus_delete' } }
            );
            dur = res.timings.duration;
            ok = res.status === 200;
        } else { ok = true; }
    }

    sustainedDur.add(dur);
    sustainedSuccess.add(ok);
    if (!ok) sustainedErrors.add(1);

    sleep(0.02 + Math.random() * 0.08);
}

// ============================================
// Phase 8: Spike 300 VUs
// ============================================
export function spikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const ops = ['list', 'unread', 'read', 'mark_all'];
    const op = ops[Math.floor(Math.random() * ops.length)];
    let ok = false;
    let dur = 0;

    if (op === 'list') {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(token), tags: { name: 'sp_list' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;

    } else if (op === 'unread') {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(token), tags: { name: 'sp_unread' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;

    } else if (op === 'read') {
        const ids = fetchNotificationIds(token, 3);
        if (ids.length > 0) {
            const res = http.put(
                `${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null,
                { headers: headers(token), tags: { name: 'sp_mark' } }
            );
            dur = res.timings.duration;
            ok = res.status === 200;
        } else { ok = true; }

    } else {
        const res = http.put(
            `${BASE_URL}/api/v1/notifications/read-all`, null,
            { headers: headers(token), tags: { name: 'sp_mark_all' } }
        );
        dur = res.timings.duration;
        ok = res.status === 200;
    }

    spikeDur.add(dur);
    spikeSuccess.add(ok);
    if (!ok) spikeErrors.add(1);
    sleep(0.02);
}

// ============================================
// Phase 9: Double Spike — 이중 스파이크
// ============================================
export function doubleSpikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const roll = Math.random();
    let ok = false;
    let dur = 0;

    if (roll < 0.50) {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(token), tags: { name: 'ds_list' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;

    } else if (roll < 0.80) {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(token), tags: { name: 'ds_unread' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;

    } else {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.put(
                `${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null,
                { headers: headers(token), tags: { name: 'ds_mark' } }
            );
            dur = res.timings.duration;
            ok = res.status === 200;
        } else { ok = true; }
    }

    dblSpikeDur.add(dur);
    dblSpikeSuccess.add(ok);
    sleep(0.02 + Math.random() * 0.03);
}

// ============================================
// Phase 10: Soak Test — 80 VUs 3분 안정성
// ============================================
export function soakTest() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roll = Math.random();
    let ok = false;
    let dur = 0;

    if (roll < 0.55) {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(token), tags: { name: 'soak_list' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;

    } else if (roll < 0.80) {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(token), tags: { name: 'soak_unread' },
        });
        dur = res.timings.duration;
        ok = res.status === 200;

    } else if (roll < 0.92) {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.put(
                `${BASE_URL}/api/v1/notifications/${ids[0]}/read`, null,
                { headers: headers(token), tags: { name: 'soak_mark' } }
            );
            dur = res.timings.duration;
            ok = res.status === 200;
        } else { ok = true; }

    } else {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const res = http.del(
                `${BASE_URL}/api/v1/notifications/${ids[0]}`, null,
                { headers: headers(token), tags: { name: 'soak_delete' } }
            );
            dur = res.timings.duration;
            ok = res.status === 200;
        } else { ok = true; }
    }

    soakDur.add(dur);
    soakSuccess.add(ok);
    sleep(0.05 + Math.random() * 0.15);
}

// ============================================
// 기본 함수
// ============================================
export default function () {
    warmup();
}
