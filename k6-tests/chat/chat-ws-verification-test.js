// =============================================================
// 채팅 메시지 검증 테스트 (HTTP 왕복 + WebSocket 수신 + Soak)
// =============================================================
// 실행: ./k6-tests/run-loadtest.sh chat-ws
//
// 검증 항목:
//   1. HTTP 왕복 검증: POST /messages → GET /messages → 본문 일치 확인
//   2. WebSocket 수신 검증: STOMP CONNECT → SUBSCRIBE → SEND → MESSAGE 프레임 본문 일치
//   3. WS Soak: 장시간 연결 유지 + 주기적 송수신 안정성
//
// Phase 구성 (~8분, 최대 200 VUs):
// ┌────────┬────────────────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                            │ VU   │ 시간  │
// ├────────┼────────────────────────────────────┼──────┼───────┤
// │ 1      │ Warmup                             │ 30   │ 30s   │
// │ 2      │ HTTP send-then-verify              │ 200  │ 2m    │
// │ 3      │ WS content verification            │ 150  │ 2m    │
// │ 4      │ WS soak (long-lived connections)   │ 100  │ 3m    │
// │ 5      │ Cooldown                           │ 5    │ 30s   │
// └────────┴────────────────────────────────────┴──────┴───────┘
// =============================================================

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
    generateJWT, headers, BASE_URL,
    vu, dur, startAfter,
    randomUser, vuUser,
    stompConnect, stompSubscribe, stompDisconnect, parseStompFrames,
    getRandomUserChatRoom,
} from '../lib/common.js';

// ============================================
// 테스트 데이터
// ============================================
const WS_URL = BASE_URL.replace('http://', 'ws://').replace('https://', 'wss://') + '/ws-native';

// ============================================
// 커스텀 메트릭
// ============================================
const chatHttpRoundtripMatch = new Rate('chat_http_roundtrip_match');
const chatWsContentMatch     = new Rate('chat_ws_content_match');
const chatWsSoakSent         = new Counter('chat_ws_soak_sent');
const chatWsSoakReceived     = new Counter('chat_ws_soak_received');
const chatWsSoakDuration     = new Trend('chat_ws_soak_duration', true);
const chatWsSoakDisconnect   = new Counter('chat_ws_soak_disconnect');

// ============================================
// Phase 타이밍
// ============================================
const P1 = 30, P2 = 120, P3 = 120, P4 = 180, P5 = 30;

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus',
            vus: vu(30),
            duration: dur(P1),
            exec: 'warmup',
            tags: { phase: '1_warmup' },
        },
        http_verify: {
            executor: 'constant-vus',
            vus: vu(200),
            duration: dur(P2),
            startTime: startAfter([P1], 5),
            exec: 'httpSendThenVerify',
            tags: { phase: '2_http_verify' },
        },
        ws_verify: {
            executor: 'constant-vus',
            vus: vu(150),
            duration: dur(P3),
            startTime: startAfter([P1, P2], 5),
            exec: 'wsContentVerify',
            tags: { phase: '3_ws_verify' },
        },
        ws_soak: {
            executor: 'constant-vus',
            vus: vu(100),
            duration: dur(P4),
            startTime: startAfter([P1, P2, P3], 5),
            exec: 'wsSoak',
            tags: { phase: '4_ws_soak' },
        },
        cooldown: {
            executor: 'constant-vus',
            vus: vu(5),
            duration: dur(P5),
            startTime: startAfter([P1, P2, P3, P4], 5),
            exec: 'warmup',
            tags: { phase: '5_cooldown' },
        },
    },

    thresholds: {
        http_req_failed:           ['rate<0.05'],
        chat_http_roundtrip_match: ['rate>0.90'],
        chat_ws_content_match:     ['rate>0.80'],
    },
};

// ============================================
// Phase 1 & 5: Warmup / Cooldown
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    const roomId = getRandomUserChatRoom(user.userId);

    http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=5`, {
        headers: headers(token), tags: { name: 'warmup_messages' },
    });
    sleep(0.3);
}

// ============================================
// Phase 2: HTTP send-then-verify
// ============================================
export function httpSendThenVerify() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = getRandomUserChatRoom(user.userId);

    const uniqueText = `k6-verify-${user.userId}-${Date.now()}-${Math.floor(Math.random() * 1000000)}`;

    const sendRes = http.post(
        `${BASE_URL}/api/v1/chat/${roomId}/messages`,
        JSON.stringify({ text: uniqueText }),
        { headers: hdrs, tags: { name: 'hv_send' } }
    );

    if (sendRes.status !== 200) {
        chatHttpRoundtripMatch.add(false);
        sleep(0.3);
        return;
    }

    sleep(0.2);

    const listRes = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=10`, {
        headers: hdrs, tags: { name: 'hv_list' },
    });

    if (listRes.status !== 200) {
        chatHttpRoundtripMatch.add(false);
        sleep(0.3);
        return;
    }

    let matched = false;
    try {
        const body = JSON.parse(listRes.body);
        const messages = body.data && body.data.messages ? body.data.messages : [];
        for (const msg of messages) {
            if (msg.text === uniqueText) { matched = true; break; }
        }
    } catch (e) { /* parse error */ }

    chatHttpRoundtripMatch.add(matched);
    check(null, { 'HTTP roundtrip: content matched': () => matched });
    sleep(0.3);
}

// ============================================
// Phase 3: WS content verification
// ============================================
export function wsContentVerify() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const roomId = getRandomUserChatRoom(user.userId);
    const uniqueText = `k6-ws-verify-${user.userId}-${Date.now()}-${Math.floor(Math.random() * 1000000)}`;

    let contentMatched = false;

    const res = ws.connect(`${WS_URL}`, null, function (socket) {
        let connected = false;
        let messageSent = false;

        socket.on('open', function () {
            socket.send(stompConnect(token));
        });

        socket.on('message', function (data) {
            const frames = parseStompFrames(data);
            for (const frame of frames) {
                if (frame.command === 'CONNECTED' && !connected) {
                    connected = true;
                    socket.send(stompSubscribe('sub-0', `/sub/chat/${roomId}/messages`));
                    socket.setTimeout(function () {
                        const body = JSON.stringify({ text: uniqueText, imageUrl: null });
                        socket.send(
                            `SEND\ndestination:/pub/chat/${roomId}/messages\ncontent-type:application/json\n\n${body}\u0000`
                        );
                        messageSent = true;
                    }, 300);
                }

                if (frame.command === 'MESSAGE' && messageSent) {
                    try {
                        const msgBody = JSON.parse(frame.body);
                        if (msgBody.text === uniqueText) contentMatched = true;
                    } catch (e) {
                        if (frame.body && frame.body.indexOf(uniqueText) !== -1) contentMatched = true;
                    }
                }

                if (frame.command === 'ERROR') chatWsContentMatch.add(false);
            }
        });

        socket.on('error', function () { chatWsContentMatch.add(false); });

        socket.setTimeout(function () {
            socket.send(stompDisconnect('disc-0'));
            socket.close();
        }, 3000);
    });

    chatWsContentMatch.add(contentMatched);
    check(null, { 'WS: received MESSAGE with matching content': () => contentMatched });
    sleep(0.2);
}

// ============================================
// Phase 4: WS soak — 장시간 연결 유지
// ============================================
export function wsSoak() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const roomId = getRandomUserChatRoom(user.userId);
    const soakStart = Date.now();

    let localSent = 0;
    let localReceived = 0;

    const res = ws.connect(`${WS_URL}`, null, function (socket) {
        let connected = false;

        socket.on('open', function () {
            socket.send(stompConnect(token));
        });

        socket.on('message', function (data) {
            const frames = parseStompFrames(data);
            for (const frame of frames) {
                if (frame.command === 'CONNECTED' && !connected) {
                    connected = true;
                    socket.send(stompSubscribe('sub-0', `/sub/chat/${roomId}/messages`));

                    socket.setInterval(function () {
                        const body = JSON.stringify({
                            text: `k6-soak-${user.userId}-${Date.now()}`,
                            imageUrl: null,
                        });
                        socket.send(
                            `SEND\ndestination:/pub/chat/${roomId}/messages\ncontent-type:application/json\n\n${body}\u0000`
                        );
                        localSent++;
                        chatWsSoakSent.add(1);
                    }, 10000);
                }
                if (frame.command === 'MESSAGE') {
                    localReceived++;
                    chatWsSoakReceived.add(1);
                }
            }
        });

        socket.on('close', function () { chatWsSoakDisconnect.add(1); });
        socket.on('error', function () { chatWsSoakDisconnect.add(1); });

        socket.setTimeout(function () {
            socket.send(stompDisconnect('disc-soak'));
            socket.close();
        }, 170000);
    });

    const soakDuration = Date.now() - soakStart;
    chatWsSoakDuration.add(soakDuration);

    if (res.status !== 101) chatWsSoakDisconnect.add(1);

    check(null, {
        'Soak: connection lasted > 30s': () => soakDuration > 30000,
        'Soak: sent at least 1 message': () => localSent > 0,
        'Soak: received at least 1 message': () => localReceived > 0,
    });

    sleep(1);
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
const num = (v, d = 1) => v != null ? Number(v).toFixed(d) : 'N/A';
const pct = (v) => v != null ? (Number(v) * 100).toFixed(1) + '%' : 'N/A';
const cnt = (v) => v != null ? Math.floor(Number(v)).toString() : '0';

export function handleSummary(data) {
    const m = data.metrics;
    const lines = [
        '',
        '=====================================================================',
        '          Chat WS Verification Test Report',
        '=====================================================================',
        '',
        '--- Phase 2: HTTP Send-then-Verify ---',
        `  Roundtrip match rate:  ${pct(m.chat_http_roundtrip_match?.values?.rate)}`,
        `  HTTP requests failed:  ${pct(m.http_req_failed?.values?.rate)}`,
        '',
        '--- Phase 3: WS Content Verification ---',
        `  WS content match rate: ${pct(m.chat_ws_content_match?.values?.rate)}`,
        '',
        '--- Phase 4: WS Soak ---',
        `  Total messages sent:     ${cnt(m.chat_ws_soak_sent?.values?.count)}`,
        `  Total messages received:  ${cnt(m.chat_ws_soak_received?.values?.count)}`,
        `  Unexpected disconnects:   ${cnt(m.chat_ws_soak_disconnect?.values?.count)}`,
        '',
        '  Soak duration (ms):',
        `    p50:  ${num(m.chat_ws_soak_duration?.values?.med)}`,
        `    p95:  ${num(m.chat_ws_soak_duration?.values?.['p(95)'])}`,
        `    min:  ${num(m.chat_ws_soak_duration?.values?.min)}`,
        `    max:  ${num(m.chat_ws_soak_duration?.values?.max)}`,
        '',
        '--- Thresholds ---',
        `  http_req_failed < 5%:            ${pct(m.http_req_failed?.values?.rate)}`,
        `  chat_http_roundtrip_match > 90%:  ${pct(m.chat_http_roundtrip_match?.values?.rate)}`,
        `  chat_ws_content_match > 80%:      ${pct(m.chat_ws_content_match?.values?.rate)}`,
        '',
        '--- HTTP Timing ---',
        `  http_req_duration p50: ${num(m.http_req_duration?.values?.med)} ms`,
        `  http_req_duration p95: ${num(m.http_req_duration?.values?.['p(95)'])} ms`,
        '',
        '=====================================================================',
        '',
    ];

    console.log(lines.join('\n'));
    return {};
}
