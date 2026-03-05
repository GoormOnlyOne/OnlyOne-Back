// =============================================================
// 채팅 도메인 통합 부하 테스트
// =============================================================
// 실행: ./k6-tests/run-loadtest.sh chat
//
// 사전 준비:
//   1. seed-all-domains.sql 실행 (50K 채팅방, 10M 메시지 시드)
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
// =============================================================

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
    generateJWT, headers, BASE_URL,
    vu, dur, startAfter,
    stompConnect, stompSubscribe, stompDisconnect, parseStompFrames,
    randomUser, vuUser,
    getUserChatRooms, getRandomUserChatRoom, getRandomUserClub,
    MIN_CHATROOM,
} from '../lib/common.js';
import { THRESHOLDS } from '../lib/bottleneck.js';

// ============================================
// 테스트 데이터
// ============================================
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
// Phase 타이밍 (초 단위, dur()/startAfter()로 스케일링)
// ============================================
const P1 = 30, P2 = 120, P3 = 120, P4 = 120, P5 = 90, P6 = 120;
const P7 = 120, P8 = 90, P9 = 120, P10 = 180, P11 = 30;

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus',
            vus: vu(50),
            duration: dur(P1),
            exec: 'warmup',
            tags: { phase: '1_warmup' },
        },
        baseline: {
            executor: 'constant-vus',
            vus: vu(300),
            duration: dur(P2),
            startTime: startAfter([P1], 5),
            exec: 'baseline',
            tags: { phase: '2_baseline' },
        },
        send_storm: {
            executor: 'ramping-vus',
            startVUs: vu(10),
            stages: [
                { duration: dur(20), target: vu(500) },
                { duration: dur(80), target: vu(500) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2], 5),
            exec: 'sendStorm',
            tags: { phase: '3_send_storm' },
        },
        read_storm: {
            executor: 'ramping-vus',
            startVUs: vu(10),
            stages: [
                { duration: dur(20), target: vu(500) },
                { duration: dur(80), target: vu(500) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3], 5),
            exec: 'readStorm',
            tags: { phase: '4_read_storm' },
        },
        room_list_stress: {
            executor: 'ramping-vus',
            startVUs: vu(5),
            stages: [
                { duration: dur(15), target: vu(350) },
                { duration: dur(60), target: vu(350) },
                { duration: dur(15), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4], 5),
            exec: 'roomListStress',
            tags: { phase: '5_room_list' },
        },
        contention: {
            executor: 'ramping-vus',
            startVUs: vu(10),
            stages: [
                { duration: dur(20), target: vu(350) },
                { duration: dur(80), target: vu(350) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5], 5),
            exec: 'readWriteContention',
            tags: { phase: '6_contention' },
        },
        ws_stress: {
            executor: 'ramping-vus',
            startVUs: vu(10),
            stages: [
                { duration: dur(20), target: vu(350) },
                { duration: dur(80), target: vu(350) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5, P6], 5),
            exec: WS_MODE === 'reactive' ? 'wsReactiveStress' : 'wsStompStress',
            tags: { phase: WS_MODE === 'reactive' ? '7_ws_reactive' : '7_ws_stomp' },
        },
        spike: {
            executor: 'ramping-vus',
            startVUs: vu(5),
            stages: [
                { duration: dur(10), target: vu(1000) },
                { duration: dur(40), target: vu(1000) },
                { duration: dur(20), target: vu(5) },
                { duration: dur(20), target: vu(5) },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7], 5),
            exec: 'spikeTest',
            tags: { phase: '8_spike' },
        },
        double_spike: {
            executor: 'ramping-vus',
            startVUs: vu(5),
            stages: [
                { duration: dur(10), target: vu(700) },
                { duration: dur(20), target: vu(700) },
                { duration: dur(10), target: vu(10) },
                { duration: dur(15), target: vu(10) },
                { duration: dur(10), target: vu(800) },
                { duration: dur(25), target: vu(800) },
                { duration: dur(15), target: vu(5) },
                { duration: dur(15), target: vu(5) },
            ],
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7, P8], 5),
            exec: 'doubleSpikeTest',
            tags: { phase: '9_double_spike' },
        },
        soak: {
            executor: 'constant-vus',
            vus: vu(300),
            duration: dur(P10),
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7, P8, P9], 5),
            exec: 'soakTest',
            tags: { phase: '10_soak' },
        },
        cooldown: {
            executor: 'constant-vus',
            vus: vu(5),
            duration: dur(P11),
            startTime: startAfter([P1, P2, P3, P4, P5, P6, P7, P8, P9, P10], 5),
            exec: 'warmup',
            tags: { phase: '11_cooldown' },
        },
    },

    thresholds: {
        http_req_failed: ['rate<0.05'],
        'chat_send_duration':      [`p(95)<${THRESHOLDS.FAST}`],
        'chat_delete_duration':    [`p(95)<${THRESHOLDS.FAST}`],
        'chat_list_duration':      [`p(95)<${THRESHOLDS.NORMAL}`],
        'chat_cursor_duration':    [`p(95)<${THRESHOLDS.NORMAL}`],
        'chat_room_list_duration': [`p(95)<${THRESHOLDS.NORMAL}`],
        'chat_ws_connect_duration': [`p(95)<${THRESHOLDS.VERY_SLOW}`],
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
// Phase 1 & 11: Warmup / Cooldown
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    const roomId = getRandomUserChatRoom(user.userId);
    const clubId = getRandomUserClub(user.userId);

    http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=5`, {
        headers: headers(token), tags: { name: 'warmup_messages' },
    });
    http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
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
    const roomId = getRandomUserChatRoom(user.userId);
    const clubId = getRandomUserClub(user.userId);
    let nextCursorId = null;
    let nextCursorAt = null;
    let hasMore = false;

    const roomRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
        headers: hdrs, tags: { name: 'bl_room_list' },
    });
    chatRoomListDur.add(roomRes.timings.duration);
    phase2Success.add(roomRes.status === 200);
    if (roomRes.status !== 200) totalErrors.add(1);
    sleep(0.2);

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
            if (data && data.nextCursorId && data.nextCursorAt) {
                nextCursorId = data.nextCursorId;
                nextCursorAt = data.nextCursorAt;
                hasMore = !!data.hasMore;
            }
        } catch (e) { /* ignore */ }
    }
    sleep(0.2);

    if (hasMore && nextCursorId && nextCursorAt) {
        const cursorRes = http.get(
            `${BASE_URL}/api/v1/chat/${roomId}/messages?size=30&cursorId=${nextCursorId}&cursorAt=${nextCursorAt}`, {
                headers: hdrs, tags: { name: 'bl_messages_page2' },
            });
        chatCursorDur.add(cursorRes.timings.duration);
        phase2Success.add(cursorRes.status === 200);
    }
    sleep(0.2);

    if (Math.random() < 0.3) {
        const sendRes = http.post(
            `${BASE_URL}/api/v1/chat/${roomId}/messages`,
            JSON.stringify({ text: `k6-bl ${Date.now()}` }),
            { headers: hdrs, tags: { name: 'bl_send' } }
        );
        chatSendDur.add(sendRes.timings.duration);
        phase2Success.add(sendRes.status === 200);

        if (sendRes.status === 200 && Math.random() < 0.17) {
            try {
                const sendBody = JSON.parse(sendRes.body);
                const msgId = sendBody.data.messageId;
                if (msgId) {
                    const delRes = http.del(`${BASE_URL}/api/v1/chat/messages/${msgId}`, null, {
                        headers: hdrs, tags: { name: 'bl_delete' },
                    });
                    chatDeleteDur.add(delRes.timings.duration);
                    phase2Success.add(delRes.status === 204 || delRes.status === 200);
                }
            } catch (e) { /* ignore */ }
        }
    }

    sleep(0.3);
}

// ============================================
// Phase 3: Send Storm — 500 VUs
// ============================================
export function sendStorm() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = getRandomUserChatRoom(user.userId);

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
// Phase 4: Read Storm — 500 VUs
// ============================================
export function readStorm() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = getRandomUserChatRoom(user.userId);
    const roll = Math.random();

    if (roll < 0.6) {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: hdrs, tags: { name: 'rs_list' },
        });
        chatListDur.add(res.timings.duration);
        phase4Success.add(res.status === 200);
        if (res.status !== 200) totalErrors.add(1);
    } else {
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
    const clubId = getRandomUserClub(user.userId);

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
// Phase 6: Read-Write Contention — 동일 방
// ============================================
export function readWriteContention() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = getUserChatRooms(user.userId)[0] || MIN_CHATROOM;

    if (Math.random() < 0.5) {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: hdrs, tags: { name: 'ct_read' },
        });
        chatListDur.add(res.timings.duration);
        phase6Success.add(res.status === 200);
    } else {
        const payload = JSON.stringify({ text: `k6-contention ${user.userId}` });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: hdrs, tags: { name: 'ct_write' },
        });
        chatSendDur.add(res.timings.duration);
        phase6Success.add(res.status === 200);
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 7: WebSocket STOMP Stress — 350 VUs
// ============================================
export function wsStompStress() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const roomId = getRandomUserChatRoom(user.userId);
    const connectStart = Date.now();

    const res = ws.connect(`${WS_URL}`, null, function (socket) {
        let connected = false;
        const MSGS_TO_SEND = 5;

        socket.on('open', function () {
            socket.send(stompConnect(token));
        });

        socket.on('message', function (data) {
            const frames = parseStompFrames(data);
            for (const frame of frames) {
                if (frame.command === 'CONNECTED' && !connected) {
                    connected = true;
                    chatWsConnDur.add(Date.now() - connectStart);
                    phase7Success.add(true);

                    socket.send(stompSubscribe('sub-0', `/sub/chat/${roomId}/messages`));

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
// Phase 7 (Reactive): WebSocket Reactive Stress
// ============================================
export function wsReactiveStress() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const roomId = getRandomUserChatRoom(user.userId);
    const connectStart = Date.now();

    const res = ws.connect(`${WS_REACTIVE_URL}?token=${token}`, null, function (socket) {
        let subscribed = false;
        const MSGS_TO_SEND = 5;

        socket.on('open', function () {
            chatWsConnDur.add(Date.now() - connectStart);
            phase7Success.add(true);
            socket.send(JSON.stringify({ action: 'subscribe', chatRoomId: roomId }));
        });

        socket.on('message', function (data) {
            try {
                const msg = JSON.parse(data);

                if (msg.type === 'subscribed' && !subscribed) {
                    subscribed = true;
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
                if (msg.type === 'message') wsMsgRecv.add(1);
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
// Phase 8: Spike — 1000 VU
// ============================================
export function spikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = getRandomUserChatRoom(user.userId);
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
        const clubId = getRandomUserClub(user.userId);
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
// Phase 9: Double Spike
// ============================================
export function doubleSpikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = getRandomUserChatRoom(user.userId);
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
        const clubId = getRandomUserClub(user.userId);
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
// Phase 10: Soak — 300 VUs 3분
// ============================================
export function soakTest() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = getRandomUserChatRoom(user.userId);
    const roll = Math.random();
    let ok = false;

    if (roll < 0.40) {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: hdrs, tags: { name: 'soak_list' },
        });
        chatListDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (roll < 0.65) {
        const payload = JSON.stringify({ text: `k6-soak ${user.userId}` });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: hdrs, tags: { name: 'soak_send' },
        });
        chatSendDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (roll < 0.85) {
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`, {
            headers: hdrs, tags: { name: 'soak_cursor' },
        });
        chatCursorDur.add(res.timings.duration);
        ok = res.status === 200;
    } else if (roll < 0.95) {
        const clubId = getRandomUserClub(user.userId);
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
            headers: hdrs, tags: { name: 'soak_rooms' },
        });
        chatRoomListDur.add(res.timings.duration);
        ok = res.status === 200;
    } else {
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

// ============================================
// handleSummary
// ============================================
const pad = (s, n) => String(s).padEnd(n);
const num = (v, d = 0) => v != null ? Number(v).toFixed(d) : 'N/A';
const pct = (v) => v != null ? (Number(v) * 100).toFixed(1) + '%' : 'N/A';
const fmt = (ms) => {
    if (ms == null) return 'N/A'.padStart(8);
    if (ms < 1000) return (ms.toFixed(0) + 'ms').padStart(8);
    return ((ms / 1000).toFixed(2) + 's').padStart(8);
};

export function handleSummary(data) {
    const line = '\u2500'.repeat(60);
    const m = data.metrics;

    let out = `
\u2554${'='.repeat(58)}\u2557
\u2551              채팅 도메인 부하 테스트 결과                    \u2551
\u255A${'='.repeat(58)}\u255D
`;

    const endpoints = [
        ['메시지 전송',     'chat_send_duration'],
        ['메시지 삭제',     'chat_delete_duration'],
        ['메시지 목록',     'chat_list_duration'],
        ['커서 페이징',     'chat_cursor_duration'],
        ['채팅방 목록',     'chat_room_list_duration'],
        ['WS 연결',        'chat_ws_connect_duration'],
    ];

    out += `\n${line}\n`;
    out += `${'API'.padEnd(22)} ${'p50'.padStart(8)} ${'p95'.padStart(8)} ${'max'.padStart(8)}  ${'avg'.padStart(8)}\n`;
    out += `${line}\n`;

    for (const [label, key] of endpoints) {
        const v = m[key]?.values;
        if (v) {
            out += `${label.padEnd(22)} ${fmt(v['p(50)'])} ${fmt(v['p(95)'])} ${fmt(v['max'])}  ${fmt(v['avg'])}\n`;
        }
    }
    out += `${line}\n`;

    const wsSent = m['chat_ws_msg_sent'];
    const wsRecv = m['chat_ws_msg_received'];
    out += `\nWS 전송: ${wsSent ? wsSent.values.count : 0}  |  WS 수신: ${wsRecv ? wsRecv.values.count : 0}\n`;

    const phases = [
        ['Phase 2 Baseline',    'chat_phase2_success'],
        ['Phase 3 SendStorm',   'chat_phase3_success'],
        ['Phase 4 ReadStorm',   'chat_phase4_success'],
        ['Phase 5 RoomList',    'chat_phase5_success'],
        ['Phase 6 Contention',  'chat_phase6_success'],
        ['Phase 7 WebSocket',   'chat_phase7_success'],
        ['Phase 8 Spike',       'chat_phase8_success'],
        ['Phase 9 DblSpike',    'chat_phase9_success'],
        ['Phase 10 Soak',       'chat_phase10_success'],
    ];

    out += `\n${'Phase'.padEnd(22)} ${'성공률'.padStart(10)}\n`;
    out += `${line}\n`;
    for (const [label, key] of phases) {
        const v = m[key]?.values;
        if (v) out += `${label.padEnd(22)} ${pct(v['rate']).padStart(10)}\n`;
    }
    out += `${line}\n`;

    const errMetric = m['chat_total_errors'];
    out += `\n총 에러: ${errMetric ? errMetric.values.count : 0}\n`;

    let pass = 0, fail = 0;
    for (const val of Object.values(m || {})) {
        if (val.thresholds) {
            for (const th of Object.values(val.thresholds)) {
                if (th.ok) pass++; else fail++;
            }
        }
    }
    out += `Thresholds: ${pass} PASS / ${fail} FAIL\n`;

    console.log(out);
    return { 'stdout': out };
}
