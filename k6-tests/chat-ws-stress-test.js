// =============================================================
// chat-ws-stress-test.js
// WebSocket STOMP 전용 고부하 스트레스 테스트
// =============================================================
//
// 실행 방법 (Docker):
//   MSYS_NO_PATHCONV=1 docker run --rm \
//     --network host \
//     -v "$(pwd)/k6-tests:/scripts" \
//     grafana/k6:latest run /scripts/chat-ws-stress-test.js
//
// =============================================================
// 테스트 구조 (8 Phase, 총 ~15분):
//
//   Phase 1  — Warmup (30s): STOMP 연결 기본 확인 (10 VUs)
//   Phase 2  — Connection Ramp (2m): 점진적 동시 접속 증가 (→300 VUs)
//   Phase 3  — Message Flood (2m): 대량 메시지 전송+수신 (200 VUs, 20msg/session)
//   Phase 4  — Multi-Room Subscribe (2m): 다중 구독 (150 VUs, 5방 동시 구독)
//   Phase 5  — Long Session (3m): 장시간 연결 유지 + 주기적 전송 (100 VUs, 15s 세션)
//   Phase 6  — Spike Connect (1m): 500 VUs 스파이크 동시 접속
//   Phase 7  — Reconnect Storm (1.5m): 빠른 연결/해제 반복 (200 VUs)
//   Phase 8  — Mixed (2m): HTTP + WS 혼합 부하 (200 VUs)
// =============================================================

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL } from './lib/common.js';

const WS_URL = (BASE_URL.replace('http://', 'ws://').replace('https://', 'wss://')) + '/ws-native';

// ============================================
// 데이터 설정
// userId 1~1000이 가입된 7개 방 (각 1,000명)
// ============================================
const VALID_USER_COUNT = 1000;
const VALID_ROOM_IDS   = [64, 159, 381, 501, 747, 864, 959];

// ============================================
// 커스텀 메트릭
// ============================================

// Phase 2: Connection Ramp
const connRampDur      = new Trend('ws_conn_ramp_duration', true);
const connRampSuccess  = new Rate('ws_conn_ramp_success');
const connRampErrors   = new Counter('ws_conn_ramp_errors');

// Phase 3: Message Flood
const msgFloodDur      = new Trend('ws_msg_flood_duration', true);
const msgFloodSuccess  = new Rate('ws_msg_flood_success');
const msgFloodSent     = new Counter('ws_msg_flood_sent');
const msgFloodRecv     = new Counter('ws_msg_flood_received');

// Phase 4: Multi-Room
const multiRoomDur     = new Trend('ws_multi_room_duration', true);
const multiRoomSuccess = new Rate('ws_multi_room_success');
const multiRoomSubs    = new Counter('ws_multi_room_subs');
const multiRoomRecv    = new Counter('ws_multi_room_received');

// Phase 5: Long Session
const longSessDur      = new Trend('ws_long_sess_duration', true);
const longSessSuccess  = new Rate('ws_long_sess_success');
const longSessSent     = new Counter('ws_long_sess_sent');
const longSessRecv     = new Counter('ws_long_sess_received');

// Phase 6: Spike
const spikeDur         = new Trend('ws_spike_duration', true);
const spikeSuccess     = new Rate('ws_spike_success');
const spikeErrors      = new Counter('ws_spike_errors');

// Phase 7: Reconnect Storm
const reconnDur        = new Trend('ws_reconn_duration', true);
const reconnSuccess    = new Rate('ws_reconn_success');
const reconnCount      = new Counter('ws_reconn_count');

// Phase 8: Mixed
const mixedWsDur       = new Trend('ws_mixed_ws_duration', true);
const mixedHttpDur     = new Trend('ws_mixed_http_duration', true);
const mixedSuccess     = new Rate('ws_mixed_success');

// ============================================
// 시나리오
// ============================================
export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: 10,
            duration: '30s',
            exec: 'wsWarmup',
            tags: { phase: 'warmup' },
        },

        // Phase 2: Connection Ramp — 300 VUs 점진 증가
        conn_ramp: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '30s', target: 100 },
                { duration: '30s', target: 200 },
                { duration: '30s', target: 300 },
                { duration: '30s', target: 0 },
            ],
            startTime: '35s',
            exec: 'connectionRamp',
            tags: { phase: 'conn_ramp' },
        },

        // Phase 3: Message Flood — 200 VUs, 20msg/세션
        msg_flood: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '20s', target: 200 },
                { duration: '80s', target: 200 },
                { duration: '20s', target: 0 },
            ],
            startTime: '160s',
            exec: 'messageFlood',
            tags: { phase: 'msg_flood' },
        },

        // Phase 4: Multi-Room Subscribe — 150 VUs, 5방 동시 구독
        multi_room: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 150 },
                { duration: '80s', target: 150 },
                { duration: '20s', target: 0 },
            ],
            startTime: '285s',
            exec: 'multiRoomSubscribe',
            tags: { phase: 'multi_room' },
        },

        // Phase 5: Long Session — 100 VUs, 15초 세션
        long_session: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 100 },
                { duration: '140s', target: 100 },
                { duration: '20s', target: 0 },
            ],
            startTime: '410s',
            exec: 'longSession',
            tags: { phase: 'long_session' },
        },

        // Phase 6: Spike — 500 VUs 폭발
        spike: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '5s', target: 500 },
                { duration: '30s', target: 500 },
                { duration: '10s', target: 100 },
                { duration: '15s', target: 0 },
            ],
            startTime: '600s',
            exec: 'spikeConnect',
            tags: { phase: 'spike' },
        },

        // Phase 7: Reconnect Storm — 200 VUs 빠른 재접속
        reconn_storm: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '15s', target: 200 },
                { duration: '60s', target: 200 },
                { duration: '15s', target: 0 },
            ],
            startTime: '665s',
            exec: 'reconnectStorm',
            tags: { phase: 'reconn_storm' },
        },

        // Phase 8: Mixed HTTP + WS — 200 VUs
        mixed: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '20s', target: 200 },
                { duration: '80s', target: 200 },
                { duration: '20s', target: 0 },
            ],
            startTime: '760s',
            exec: 'mixedLoad',
            tags: { phase: 'mixed' },
        },
    },

    thresholds: {
        // Phase 2: Connection Ramp
        'ws_conn_ramp_duration':   ['p(95)<2000'],
        'ws_conn_ramp_success':    ['rate>0.90'],
        // Phase 3: Message Flood
        'ws_msg_flood_duration':   ['p(95)<3000'],
        'ws_msg_flood_success':    ['rate>0.90'],
        // Phase 4: Multi-Room
        'ws_multi_room_duration':  ['p(95)<5000'],
        'ws_multi_room_success':   ['rate>0.90'],
        // Phase 5: Long Session
        'ws_long_sess_duration':   ['p(95)<18000'],
        'ws_long_sess_success':    ['rate>0.90'],
        // Phase 6: Spike
        'ws_spike_duration':       ['p(95)<5000'],
        'ws_spike_success':        ['rate>0.80'],
        // Phase 7: Reconnect Storm
        'ws_reconn_duration':      ['p(95)<2000'],
        'ws_reconn_success':       ['rate>0.90'],
        // Phase 8: Mixed
        'ws_mixed_success':        ['rate>0.90'],
    },
};

// ============================================
// 유틸 — userId 1~1000만 사용 (방 멤버십 보장)
// ============================================
function testUser(vuId) {
    const userId = (vuId % VALID_USER_COUNT) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}
function randomUser() {
    const userId = Math.floor(Math.random() * VALID_USER_COUNT) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}
function randomRoomId() { return VALID_ROOM_IDS[Math.floor(Math.random() * VALID_ROOM_IDS.length)]; }
function randomRoomIds(count) {
    const shuffled = VALID_ROOM_IDS.slice().sort(() => Math.random() - 0.5);
    return shuffled.slice(0, Math.min(count, shuffled.length));
}

// ============================================
// Phase 1: Warmup
// ============================================
export function wsWarmup() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const start = Date.now();

    const res = ws.connect(WS_URL, null, function (socket) {
        socket.on('open', function () {
            socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:0,0\n\n\0');
        });
        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED')) {
                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');
                const body = JSON.stringify({ text: 'warmup-' + __VU, imageUrl: null });
                socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
            }
        });
        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 2000);
    });
    sleep(0.5);
}

// ============================================
// Phase 2: Connection Ramp — 점진적 동시 접속 300까지
// ============================================
export function connectionRamp() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const start = Date.now();
    let connected = false;

    const res = ws.connect(WS_URL, null, function (socket) {
        socket.on('open', function () {
            socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:0,0\n\n\0');
        });
        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED')) {
                connected = true;
                connRampDur.add(Date.now() - start);
                connRampSuccess.add(true);
                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');
                const body = JSON.stringify({ text: 'ramp-' + __VU, imageUrl: null });
                socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
            }
            if (data.startsWith('ERROR')) connRampErrors.add(1);
        });
        socket.on('error', function () { connRampSuccess.add(false); connRampErrors.add(1); });
        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 3000);
    });

    if (res.status !== 101) {
        connRampSuccess.add(false);
        connRampDur.add(Date.now() - start);
    }
    sleep(0.1);
}

// ============================================
// Phase 3: Message Flood — 세션당 20개 메시지 폭풍 전송
// ============================================
export function messageFlood() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const start = Date.now();
    let connected = false;
    const MSGS = 20;

    const res = ws.connect(WS_URL, null, function (socket) {
        socket.on('open', function () {
            socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:0,0\n\n\0');
        });
        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED') && !connected) {
                connected = true;
                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');

                for (let i = 0; i < MSGS; i++) {
                    const body = JSON.stringify({ text: 'flood-' + __VU + '-' + i + '-' + Date.now(), imageUrl: null });
                    socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
                    msgFloodSent.add(1);
                }
            }
            if (data.startsWith('MESSAGE')) msgFloodRecv.add(1);
        });
        socket.on('error', function () { msgFloodSuccess.add(false); });
        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 5000);
    });

    msgFloodDur.add(Date.now() - start);
    msgFloodSuccess.add(res.status === 101);
    sleep(0.1);
}

// ============================================
// Phase 4: Multi-Room Subscribe — 5개 방 동시 구독
// ============================================
export function multiRoomSubscribe() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const rooms = randomRoomIds(5);
    const start = Date.now();
    let connected = false;

    const res = ws.connect(WS_URL, null, function (socket) {
        socket.on('open', function () {
            socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:0,0\n\n\0');
        });
        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED') && !connected) {
                connected = true;
                // 5개 방 동시 구독
                for (let i = 0; i < rooms.length; i++) {
                    socket.send('SUBSCRIBE\nid:sub-' + i + '\ndestination:/sub/chat/' + rooms[i] + '/messages\n\n\0');
                    multiRoomSubs.add(1);
                }
                // 각 방에 1개씩 메시지 전송
                for (let i = 0; i < rooms.length; i++) {
                    const body = JSON.stringify({ text: 'multi-' + __VU + '-room' + rooms[i], imageUrl: null });
                    socket.send('SEND\ndestination:/pub/chat/' + rooms[i] + '/messages\ncontent-type:application/json\n\n' + body + '\0');
                }
            }
            if (data.startsWith('MESSAGE')) multiRoomRecv.add(1);
        });
        socket.on('error', function () { multiRoomSuccess.add(false); });
        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 5000);
    });

    multiRoomDur.add(Date.now() - start);
    multiRoomSuccess.add(res.status === 101);
    sleep(0.2);
}

// ============================================
// Phase 5: Long Session — 15초 유지하며 2초마다 메시지
// ============================================
export function longSession() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const start = Date.now();
    let connected = false;
    let msgIdx = 0;
    let sendInterval = null;

    const res = ws.connect(WS_URL, null, function (socket) {
        socket.on('open', function () {
            socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:10000,10000\n\n\0');
        });
        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED') && !connected) {
                connected = true;
                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');

                // 2초마다 메시지 전송 (7~8회)
                sendInterval = socket.setInterval(function () {
                    const body = JSON.stringify({ text: 'long-' + __VU + '-' + (msgIdx++), imageUrl: null });
                    socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
                    longSessSent.add(1);
                }, 2000);
            }
            if (data.startsWith('MESSAGE')) longSessRecv.add(1);
        });
        socket.on('error', function () { longSessSuccess.add(false); });
        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 15000);
    });

    longSessDur.add(Date.now() - start);
    longSessSuccess.add(res.status === 101);
    sleep(0.5);
}

// ============================================
// Phase 6: Spike — 500 VUs 동시 접속 폭발
// ============================================
export function spikeConnect() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const start = Date.now();
    let connected = false;

    const res = ws.connect(WS_URL, null, function (socket) {
        socket.on('open', function () {
            socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:0,0\n\n\0');
        });
        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED')) {
                connected = true;
                spikeDur.add(Date.now() - start);
                spikeSuccess.add(true);
                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');
                const body = JSON.stringify({ text: 'spike-' + __VU, imageUrl: null });
                socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
            }
            if (data.startsWith('ERROR')) spikeErrors.add(1);
        });
        socket.on('error', function () { spikeSuccess.add(false); spikeErrors.add(1); });
        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 3000);
    });

    if (res.status !== 101) {
        spikeSuccess.add(false);
        spikeDur.add(Date.now() - start);
    }
    sleep(0.05);
}

// ============================================
// Phase 7: Reconnect Storm — 빠른 연결/해제 반복
// ============================================
export function reconnectStorm() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();

    // 1회 반복 = 빠른 연결 → 구독 → 즉시 해제
    const start = Date.now();
    let connected = false;

    const res = ws.connect(WS_URL, null, function (socket) {
        socket.on('open', function () {
            socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:0,0\n\n\0');
        });
        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED') && !connected) {
                connected = true;
                reconnDur.add(Date.now() - start);
                reconnSuccess.add(true);
                reconnCount.add(1);

                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');
                // 즉시 disconnect
                socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
                socket.close();
            }
            if (data.startsWith('ERROR')) { reconnSuccess.add(false); }
        });
        socket.on('error', function () { reconnSuccess.add(false); });
        // 안전 타임아웃
        socket.setTimeout(function () { socket.close(); }, 2000);
    });

    if (res.status !== 101) {
        reconnSuccess.add(false);
        reconnDur.add(Date.now() - start);
    }
    sleep(0.05);
}

// ============================================
// Phase 8: Mixed HTTP + WebSocket 동시 부하
// ============================================
export function mixedLoad() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();

    // 50% HTTP, 50% WebSocket
    if (Math.random() < 0.5) {
        // HTTP: 메시지 전송 또는 조회
        const start = Date.now();
        let res;
        if (Math.random() < 0.4) {
            // 전송
            res = http.post(
                `${BASE_URL}/api/v1/chat/${roomId}/messages`,
                JSON.stringify({ text: 'mixed-http-' + __VU, imageUrl: null }),
                { headers: headers(token), tags: { name: 'mixed_http_send' } }
            );
        } else {
            // 조회
            res = http.get(
                `${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`,
                { headers: headers(token), tags: { name: 'mixed_http_read' } }
            );
        }
        mixedHttpDur.add(Date.now() - start);
        mixedSuccess.add(res.status === 200 || res.status === 201);
    } else {
        // WebSocket 세션
        const start = Date.now();
        const res = ws.connect(WS_URL, null, function (socket) {
            let connected = false;
            socket.on('open', function () {
                socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:0,0\n\n\0');
            });
            socket.on('message', function (data) {
                if (data.startsWith('CONNECTED') && !connected) {
                    connected = true;
                    socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');
                    for (let i = 0; i < 5; i++) {
                        const body = JSON.stringify({ text: 'mixed-ws-' + __VU + '-' + i, imageUrl: null });
                        socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
                    }
                }
            });
            socket.on('error', function () { mixedSuccess.add(false); });
            socket.setTimeout(function () {
                socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
                socket.close();
            }, 3000);
        });
        mixedWsDur.add(Date.now() - start);
        mixedSuccess.add(res.status === 101);
    }
    sleep(0.1);
}
