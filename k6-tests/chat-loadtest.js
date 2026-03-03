// =============================================================
// 채팅 도메인 통합 부하 테스트
// =============================================================
// 실행: MSYS_NO_PATHCONV=1 docker run --rm -i --network=host \
//   -v "$(pwd)/k6-tests:/scripts" grafana/k6 run /scripts/chat-loadtest.js
//
// 사전 준비:
//   1. seed-mongo-chat.js 실행 (1000 유저, 7개 방, 방당 1000명)
//   2. application-local.yml -> app.chat.storage=mongodb
//
// 흡수된 테스트:
//   - chat-bottleneck-test.js   -> Phase 2 (Baseline 임계값)
//   - chat-highload-test.js     -> Phase 3~6, 8~10
//   - chat-storage-test.js      -> Phase 4 cursor pagination
//   - chat-ws-stress-test.js    -> Phase 7
//   - chat-ws-extreme-test.js   -> Phase 7에 흡수
//
// Phase 구성 (~20분, 최대 1000 VUs):
// ┌────────┬────────────────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                            │ VU   │ 시간  │
// ├────────┼────────────────────────────────────┼──────┼───────┤
// │ 1      │ Warmup                             │ 50   │ 30s   │
// │ 2      │ Baseline — 전 API 혼합              │ 300  │ 2m    │
// │ 3      │ Send Storm — 메시지 전송 + 삭제      │ 500  │ 2m    │
// │ 4      │ Read Storm — 최신 목록 + 커서 페이징  │ 500  │ 2m    │
// │ 5      │ Room List Stress                   │ 350  │ 1.5m  │
// │ 6      │ Read-Write Contention — 동일 방      │ 350  │ 2m    │
// │ 7      │ WebSocket STOMP Stress             │ 350  │ 2m    │
// │ 8      │ Spike 1000 VU                      │ 1000 │ 1.5m  │
// │ 9      │ Double Spike                       │ 800  │ 2m    │
// │ 10     │ Soak                               │ 300  │ 3m    │
// │ 11     │ Cooldown                           │ 5    │ 30s   │
// └────────┴────────────────────────────────────┴──────┴───────┘
//
// 인프라 튜닝 탐지 포인트:
//   - HikariCP: Phase 3,8에서 send storm + spike 커넥션 풀 고갈 여부
//   - MongoDB:  Phase 4에서 cursor pagination on large collections
//   - Redis:    Phase 3에서 Pub/Sub message fan-out 성능
//   - Tomcat:   Phase 7에서 WebSocket upgrade + HTTP 동시 수용
//   - JVM:      Phase 10에서 connection leak detection, GC pause 누적
// =============================================================

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, stompConnect, stompSubscribe, stompDisconnect, parseStompFrames } from './lib/common.js';
import { THRESHOLDS } from './lib/bottleneck.js';

// ============================================
// 테스트 데이터
// ============================================
const USER_COUNT    = parseInt(__ENV.USER_COUNT || '100000');
const TOTAL_ROOMS   = 25000;
const MIN_CHATROOM  = parseInt(__ENV.MIN_CHATROOM_ID || '1');
const MIN_CLUB      = parseInt(__ENV.MIN_CLUB_ID || '1');
const TOP_ROOM_IDS  = [64, 159, 233, 381, 7, 421, 499];
const CLUB_IDS      = [64, 159, 381, 501, 747];

const WS_MODE = (__ENV.WS_MODE || 'stomp');  // 'stomp' | 'reactive'
const WS_URL = (BASE_URL.replace('http://', 'ws://').replace('https://', 'wss://')) + '/ws-native';
const WS_REACTIVE_URL = (BASE_URL.replace('http://', 'ws://').replace('https://', 'wss://')) + '/ws-reactive';

// ============================================
// 커스텀 메트릭 — 엔드포인트별
// ============================================
const chatSendDur     = new Trend('chat_send_duration', true);
const chatDeleteDur   = new Trend('chat_delete_duration', true);
const chatListDur     = new Trend('chat_list_duration', true);
const chatCursorDur   = new Trend('chat_cursor_duration', true);
const chatRoomListDur = new Trend('chat_room_list_duration', true);
const chatWsConnDur   = new Trend('chat_ws_connect_duration', true);

// ============================================
// 커스텀 메트릭 — Phase별 성공률
// ============================================
const phase2Success  = new Rate('chat_phase2_success');
const phase3Success  = new Rate('chat_phase3_success');
const phase4Success  = new Rate('chat_phase4_success');
const phase5Success  = new Rate('chat_phase5_success');
const phase6Success  = new Rate('chat_phase6_success');
const phase7Success  = new Rate('chat_phase7_success');
const phase8Success  = new Rate('chat_phase8_success');
const phase9Success  = new Rate('chat_phase9_success');
const phase10Success = new Rate('chat_phase10_success');

const totalErrors    = new Counter('chat_total_errors');
const wsMsgSent      = new Counter('chat_ws_msg_sent');
const wsMsgRecv      = new Counter('chat_ws_msg_received');

// ============================================
// 시나리오 설정
// ============================================
export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: 50,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: '1_warmup' },
        },

        // Phase 2: Baseline — 전 API 혼합 (bottleneck 임계값 포함)
        baseline: {
            executor: 'constant-vus',
            vus: 300,
            duration: '120s',
            startTime: '35s',
            exec: 'baseline',
            tags: { phase: '2_baseline' },
        },

        // Phase 3: Send Storm — 500 VUs
        send_storm: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 500 },
                { duration: '80s', target: 500 },
                { duration: '20s', target: 0 },
            ],
            startTime: '160s',
            exec: 'sendStorm',
            tags: { phase: '3_send_storm' },
        },

        // Phase 4: Read Storm — 500 VUs
        read_storm: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 500 },
                { duration: '80s', target: 500 },
                { duration: '20s', target: 0 },
            ],
            startTime: '285s',
            exec: 'readStorm',
            tags: { phase: '4_read_storm' },
        },

        // Phase 5: Room List Stress — 350 VUs
        room_list_stress: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '15s', target: 350 },
                { duration: '60s', target: 350 },
                { duration: '15s', target: 0 },
            ],
            startTime: '410s',
            exec: 'roomListStress',
            tags: { phase: '5_room_list' },
        },

        // Phase 6: Read-Write Contention — 350 VUs
        contention: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 350 },
                { duration: '80s', target: 350 },
                { duration: '20s', target: 0 },
            ],
            startTime: '505s',
            exec: 'readWriteContention',
            tags: { phase: '6_contention' },
        },

        // Phase 7: WebSocket Stress — 350 VUs (STOMP or Reactive per WS_MODE)
        ws_stress: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 350 },
                { duration: '80s', target: 350 },
                { duration: '20s', target: 0 },
            ],
            startTime: '630s',
            exec: WS_MODE === 'reactive' ? 'wsReactiveStress' : 'wsStompStress',
            tags: { phase: WS_MODE === 'reactive' ? '7_ws_reactive' : '7_ws_stomp' },
        },

        // Phase 8: Spike — 1000 VUs 순간 폭증
        spike: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '10s', target: 1000 },
                { duration: '40s', target: 1000 },
                { duration: '20s', target: 5 },
                { duration: '20s', target: 5 },
            ],
            startTime: '755s',
            exec: 'spikeTest',
            tags: { phase: '8_spike' },
        },

        // Phase 9: Double Spike — 회복 후 재폭증
        double_spike: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '10s', target: 700 },
                { duration: '20s', target: 700 },
                { duration: '10s', target: 10 },
                { duration: '15s', target: 10 },
                { duration: '10s', target: 800 },
                { duration: '25s', target: 800 },
                { duration: '15s', target: 5 },
                { duration: '15s', target: 5 },
            ],
            startTime: '850s',
            exec: 'doubleSpikeTest',
            tags: { phase: '9_double_spike' },
        },

        // Phase 10: Soak — 중간 부하 장시간
        soak: {
            executor: 'constant-vus',
            vus: 300,
            duration: '180s',
            startTime: '975s',
            exec: 'soakTest',
            tags: { phase: '10_soak' },
        },

        // Phase 11: Cooldown
        cooldown: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            startTime: '1160s',
            exec: 'warmup',
            tags: { phase: '11_cooldown' },
        },
    },

    thresholds: {
        // -- 글로벌 --
        http_req_failed: ['rate<0.05'],

        // -- 엔드포인트별 (bottleneck 임계값) --
        'chat_send_duration':      [`p(95)<${THRESHOLDS.FAST}`],      // 200ms
        'chat_delete_duration':    [`p(95)<${THRESHOLDS.FAST}`],      // 200ms
        'chat_list_duration':      [`p(95)<${THRESHOLDS.NORMAL}`],    // 500ms
        'chat_cursor_duration':    [`p(95)<${THRESHOLDS.NORMAL}`],    // 500ms
        'chat_room_list_duration': [`p(95)<${THRESHOLDS.NORMAL}`],    // 500ms
        'chat_ws_connect_duration': [`p(95)<${THRESHOLDS.VERY_SLOW}`], // 3000ms

        // -- Phase별 성공률 --
        'chat_phase2_success':  ['rate>0.98'],
        'chat_phase3_success':  ['rate>0.98'],
        'chat_phase4_success':  ['rate>0.98'],
        'chat_phase5_success':  ['rate>0.98'],
        'chat_phase6_success':  ['rate>0.95'],
        'chat_phase7_success':  ['rate>0.90'],
        'chat_phase8_success':  ['rate>0.85'],
        'chat_phase9_success':  ['rate>0.85'],
        'chat_phase10_success': ['rate>0.98'],
    },
};

// ============================================
// 유저 유틸
// ============================================
function randomUser() {
    const userId = Math.floor(Math.random() * USER_COUNT) + 1;
    return { userId, kakaoId: 1000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

function vuUser(vuId) {
    const userId = ((vuId - 1) % USER_COUNT) + 1;
    return { userId, kakaoId: 1000000 + userId, status: 'ACTIVE', role: 'ROLE_USER' };
}

function randomRoomId() {
    return TOP_ROOM_IDS[Math.floor(Math.random() * TOP_ROOM_IDS.length)];
}

function randomClubId() {
    return CLUB_IDS[Math.floor(Math.random() * CLUB_IDS.length)];
}

function randomRoomFromPool() {
    return MIN_CHATROOM + Math.floor(Math.random() * TOTAL_ROOMS);
}

// ============================================
// Phase 1 & 11: Warmup / Cooldown
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    const roomId = randomRoomId();

    http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=5`, {
        headers: headers(token), tags: { name: 'warmup_messages' },
    });
    http.get(`${BASE_URL}/api/v1/clubs/${randomClubId()}/chat`, {
        headers: headers(token), tags: { name: 'warmup_rooms' },
    });
    sleep(0.3);
}

// ============================================
// Phase 2: Baseline — 전 API 혼합 + bottleneck 임계값
// ============================================
export function baseline() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = randomRoomId();
    const clubId = randomClubId();
    let messageIds = [];

    // 채팅방 목록
    const roomRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
        headers: hdrs, tags: { name: 'bl_room_list' },
    });
    chatRoomListDur.add(roomRes.timings.duration);
    phase2Success.add(roomRes.status === 200);
    if (roomRes.status !== 200) totalErrors.add(1);
    sleep(0.2);

    // 메시지 목록 (최신)
    const listRes = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=30`, {
        headers: hdrs, tags: { name: 'bl_messages' },
    });
    chatListDur.add(listRes.timings.duration);
    phase2Success.add(listRes.status === 200);
    if (listRes.status !== 200) totalErrors.add(1);

    if (listRes.status === 200) {
        try {
            const body = JSON.parse(listRes.body);
            const data = body.data;
            if (data && data.messages) {
                messageIds = data.messages.map(m => m.messageId);
            } else if (Array.isArray(data)) {
                messageIds = data.map(m => m.messageId);
            }
        } catch (e) { /* ignore */ }
    }
    sleep(0.2);

    // 커서 페이지네이션 (2페이지) — 메시지 30개 이상 시
    if (messageIds.length >= 30) {
        const lastId = messageIds[messageIds.length - 1];
        const cursorRes = http.get(
            `${BASE_URL}/api/v1/chat/${roomId}/messages?size=30&cursor=${lastId}`, {
                headers: hdrs, tags: { name: 'bl_messages_page2' },
            });
        chatCursorDur.add(cursorRes.timings.duration);
        phase2Success.add(cursorRes.status === 200);
    }
    sleep(0.2);

    // 메시지 전송 (30%)
    if (Math.random() < 0.3) {
        const sendRes = http.post(
            `${BASE_URL}/api/v1/chat/${roomId}/messages`,
            JSON.stringify({ text: `k6-bl ${Date.now()}` }),
            { headers: hdrs, tags: { name: 'bl_send' } }
        );
        chatSendDur.add(sendRes.timings.duration);
        phase2Success.add(sendRes.status === 200);
    }
    sleep(0.2);

    // 메시지 삭제 (5%)
    if (Math.random() < 0.05 && messageIds.length > 0) {
        const msgId = messageIds[messageIds.length - 1];
        const delRes = http.del(`${BASE_URL}/api/v1/chat/messages/${msgId}`, null, {
            headers: hdrs, tags: { name: 'bl_delete' },
        });
        chatDeleteDur.add(delRes.timings.duration);
        phase2Success.add(delRes.status === 204 || delRes.status === 200);
    }

    sleep(0.3);
}

// ============================================
// Phase 3: Send Storm — 500 VUs 전송 + 삭제 폭풍
// ============================================
export function sendStorm() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = randomRoomId();

    // 메시지 전송
    const payload = JSON.stringify({
        text: `k6-send user${user.userId} #${Math.floor(Math.random() * 100000)}`,
    });
    const sendRes = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
        headers: hdrs, tags: { name: 'ss_send' },
    });
    chatSendDur.add(sendRes.timings.duration);
    const ok = sendRes.status === 200;
    phase3Success.add(ok);
    if (!ok) totalErrors.add(1);

    // 15% 확률 삭제
    if (ok && Math.random() < 0.15) {
        try {
            const body = JSON.parse(sendRes.body);
            const msgId = body.data.messageId;
            if (msgId) {
                const delRes = http.del(`${BASE_URL}/api/v1/chat/messages/${msgId}`, null, {
                    headers: hdrs, tags: { name: 'ss_delete' },
                });
                chatDeleteDur.add(delRes.timings.duration);
                phase3Success.add(delRes.status === 204 || delRes.status === 200);
            }
        } catch (e) { /* ignore */ }
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 4: Read Storm — 500 VUs 읽기 + 커서 페이징
// ============================================
export function readStorm() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = randomRoomId();
    const roll = Math.random();

    if (roll < 0.6) {
        // 60%: 최신 메시지 조회
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: hdrs, tags: { name: 'rs_list' },
        });
        chatListDur.add(res.timings.duration);
        phase4Success.add(res.status === 200);
        if (res.status !== 200) totalErrors.add(1);
    } else {
        // 40%: 커서 기반 2페이지 연속
        const res1 = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`, {
            headers: hdrs, tags: { name: 'rs_cursor_1' },
        });
        chatListDur.add(res1.timings.duration);
        phase4Success.add(res1.status === 200);

        if (res1.status === 200) {
            try {
                const body = JSON.parse(res1.body);
                const d = body.data;
                if (d && d.hasMore && d.nextCursorId && d.nextCursorAt) {
                    const res2 = http.get(
                        `${BASE_URL}/api/v1/chat/${roomId}/messages?size=20&cursorId=${d.nextCursorId}&cursorAt=${d.nextCursorAt}`, {
                            headers: hdrs, tags: { name: 'rs_cursor_2' },
                        });
                    chatCursorDur.add(res2.timings.duration);
                    phase4Success.add(res2.status === 200);
                }
            } catch (e) { /* ignore */ }
        }
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 5: Room List Stress — 350 VUs
// ============================================
export function roomListStress() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const clubId = randomClubId();

    const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
        headers: headers(token), tags: { name: 'rls_rooms' },
    });
    chatRoomListDur.add(res.timings.duration);
    phase5Success.add(res.status === 200);
    if (res.status !== 200) totalErrors.add(1);

    check(res, { 'rooms 200': (r) => r.status === 200 });
    sleep(0.1 + Math.random() * 0.2);
}

// ============================================
// Phase 6: Read-Write Contention — 동일 방 (roomId 64)
// ============================================
export function readWriteContention() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = TOP_ROOM_IDS[0]; // 집중 경합: 방 64

    if (Math.random() < 0.5) {
        // 읽기
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: hdrs, tags: { name: 'ct_read' },
        });
        chatListDur.add(res.timings.duration);
        phase6Success.add(res.status === 200);
    } else {
        // 쓰기
        const payload = JSON.stringify({ text: `k6-contention ${user.userId}` });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: hdrs, tags: { name: 'ct_write' },
        });
        chatSendDur.add(res.timings.duration);
        phase6Success.add(res.status === 200);
    }
    if (phase6Success) { /* tracked above */ }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 7: WebSocket STOMP Stress — 350 VUs
//   - STOMP CONNECT + SUBSCRIBE + SEND + DISCONNECT
//   - stompConnect/stompSubscribe/stompDisconnect from lib/common.js
// ============================================
export function wsStompStress() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const connectStart = Date.now();

    const res = ws.connect(`${WS_URL}`, null, function (socket) {
        let connected = false;
        const MSGS_TO_SEND = 5;

        socket.on('open', function () {
            // STOMP CONNECT with Authorization header
            socket.send(stompConnect(token));
        });

        socket.on('message', function (data) {
            const frames = parseStompFrames(data);
            for (const frame of frames) {
                if (frame.command === 'CONNECTED' && !connected) {
                    connected = true;
                    chatWsConnDur.add(Date.now() - connectStart);
                    phase7Success.add(true);

                    // 구독
                    socket.send(stompSubscribe('sub-0', `/sub/chat/${roomId}/messages`));

                    // 메시지 전송
                    for (let i = 0; i < MSGS_TO_SEND; i++) {
                        const body = JSON.stringify({ text: `k6-ws ${__VU}-${i}`, imageUrl: null });
                        socket.send(
                            `SEND\ndestination:/pub/chat/${roomId}/messages\ncontent-type:application/json\n\n${body}\u0000`
                        );
                        wsMsgSent.add(1);
                    }
                }
                if (frame.command === 'MESSAGE') wsMsgRecv.add(1);
                if (frame.command === 'ERROR') {
                    phase7Success.add(false);
                    totalErrors.add(1);
                }
            }
        });

        socket.on('error', function () {
            phase7Success.add(false);
            totalErrors.add(1);
        });

        socket.setTimeout(function () {
            socket.send(stompDisconnect('disc-0'));
            socket.close();
        }, 4000);
    });

    if (res.status !== 101) {
        phase7Success.add(false);
        chatWsConnDur.add(Date.now() - connectStart);
    }
    sleep(0.1);
}

// ============================================
// Phase 7 (Reactive): WebSocket Reactive Stress — 350 VUs
//   - Raw JSON subscribe + send + receive
// ============================================
export function wsReactiveStress() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const connectStart = Date.now();

    const res = ws.connect(`${WS_REACTIVE_URL}?token=${token}`, null, function (socket) {
        let subscribed = false;
        const MSGS_TO_SEND = 5;

        socket.on('open', function () {
            chatWsConnDur.add(Date.now() - connectStart);
            phase7Success.add(true);

            // subscribe to room
            socket.send(JSON.stringify({ action: 'subscribe', chatRoomId: roomId }));
        });

        socket.on('message', function (data) {
            try {
                const msg = JSON.parse(data);

                if (msg.type === 'subscribed' && !subscribed) {
                    subscribed = true;
                    // send messages
                    for (let i = 0; i < MSGS_TO_SEND; i++) {
                        socket.send(JSON.stringify({
                            action: 'send',
                            chatRoomId: roomId,
                            text: `k6-reactive ${__VU}-${i}`,
                            imageUrl: null,
                        }));
                        wsMsgSent.add(1);
                    }
                }

                if (msg.type === 'message') {
                    wsMsgRecv.add(1);
                }

                if (msg.type === 'error') {
                    phase7Success.add(false);
                    totalErrors.add(1);
                }
            } catch (e) { /* ignore non-JSON frames */ }
        });

        socket.on('error', function () {
            phase7Success.add(false);
            totalErrors.add(1);
        });

        socket.setTimeout(function () {
            socket.send(JSON.stringify({ action: 'unsubscribe', chatRoomId: roomId }));
            socket.close();
        }, 4000);
    });

    if (res.status !== 101) {
        phase7Success.add(false);
        chatWsConnDur.add(Date.now() - connectStart);
    }
    sleep(0.1);
}

// ============================================
// Phase 8: Spike — 1000 VU 순간 폭증
// ============================================
export function spikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = randomRoomId();
    const ops = ['list', 'send', 'cursor', 'rooms'];
    const op = ops[Math.floor(Math.random() * ops.length)];
    let ok = false;

    if (op === 'list') {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: hdrs, tags: { name: 'sp_list' },
        });
        chatListDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (op === 'send') {
        const payload = JSON.stringify({ text: `k6-spike ${user.userId}` });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: hdrs, tags: { name: 'sp_send' },
        });
        chatSendDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (op === 'cursor') {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`, {
            headers: hdrs, tags: { name: 'sp_cursor' },
        });
        chatCursorDur.add(res.timings.duration);
        ok = res.status === 200;
    } else {
        const clubId = randomClubId();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
            headers: hdrs, tags: { name: 'sp_rooms' },
        });
        chatRoomListDur.add(res.timings.duration);
        ok = res.status === 200;
    }

    phase8Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.05);
}

// ============================================
// Phase 9: Double Spike — 이중 스파이크 (회복 -> 재폭증)
// ============================================
export function doubleSpikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = randomRoomId();
    const roll = Math.random();
    let ok = false;

    if (roll < 0.40) {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: hdrs, tags: { name: 'ds_list' },
        });
        chatListDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (roll < 0.70) {
        const payload = JSON.stringify({ text: `k6-dbl ${user.userId}` });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: hdrs, tags: { name: 'ds_send' },
        });
        chatSendDur.add(res.timings.duration);
        ok = res.status === 200;
    } else {
        const clubId = randomClubId();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
            headers: hdrs, tags: { name: 'ds_rooms' },
        });
        chatRoomListDur.add(res.timings.duration);
        ok = res.status === 200;
    }

    phase9Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 10: Soak — 300 VUs 3분 안정성
//   40% list, 25% send, 20% cursor, 10% rooms, 5% delete
// ============================================
export function soakTest() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = randomRoomId();
    const roll = Math.random();
    let ok = false;

    if (roll < 0.40) {
        // 40%: 메시지 목록
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: hdrs, tags: { name: 'soak_list' },
        });
        chatListDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (roll < 0.65) {
        // 25%: 메시지 전송
        const payload = JSON.stringify({ text: `k6-soak ${user.userId}` });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: hdrs, tags: { name: 'soak_send' },
        });
        chatSendDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (roll < 0.85) {
        // 20%: 커서 페이징
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`, {
            headers: hdrs, tags: { name: 'soak_cursor' },
        });
        chatCursorDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (roll < 0.95) {
        // 10%: 채팅방 목록
        const clubId = randomClubId();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
            headers: hdrs, tags: { name: 'soak_rooms' },
        });
        chatRoomListDur.add(res.timings.duration);
        ok = res.status === 200;
    } else {
        // 5%: 전송 후 삭제
        const payload = JSON.stringify({ text: `k6-soak-del ${user.userId}` });
        const sendRes = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: hdrs, tags: { name: 'soak_send_del' },
        });
        chatSendDur.add(sendRes.timings.duration);
        ok = sendRes.status === 200;
        if (ok) {
            try {
                const body = JSON.parse(sendRes.body);
                const msgId = body.data.messageId;
                if (msgId) {
                    const delRes = http.del(`${BASE_URL}/api/v1/chat/messages/${msgId}`, null, {
                        headers: hdrs, tags: { name: 'soak_delete' },
                    });
                    chatDeleteDur.add(delRes.timings.duration);
                }
            } catch (e) { /* ignore */ }
        }
    }

    phase10Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.1 + Math.random() * 0.2);
}

// ============================================
// default function (fallback)
// ============================================
export default function () {
    warmup();
}
