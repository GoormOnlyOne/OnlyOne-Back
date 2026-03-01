// =============================================================
// chat-ws-extreme-test.js
// WebSocket STOMP 극한 스트레스 테스트
// 목표: STOMP 브로커, Redis Pub/Sub, 커넥션 풀 한계점 탐지
// =============================================================
//
// 실행 방법 (Docker):
//   MSYS_NO_PATHCONV=1 docker run --rm \
//     --network host \
//     -v "$(pwd)/k6-tests:/scripts" \
//     grafana/k6:latest run /scripts/chat-ws-extreme-test.js
//
// =============================================================
// 테스트 구조 (8 Phase, 총 ~17분):
//
//   Phase 1  — Warmup (30s): 기본 확인 (20 VUs)
//   Phase 2  — Mass Connect (2m): 동시 접속 → 600 VUs
//   Phase 3  — Message Storm (2.5m): 50msg/세션, 400 VUs
//   Phase 4  — Sustained Connections (3m): 300 VUs 장시간 유지 (20초 세션, 3초 간격 전송)
//   Phase 5  — Broadcast Stress (2m): 7방 동시 구독 + 전송 (300 VUs)
//   Phase 6  — Spike 1000 VUs (1.5m): 극한 스파이크
//   Phase 7  — Spike + HTTP Mixed (2m): 500 WS + 200 HTTP 동시
//   Phase 8  — Cooldown (30s): 잔여 소화
// =============================================================

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL } from './lib/common.js';

const WS_URL = (BASE_URL.replace('http://', 'ws://').replace('https://', 'wss://')) + '/ws-native';

// ============================================
// 데이터 설정 — userId 1~1000, 7방 (각 1,000명)
// ============================================
const VALID_USER_COUNT = 1000;
const VALID_ROOM_IDS   = [64, 159, 381, 501, 747, 864, 959];

// ============================================
// 커스텀 메트릭
// ============================================

// Phase 2: Mass Connect
const massConnDur      = new Trend('ex_mass_conn_duration', true);
const massConnSuccess  = new Rate('ex_mass_conn_success');
const massConnErrors   = new Counter('ex_mass_conn_errors');

// Phase 3: Message Storm
const msgStormConnDur  = new Trend('ex_msg_storm_conn_duration', true);
const msgStormSuccess  = new Rate('ex_msg_storm_success');
const msgStormSent     = new Counter('ex_msg_storm_sent');
const msgStormRecv     = new Counter('ex_msg_storm_received');

// Phase 4: Sustained
const sustainedSuccess = new Rate('ex_sustained_success');
const sustainedSent    = new Counter('ex_sustained_sent');
const sustainedRecv    = new Counter('ex_sustained_received');
const sustainedErrors  = new Counter('ex_sustained_errors');

// Phase 5: Broadcast
const bcastSuccess     = new Rate('ex_bcast_success');
const bcastSent        = new Counter('ex_bcast_sent');
const bcastRecv        = new Counter('ex_bcast_received');
const bcastSubs        = new Counter('ex_bcast_subs');

// Phase 6: Spike 1000
const spike1kDur       = new Trend('ex_spike_1k_duration', true);
const spike1kSuccess   = new Rate('ex_spike_1k_success');
const spike1kErrors    = new Counter('ex_spike_1k_errors');

// Phase 7: Mixed
const mixedWsSuccess   = new Rate('ex_mixed_ws_success');
const mixedHttpDur     = new Trend('ex_mixed_http_duration', true);
const mixedHttpSuccess = new Rate('ex_mixed_http_success');

// ============================================
// 시나리오
// ============================================
export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: 20,
            duration: '30s',
            exec: 'wsWarmup',
            tags: { phase: 'warmup' },
        },

        // Phase 2: Mass Connect — 600 VUs 점진 증가
        mass_connect: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '20s', target: 200 },
                { duration: '20s', target: 400 },
                { duration: '20s', target: 600 },
                { duration: '40s', target: 600 },
                { duration: '20s', target: 0 },
            ],
            startTime: '35s',
            exec: 'massConnect',
            tags: { phase: 'mass_connect' },
        },

        // Phase 3: Message Storm — 400 VUs, 50msg/세션
        msg_storm: {
            executor: 'ramping-vus',
            startVUs: 30,
            stages: [
                { duration: '20s', target: 400 },
                { duration: '120s', target: 400 },
                { duration: '10s', target: 0 },
            ],
            startTime: '160s',
            exec: 'messageStorm',
            tags: { phase: 'msg_storm' },
        },

        // Phase 4: Sustained Connections — 300 VUs, 20초 세션, 3초 간격 전송
        sustained: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '20s', target: 300 },
                { duration: '140s', target: 300 },
                { duration: '20s', target: 0 },
            ],
            startTime: '315s',
            exec: 'sustainedSession',
            tags: { phase: 'sustained' },
        },

        // Phase 5: Broadcast Stress — 300 VUs, 7방 전부 구독 + 전송
        broadcast: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '15s', target: 300 },
                { duration: '90s', target: 300 },
                { duration: '15s', target: 0 },
            ],
            startTime: '500s',
            exec: 'broadcastStress',
            tags: { phase: 'broadcast' },
        },

        // Phase 6: Spike 1000 VUs
        spike_1k: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '5s', target: 1000 },
                { duration: '40s', target: 1000 },
                { duration: '10s', target: 200 },
                { duration: '10s', target: 0 },
            ],
            startTime: '625s',
            exec: 'spike1000',
            tags: { phase: 'spike_1k' },
        },

        // Phase 7: Mixed WS(500) + HTTP(200) 동시
        mixed_extreme: {
            executor: 'ramping-vus',
            startVUs: 30,
            stages: [
                { duration: '15s', target: 700 },
                { duration: '90s', target: 700 },
                { duration: '15s', target: 0 },
            ],
            startTime: '695s',
            exec: 'mixedExtreme',
            tags: { phase: 'mixed_extreme' },
        },

        // Phase 8: Cooldown
        cooldown: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            exec: 'wsWarmup',
            startTime: '820s',
            tags: { phase: 'cooldown' },
        },
    },

    thresholds: {
        // Phase 2
        'ex_mass_conn_duration':     ['p(95)<3000'],
        'ex_mass_conn_success':      ['rate>0.85'],
        // Phase 3
        'ex_msg_storm_success':      ['rate>0.90'],
        // Phase 4
        'ex_sustained_success':      ['rate>0.90'],
        // Phase 5
        'ex_bcast_success':          ['rate>0.85'],
        // Phase 6
        'ex_spike_1k_duration':      ['p(95)<5000'],
        'ex_spike_1k_success':       ['rate>0.75'],
        // Phase 7
        'ex_mixed_ws_success':       ['rate>0.85'],
        'ex_mixed_http_success':     ['rate>0.90'],
        'ex_mixed_http_duration':    ['p(95)<1000'],
    },
};

// ============================================
// 유틸
// ============================================
function testUser(vuId) {
    const userId = (vuId % VALID_USER_COUNT) + 1;
    return { userId, kakaoId: 10000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}
function randomRoomId() { return VALID_ROOM_IDS[Math.floor(Math.random() * VALID_ROOM_IDS.length)]; }

// ============================================
// Phase 1: Warmup
// ============================================
export function wsWarmup() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();

    ws.connect(WS_URL, null, function (socket) {
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
// Phase 2: Mass Connect — 600 VUs 동시 접속
// ============================================
export function massConnect() {
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
            if (data.startsWith('CONNECTED') && !connected) {
                connected = true;
                massConnDur.add(Date.now() - start);
                massConnSuccess.add(true);
                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');
                // 3개 메시지
                for (let i = 0; i < 3; i++) {
                    const body = JSON.stringify({ text: 'mass-' + __VU + '-' + i, imageUrl: null });
                    socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
                }
            }
            if (data.startsWith('ERROR')) massConnErrors.add(1);
        });
        socket.on('error', function () { massConnSuccess.add(false); massConnErrors.add(1); });
        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 4000);
    });

    if (res.status !== 101) {
        massConnSuccess.add(false);
        massConnDur.add(Date.now() - start);
    }
    sleep(0.05);
}

// ============================================
// Phase 3: Message Storm — 세션당 50개 메시지 폭풍
// ============================================
export function messageStorm() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const start = Date.now();
    let connected = false;
    const MSGS = 50;

    const res = ws.connect(WS_URL, null, function (socket) {
        socket.on('open', function () {
            socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:0,0\n\n\0');
        });
        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED') && !connected) {
                connected = true;
                msgStormConnDur.add(Date.now() - start);
                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');

                for (let i = 0; i < MSGS; i++) {
                    const body = JSON.stringify({ text: 'storm-' + __VU + '-' + i, imageUrl: null });
                    socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
                    msgStormSent.add(1);
                }
            }
            if (data.startsWith('MESSAGE')) msgStormRecv.add(1);
        });
        socket.on('error', function () { msgStormSuccess.add(false); });
        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 8000);
    });

    msgStormSuccess.add(res.status === 101);
    sleep(0.1);
}

// ============================================
// Phase 4: Sustained Connections — 20초 유지, 3초 간격 전송
// ============================================
export function sustainedSession() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    let connected = false;
    let msgIdx = 0;

    const res = ws.connect(WS_URL, null, function (socket) {
        socket.on('open', function () {
            socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:10000,10000\n\n\0');
        });
        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED') && !connected) {
                connected = true;
                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');

                // 3초 간격으로 메시지 전송 (20초간 6~7회)
                socket.setInterval(function () {
                    const body = JSON.stringify({ text: 'sust-' + __VU + '-' + (msgIdx++), imageUrl: null });
                    socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
                    sustainedSent.add(1);
                }, 3000);
            }
            if (data.startsWith('MESSAGE')) sustainedRecv.add(1);
            if (data.startsWith('ERROR')) sustainedErrors.add(1);
        });
        socket.on('error', function () { sustainedSuccess.add(false); });
        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 20000);
    });

    sustainedSuccess.add(res.status === 101);
    sleep(0.5);
}

// ============================================
// Phase 5: Broadcast Stress — 7방 전부 구독 + 각 방에 메시지
// ============================================
export function broadcastStress() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    let connected = false;

    const res = ws.connect(WS_URL, null, function (socket) {
        socket.on('open', function () {
            socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:0,0\n\n\0');
        });
        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED') && !connected) {
                connected = true;
                // 7방 전부 구독
                for (let i = 0; i < VALID_ROOM_IDS.length; i++) {
                    socket.send('SUBSCRIBE\nid:sub-' + i + '\ndestination:/sub/chat/' + VALID_ROOM_IDS[i] + '/messages\n\n\0');
                    bcastSubs.add(1);
                }
                // 각 방에 3개씩 메시지 = 21개
                for (let i = 0; i < VALID_ROOM_IDS.length; i++) {
                    for (let j = 0; j < 3; j++) {
                        const body = JSON.stringify({ text: 'bcast-' + __VU + '-r' + VALID_ROOM_IDS[i] + '-' + j, imageUrl: null });
                        socket.send('SEND\ndestination:/pub/chat/' + VALID_ROOM_IDS[i] + '/messages\ncontent-type:application/json\n\n' + body + '\0');
                        bcastSent.add(1);
                    }
                }
            }
            if (data.startsWith('MESSAGE')) bcastRecv.add(1);
        });
        socket.on('error', function () { bcastSuccess.add(false); });
        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 6000);
    });

    bcastSuccess.add(res.status === 101);
    sleep(0.2);
}

// ============================================
// Phase 6: Spike 1000 VUs
// ============================================
export function spike1000() {
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
            if (data.startsWith('CONNECTED') && !connected) {
                connected = true;
                spike1kDur.add(Date.now() - start);
                spike1kSuccess.add(true);
                socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');
                const body = JSON.stringify({ text: 'spike1k-' + __VU, imageUrl: null });
                socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
            }
            if (data.startsWith('ERROR')) spike1kErrors.add(1);
        });
        socket.on('error', function () { spike1kSuccess.add(false); spike1kErrors.add(1); });
        socket.setTimeout(function () {
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 4000);
    });

    if (res.status !== 101) {
        spike1kSuccess.add(false);
        spike1kDur.add(Date.now() - start);
    }
    sleep(0.05);
}

// ============================================
// Phase 7: Mixed Extreme — 70% WS + 30% HTTP, 700 VUs
// ============================================
export function mixedExtreme() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();

    if (Math.random() < 0.3) {
        // 30% HTTP — 전송 + 조회 + 방 목록
        const start = Date.now();
        const rand = Math.random();
        let res;
        if (rand < 0.35) {
            res = http.post(
                `${BASE_URL}/api/v1/chat/${roomId}/messages`,
                JSON.stringify({ text: 'mixed-http-' + __VU, imageUrl: null }),
                { headers: headers(token), tags: { name: 'mixed_http_send' } }
            );
        } else if (rand < 0.7) {
            res = http.get(
                `${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`,
                { headers: headers(token), tags: { name: 'mixed_http_read' } }
            );
        } else {
            // 채팅방 목록 (병목 해결 확인)
            const clubIds = [1, 2, 3, 5, 10];
            const clubId = clubIds[Math.floor(Math.random() * clubIds.length)];
            res = http.get(
                `${BASE_URL}/api/v1/clubs/${clubId}/chat`,
                { headers: headers(token), tags: { name: 'mixed_http_rooms' } }
            );
        }
        mixedHttpDur.add(Date.now() - start);
        mixedHttpSuccess.add(res.status === 200 || res.status === 201);
    } else {
        // 70% WebSocket — 10개 메시지
        const res = ws.connect(WS_URL, null, function (socket) {
            let connected = false;
            socket.on('open', function () {
                socket.send('CONNECT\naccept-version:1.2\nAuthorization:Bearer ' + token + '\nheart-beat:0,0\n\n\0');
            });
            socket.on('message', function (data) {
                if (data.startsWith('CONNECTED') && !connected) {
                    connected = true;
                    socket.send('SUBSCRIBE\nid:sub-0\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');
                    for (let i = 0; i < 10; i++) {
                        const body = JSON.stringify({ text: 'mx-ws-' + __VU + '-' + i, imageUrl: null });
                        socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + body + '\0');
                    }
                }
            });
            socket.on('error', function () { mixedWsSuccess.add(false); });
            socket.setTimeout(function () {
                socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
                socket.close();
            }, 4000);
        });
        mixedWsSuccess.add(res.status === 101);
    }
    sleep(0.05);
}
