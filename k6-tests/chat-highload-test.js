// =============================================================
// chat-highload-test.js
// 채팅 시스템 고부하 스트레스 테스트 (MySQL vs MongoDB 비교용)
// =============================================================
//
// 실행 방법 (Docker):
//   MSYS_NO_PATHCONV=1 docker run --rm \
//     -v "$(pwd)/k6-tests:/scripts" \
//     --add-host=host.docker.internal:host-gateway \
//     grafana/k6:latest run /scripts/chat-highload-test.js
//
// =============================================================
// 테스트 구조 (11 Phase, 총 ~18분):
//
//   Phase 1  — Warmup (30s): JIT + 커넥션 풀 워밍업
//   Phase 2  — Send Storm (2m): 메시지 전송 + 삭제 고부하 (150 VUs)
//   Phase 3  — Read Storm (2m): 최신 목록 + 커서 동시 고부하 (150 VUs)
//   Phase 4  — Room List Stress (1.5m): 채팅방 목록 집중 부하 (100 VUs)
//   Phase 5  — WebSocket Flood (1.5m): WS STOMP 대량 연결 (100 VUs)
//   Phase 6  — Read-Write Contention (2m): 동일 방 읽기+쓰기 동시 (100 VUs)
//   Phase 7  — Sustained High Load (3m): 혼합 부하 장시간 (200 VUs)
//   Phase 8  — Spike 300 VUs (1.5m): 극한 스파이크
//   Phase 9  — Double Spike (2m): 이중 스파이크 (회복→재폭주)
//   Phase 10 — Soak Test (3m): 안정성 테스트 (80 VUs 장시간)
//   Phase 11 — Cooldown (30s): 잔여 요청 소화
// =============================================================

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL } from './lib/common.js';

const WS_URL = (BASE_URL.replace('http://', 'ws://').replace('https://', 'wss://')) + '/ws-native';

// ============================================
// 커스텀 메트릭
// ============================================

// Phase 2: Send Storm
const sendStormDur     = new Trend('hl_send_storm_duration', true);
const sendStormSuccess = new Rate('hl_send_storm_success');
const sendStormErrors  = new Counter('hl_send_storm_errors');
const deleteDur        = new Trend('hl_delete_duration', true);
const deleteSuccess    = new Rate('hl_delete_success');

// Phase 3: Read Storm
const readListDur      = new Trend('hl_read_list_duration', true);
const readCursorDur    = new Trend('hl_read_cursor_duration', true);
const readStormSuccess = new Rate('hl_read_storm_success');

// Phase 4: Room List Stress
const roomListDur      = new Trend('hl_room_list_duration', true);
const roomListSuccess  = new Rate('hl_room_list_success');

// Phase 5: WebSocket Flood
const wsConnDur        = new Trend('hl_ws_connect_duration', true);
const wsConnSuccess    = new Rate('hl_ws_connect_success');
const wsMsgSent        = new Counter('hl_ws_msg_sent');
const wsMsgRecv        = new Counter('hl_ws_msg_received');
const wsErrors         = new Counter('hl_ws_errors');

// Phase 6: Contention
const contentionReadDur  = new Trend('hl_contention_read', true);
const contentionWriteDur = new Trend('hl_contention_write', true);
const contentionSuccess  = new Rate('hl_contention_success');

// Phase 7: Sustained
const sustainedDur     = new Trend('hl_sustained_duration', true);
const sustainedSuccess = new Rate('hl_sustained_success');
const sustainedErrors  = new Counter('hl_sustained_errors');

// Phase 8: Spike
const spikeDur         = new Trend('hl_spike_duration', true);
const spikeSuccess     = new Rate('hl_spike_success');
const spikeErrors      = new Counter('hl_spike_errors');

// Phase 9: Double Spike
const dblSpikeDur      = new Trend('hl_dbl_spike_duration', true);
const dblSpikeSuccess  = new Rate('hl_dbl_spike_success');

// Phase 10: Soak
const soakDur          = new Trend('hl_soak_duration', true);
const soakSuccess      = new Rate('hl_soak_success');

// ============================================
// 설정
// ============================================
const USER_COUNT = parseInt(__ENV.USER_COUNT || '1000');
const TOP_ROOM_IDS = [64, 159, 381, 501, 747, 864, 959];
const CLUB_IDS = [64, 159, 381, 501, 747];

export const options = {
    scenarios: {
        // Phase 1: Warmup (30s)
        warmup: {
            executor: 'constant-vus',
            vus: 10,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },

        // Phase 2: Send Storm — 150 VUs, 2분
        send_storm: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 150 },
                { duration: '80s', target: 150 },
                { duration: '20s', target: 0 },
            ],
            startTime: '35s',
            exec: 'sendStorm',
            tags: { phase: 'send_storm' },
        },

        // Phase 3: Read Storm — 150 VUs, 2분
        read_storm: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 150 },
                { duration: '80s', target: 150 },
                { duration: '20s', target: 0 },
            ],
            startTime: '160s',
            exec: 'readStorm',
            tags: { phase: 'read_storm' },
        },

        // Phase 4: Room List Stress — 100 VUs, 1.5분
        room_list_stress: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '15s', target: 100 },
                { duration: '60s', target: 100 },
                { duration: '15s', target: 0 },
            ],
            startTime: '285s',
            exec: 'roomListStress',
            tags: { phase: 'room_list' },
        },

        // Phase 5: WebSocket Flood — 100 VUs, 1.5분
        ws_flood: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '15s', target: 100 },
                { duration: '60s', target: 100 },
                { duration: '15s', target: 0 },
            ],
            startTime: '380s',
            exec: 'wsFlood',
            tags: { phase: 'ws_flood' },
        },

        // Phase 6: Read-Write Contention — 100 VUs, 2분
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

        // Phase 7: Sustained High Load — 200 VUs, 3분
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

        // Phase 8: Spike 300 VUs — 1.5분
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

        // Phase 9: Double Spike — 이중 스파이크
        double_spike: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '10s', target: 200 },
                { duration: '20s', target: 200 },
                { duration: '10s', target: 10 },
                { duration: '15s', target: 10 },
                { duration: '10s', target: 250 },
                { duration: '25s', target: 250 },
                { duration: '15s', target: 5 },
                { duration: '15s', target: 5 },
            ],
            startTime: '885s',
            exec: 'doubleSpikeTest',
            tags: { phase: 'double_spike' },
        },

        // Phase 10: Soak Test — 80 VUs 3분
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
        // Send Storm
        'hl_send_storm_duration':  ['p(95)<1000', 'p(99)<2000'],
        'hl_send_storm_success':   ['rate>0.98'],
        'hl_delete_duration':      ['p(95)<500'],
        // Read Storm
        'hl_read_list_duration':   ['p(95)<500', 'p(99)<1000'],
        'hl_read_cursor_duration': ['p(95)<500', 'p(99)<1000'],
        'hl_read_storm_success':   ['rate>0.98'],
        // Room List
        'hl_room_list_duration':   ['p(95)<1000', 'p(99)<3000'],
        'hl_room_list_success':    ['rate>0.98'],
        // WebSocket
        'hl_ws_connect_success':   ['rate>0.90'],
        'hl_ws_connect_duration':  ['p(95)<3000'],
        // Contention
        'hl_contention_read':      ['p(95)<1000'],
        'hl_contention_write':     ['p(95)<1000'],
        'hl_contention_success':   ['rate>0.95'],
        // Sustained
        'hl_sustained_duration':   ['p(95)<1000', 'p(99)<3000'],
        'hl_sustained_success':    ['rate>0.95'],
        // Spike
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
// 유틸
// ============================================
function testUser(vuId) {
    const userId = (vuId % USER_COUNT) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}
function randomUser() {
    const userId = Math.floor(Math.random() * USER_COUNT) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}
function randomRoomId() { return TOP_ROOM_IDS[Math.floor(Math.random() * TOP_ROOM_IDS.length)]; }
function randomClubId() { return CLUB_IDS[Math.floor(Math.random() * CLUB_IDS.length)]; }

// ============================================
// Phase 1: Warmup
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    http.get(`${BASE_URL}/api/v1/chat/${randomRoomId()}/messages?size=5`, {
        headers: headers(token), tags: { name: 'warmup' },
    });
    sleep(0.5);
}

// ============================================
// Phase 2: Send Storm — 메시지 전송 + 삭제 고부하
// ============================================
export function sendStorm() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();

    group('send_storm', () => {
        const payload = JSON.stringify({
            text: `k6-hl-send user${user.userId} #${Math.floor(Math.random() * 100000)}`,
        });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: headers(token), tags: { name: 'hl_send' },
        });

        sendStormDur.add(res.timings.duration);
        const ok = res.status === 200;
        sendStormSuccess.add(ok);
        if (!ok) sendStormErrors.add(1);

        check(res, { 'send 200': (r) => r.status === 200 });

        // 15% 확률 삭제
        if (ok && Math.random() < 0.15) {
            try {
                const body = JSON.parse(res.body);
                const msgId = body.data.messageId;
                const delRes = http.del(`${BASE_URL}/api/v1/chat/messages/${msgId}`, null, {
                    headers: headers(token), tags: { name: 'hl_delete' },
                });
                deleteDur.add(delRes.timings.duration);
                deleteSuccess.add(delRes.status === 204);
            } catch (e) { /* ignore */ }
        }
    });
    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 3: Read Storm — 최신 목록 + 커서 동시 고부하
// ============================================
export function readStorm() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const roll = Math.random();

    if (roll < 0.6) {
        // 60%: 최신 메시지 조회
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: headers(token), tags: { name: 'hl_read_list' },
        });
        readListDur.add(res.timings.duration);
        readStormSuccess.add(res.status === 200);
    } else {
        // 40%: 커서 기반 → 2페이지 연속
        const res1 = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`, {
            headers: headers(token), tags: { name: 'hl_read_cursor_1' },
        });
        readCursorDur.add(res1.timings.duration);
        readStormSuccess.add(res1.status === 200);

        if (res1.status === 200) {
            try {
                const body = JSON.parse(res1.body);
                const d = body.data;
                if (d.hasMore && d.nextCursorId && d.nextCursorAt) {
                    const res2 = http.get(
                        `${BASE_URL}/api/v1/chat/${roomId}/messages?size=20&cursorId=${d.nextCursorId}&cursorAt=${d.nextCursorAt}`,
                        { headers: headers(token), tags: { name: 'hl_read_cursor_2' } }
                    );
                    readCursorDur.add(res2.timings.duration);
                    readStormSuccess.add(res2.status === 200);
                }
            } catch (e) { /* ignore */ }
        }
    }
    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 4: Room List Stress — 채팅방 목록 집중
// ============================================
export function roomListStress() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const clubId = randomClubId();

    const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
        headers: headers(token), tags: { name: 'hl_room_list' },
    });
    roomListDur.add(res.timings.duration);
    roomListSuccess.add(res.status === 200);
    check(res, { 'rooms 200': (r) => r.status === 200 });

    sleep(0.1 + Math.random() * 0.2);
}

// ============================================
// Phase 5: WebSocket Flood
// ============================================
export function wsFlood() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const connectStart = Date.now();

    const res = ws.connect(WS_URL, null, function (socket) {
        let connected = false;
        const MSGS_TO_SEND = 5;

        socket.on('open', function () {
            socket.send(
                'CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:0,0\n\n\0'
            );
        });

        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED')) {
                connected = true;
                wsConnDur.add(Date.now() - connectStart);
                wsConnSuccess.add(true);

                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');

                for (let i = 0; i < MSGS_TO_SEND; i++) {
                    const body = JSON.stringify({ text: 'k6-ws-hl ' + __VU + '-' + i, imageUrl: null });
                    socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
                    wsMsgSent.add(1);
                }
            }
            if (data.startsWith('MESSAGE')) wsMsgRecv.add(1);
            if (data.startsWith('ERROR')) wsErrors.add(1);
        });

        socket.on('error', function () { wsConnSuccess.add(false); wsErrors.add(1); });

        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 3000);
    });

    if (res.status !== 101) {
        wsConnSuccess.add(false);
        wsConnDur.add(Date.now() - connectStart);
    }
    sleep(0.1);
}

// ============================================
// Phase 6: Read-Write Contention — 동일 방 읽기+쓰기 동시
// ============================================
export function readWriteContention() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = TOP_ROOM_IDS[0]; // 집중 경합: 방 64

    if (Math.random() < 0.5) {
        // 읽기
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: headers(token), tags: { name: 'hl_contention_read' },
        });
        contentionReadDur.add(res.timings.duration);
        contentionSuccess.add(res.status === 200);
    } else {
        // 쓰기
        const payload = JSON.stringify({ text: `k6-contention ${user.userId}` });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: headers(token), tags: { name: 'hl_contention_write' },
        });
        contentionWriteDur.add(res.timings.duration);
        contentionSuccess.add(res.status === 200);
    }
    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 7: Sustained High Load — 혼합 장시간
//   35% list, 25% send, 20% cursor, 15% rooms, 5% delete
// ============================================
export function sustainedLoad() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const roll = Math.random();
    let ok = false;

    if (roll < 0.35) {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: headers(token), tags: { name: 'hl_sustained_list' },
        });
        sustainedDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (roll < 0.60) {
        const payload = JSON.stringify({ text: `k6-sustained ${user.userId}` });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: headers(token), tags: { name: 'hl_sustained_send' },
        });
        sustainedDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (roll < 0.80) {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`, {
            headers: headers(token), tags: { name: 'hl_sustained_cursor' },
        });
        sustainedDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (roll < 0.95) {
        const clubId = randomClubId();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
            headers: headers(token), tags: { name: 'hl_sustained_rooms' },
        });
        sustainedDur.add(res.timings.duration);
        ok = res.status === 200;
    } else {
        // 5%: 전송 후 삭제
        const payload = JSON.stringify({ text: `k6-sustained-del ${user.userId}` });
        const sendRes = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: headers(token), tags: { name: 'hl_sustained_send_del' },
        });
        sustainedDur.add(sendRes.timings.duration);
        ok = sendRes.status === 200;
        if (ok) {
            try {
                const body = JSON.parse(sendRes.body);
                http.del(`${BASE_URL}/api/v1/chat/messages/${body.data.messageId}`, null, {
                    headers: headers(token), tags: { name: 'hl_sustained_delete' },
                });
            } catch (e) { /* ignore */ }
        }
    }

    sustainedSuccess.add(ok);
    if (!ok) sustainedErrors.add(1);
    sleep(0.05 + Math.random() * 0.15);
}

// ============================================
// Phase 8: Spike 300 VUs
// ============================================
export function spikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const ops = ['list', 'send', 'cursor', 'rooms'];
    const op = ops[Math.floor(Math.random() * ops.length)];
    let ok = false;

    if (op === 'list') {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: headers(token), tags: { name: 'hl_spike_list' },
        });
        spikeDur.add(res.timings.duration); ok = res.status === 200;
    } else if (op === 'send') {
        const payload = JSON.stringify({ text: `k6-spike ${user.userId}` });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: headers(token), tags: { name: 'hl_spike_send' },
        });
        spikeDur.add(res.timings.duration); ok = res.status === 200;
    } else if (op === 'cursor') {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`, {
            headers: headers(token), tags: { name: 'hl_spike_cursor' },
        });
        spikeDur.add(res.timings.duration); ok = res.status === 200;
    } else {
        const clubId = randomClubId();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
            headers: headers(token), tags: { name: 'hl_spike_rooms' },
        });
        spikeDur.add(res.timings.duration); ok = res.status === 200;
    }

    spikeSuccess.add(ok);
    if (!ok) spikeErrors.add(1);
    sleep(0.05);
}

// ============================================
// Phase 9: Double Spike — 회복→재폭주
// ============================================
export function doubleSpikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const roll = Math.random();
    let ok = false;

    if (roll < 0.4) {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: headers(token), tags: { name: 'hl_dbl_list' },
        });
        dblSpikeDur.add(res.timings.duration); ok = res.status === 200;
    } else if (roll < 0.7) {
        const payload = JSON.stringify({ text: `k6-dbl ${user.userId}` });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: headers(token), tags: { name: 'hl_dbl_send' },
        });
        dblSpikeDur.add(res.timings.duration); ok = res.status === 200;
    } else {
        const clubId = randomClubId();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
            headers: headers(token), tags: { name: 'hl_dbl_rooms' },
        });
        dblSpikeDur.add(res.timings.duration); ok = res.status === 200;
    }

    dblSpikeSuccess.add(ok);
    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 10: Soak Test — 80 VUs 장시간 안정성
// ============================================
export function soakTest() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const roll = Math.random();
    let ok = false;

    if (roll < 0.45) {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: headers(token), tags: { name: 'hl_soak_list' },
        });
        soakDur.add(res.timings.duration); ok = res.status === 200;
    } else if (roll < 0.75) {
        const payload = JSON.stringify({ text: `k6-soak ${user.userId}` });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: headers(token), tags: { name: 'hl_soak_send' },
        });
        soakDur.add(res.timings.duration); ok = res.status === 200;
    } else if (roll < 0.90) {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`, {
            headers: headers(token), tags: { name: 'hl_soak_cursor' },
        });
        soakDur.add(res.timings.duration); ok = res.status === 200;
    } else {
        const clubId = randomClubId();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
            headers: headers(token), tags: { name: 'hl_soak_rooms' },
        });
        soakDur.add(res.timings.duration); ok = res.status === 200;
    }

    soakSuccess.add(ok);
    sleep(0.1 + Math.random() * 0.2);
}

// ============================================
// 기본 함수 (fallback)
// ============================================
export default function () {
    warmup();
}
