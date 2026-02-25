// =============================================================
// 채팅 성능 부하 테스트
//
// Phase 1: 메시지 조회 단독  (커서 페이징 / DB 쿼리 성능)
// Phase 2: 메시지 전송 단독  (쓰기 처리량 / Redis Pub/Sub)
// Phase 3: 혼합 (읽기 + 쓰기 동시)
// Phase 4: WebSocket 연결 + STOMP 메시지
// Phase 5: 스파이크 (한계점)
// =============================================================

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL } from './lib/common.js';

// init 단계에서 유저-채팅방/클럽 매핑 파일 로드 (open()은 init에서만 호출 가능)
const USER_ROOMS = JSON.parse(open('./lib/user-rooms.json'));
const USER_CLUBS = JSON.parse(open('./lib/user-clubs.json'));

// ============================================
// 커스텀 메트릭 — Phase별 분리
// ============================================

// Phase 1: 메시지 조회
const p1MsgListDuration    = new Trend('p1_msg_list_duration', true);
const p1MsgListPageDur     = new Trend('p1_msg_list_page2_duration', true);
const p1RoomListDuration   = new Trend('p1_room_list_duration', true);
const p1SuccessRate        = new Rate('p1_success_rate');
const p1Errors             = new Counter('p1_5xx_errors');

// Phase 2: 메시지 전송
const p2SendDuration       = new Trend('p2_msg_send_duration', true);
const p2SuccessRate        = new Rate('p2_success_rate');
const p2Errors             = new Counter('p2_5xx_errors');

// Phase 3: 혼합
const p3MsgListDuration    = new Trend('p3_msg_list_duration', true);
const p3SendDuration       = new Trend('p3_msg_send_duration', true);
const p3RoomListDuration   = new Trend('p3_room_list_duration', true);
const p3DeleteDuration     = new Trend('p3_msg_delete_duration', true);
const p3SuccessRate        = new Rate('p3_success_rate');
const p3Errors             = new Counter('p3_5xx_errors');

// Phase 4: WebSocket
const p4WsConnectDuration  = new Trend('p4_ws_connect_duration', true);
const p4WsMsgSendDuration  = new Trend('p4_ws_msg_round_trip', true);
const p4WsSuccess          = new Rate('p4_ws_success_rate');
const p4WsErrors           = new Counter('p4_ws_errors');

// Phase 5: 스파이크
const p5MsgListDuration    = new Trend('p5_msg_list_duration', true);
const p5SendDuration       = new Trend('p5_msg_send_duration', true);
const p5SuccessRate        = new Rate('p5_success_rate');
const p5Errors             = new Counter('p5_5xx_errors');

// 통합
const apiSuccessRate       = new Rate('api_success_rate');
const serverErrors         = new Counter('server_5xx_errors');
const dbSlowQueries        = new Counter('db_slow_queries');

// ============================================
// 테스트 유저 (1~1000)
// ============================================
const TEST_USERS_COUNT = 1000;

function getUser(vuId) {
    const userId = ((vuId - 1) % TEST_USERS_COUNT) + 1;
    return {
        userId,
        kakaoId: 10000000 + userId,
        status: 'ACTIVE',
        role: 'ROLE_USER',
    };
}

// 유저가 실제 가입한 클럽 중 랜덤 선택 (DB 기반)
function getClubIdForUser(userId) {
    const clubs = USER_CLUBS[String(userId)];
    if (!clubs || clubs.length === 0) {
        return null;
    }
    return clubs[Math.floor(Math.random() * clubs.length)];
}

// init에서 로드한 유저별 참여 방 목록에서 랜덤 방 선택
function getUserRoomId(userId) {
    const rooms = USER_ROOMS[String(userId)];
    if (!rooms || rooms.length === 0) {
        return null;
    }
    return rooms[Math.floor(Math.random() * rooms.length)];
}

// ============================================
// 시나리오 (5-Phase, 총 ~10분)
// ============================================
export const options = {
    scenarios: {
        phase1_read: {
            executor: 'ramping-vus',
            exec: 'messageReadTest',
            startVUs: 0,
            stages: [
                { duration: '20s', target: 50 },
                { duration: '30s', target: 200 },
                { duration: '40s', target: 400 },
                { duration: '20s', target: 0 },
            ],
            startTime: '0s',
            tags: { phase: 'read_only' },
        },
        phase2_write: {
            executor: 'ramping-vus',
            exec: 'messageSendTest',
            startVUs: 0,
            stages: [
                { duration: '20s', target: 40 },
                { duration: '30s', target: 150 },
                { duration: '30s', target: 300 },
                { duration: '20s', target: 0 },
            ],
            startTime: '2m0s',
            tags: { phase: 'write_only' },
        },
        phase3_mixed: {
            executor: 'ramping-vus',
            exec: 'mixedTest',
            startVUs: 0,
            stages: [
                { duration: '20s', target: 50 },
                { duration: '40s', target: 200 },
                { duration: '40s', target: 400 },
                { duration: '20s', target: 0 },
            ],
            startTime: '3m50s',
            tags: { phase: 'mixed' },
        },
        phase4_websocket: {
            executor: 'ramping-vus',
            exec: 'websocketTest',
            startVUs: 0,
            stages: [
                { duration: '20s', target: 40 },
                { duration: '30s', target: 150 },
                { duration: '30s', target: 300 },
                { duration: '20s', target: 0 },
            ],
            startTime: '5m50s',
            tags: { phase: 'websocket' },
        },
        phase5_spike: {
            executor: 'ramping-vus',
            exec: 'spikeTest',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 100 },
                { duration: '10s', target: 500 },
                { duration: '30s', target: 500 },
                { duration: '10s', target: 0 },
            ],
            startTime: '7m50s',
            tags: { phase: 'spike' },
        },
    },

    thresholds: {
        'p1_msg_list_duration':  ['p(95)<300'],
        'p1_room_list_duration': ['p(95)<500'],
        'p2_msg_send_duration':  ['p(95)<500'],
        'p3_msg_list_duration':  ['p(95)<500'],
        'p3_msg_send_duration':  ['p(95)<800'],
        'p1_success_rate':       ['rate>0.95'],
        'p2_success_rate':       ['rate>0.95'],
        'p3_success_rate':       ['rate>0.90'],
    },
};

// ============================================
// setup
// ============================================
export function setup() {
    console.log('=== 채팅 성능 부하 테스트 시작 ===');
    console.log(`BASE_URL: ${BASE_URL}`);
    console.log('Phase 1 (0~1:50)  : 메시지 조회 단독 → 최대 400 VU');
    console.log('Phase 2 (2:00~3:40): 메시지 전송 단독 → 최대 300 VU');
    console.log('Phase 3 (3:50~5:50): 혼합 부하        → 최대 400 VU');
    console.log('Phase 4 (5:50~7:30): WebSocket         → 최대 300 VU');
    console.log('Phase 5 (7:50~8:50): 스파이크          → 500 VU');
    console.log('=========================================');

    const health = http.get(`${BASE_URL}/actuator/health`);
    if (health.status !== 200) {
        console.error('서버 헬스체크 실패!');
    }

    // 유저-채팅방 매핑은 init 단계에서 user-rooms.json으로 로드됨
    const totalUsers = Object.keys(USER_ROOMS).length;
    const totalMappings = Object.values(USER_ROOMS).reduce((sum, r) => sum + r.length, 0);
    console.log(`유저-채팅방 매핑 로드 완료: ${totalUsers}명, 총 ${totalMappings}건`);

    const totalClubUsers = Object.keys(USER_CLUBS).length;
    const totalClubMappings = Object.values(USER_CLUBS).reduce((sum, c) => sum + c.length, 0);
    console.log(`유저-클럽 매핑 로드 완료: ${totalClubUsers}명, 총 ${totalClubMappings}건`);
}

// ============================================
// Phase 1: 메시지 조회 테스트
// ============================================
export function messageReadTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = getUserRoomId(user.userId);
    if (!roomId) { sleep(0.2); return; }

    const r = Math.random();
    if (r < 0.5) {
        readMessagesP1(hdrs, roomId);
    } else if (r < 0.8) {
        readMessagesWithPagingP1(hdrs, roomId);
    } else {
        readRoomListP1(hdrs, user.userId);
    }

    sleep(Math.random() * 0.3 + 0.1);
}

function readMessagesP1(hdrs, roomId) {
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
        headers: hdrs,
        tags: { name: 'chat_messages' },
    });
    const elapsed = Date.now() - start;

    p1MsgListDuration.add(elapsed);
    const ok = res.status === 200;
    p1SuccessRate.add(ok ? 1 : 0);
    apiSuccessRate.add(ok ? 1 : 0);
    if (res.status >= 500) { p1Errors.add(1); serverErrors.add(1); }
    if (elapsed > 300) dbSlowQueries.add(1);

    check(res, { 'P1 MsgList 200': (r) => r.status === 200 });
}

function readMessagesWithPagingP1(hdrs, roomId) {
    // 첫 페이지
    const res1 = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
        headers: hdrs,
        tags: { name: 'chat_messages' },
    });

    p1SuccessRate.add(res1.status === 200 ? 1 : 0);
    apiSuccessRate.add(res1.status === 200 ? 1 : 0);

    if (res1.status === 200) {
        try {
            const body = JSON.parse(res1.body);
            const data = body.data || body;
            if (data.hasMore && data.nextCursorId && data.nextCursorAt) {
                // 두 번째 페이지
                const start2 = Date.now();
                const res2 = http.get(
                    `${BASE_URL}/api/v1/chat/${roomId}/messages?size=50&cursorId=${data.nextCursorId}&cursorAt=${data.nextCursorAt}`,
                    { headers: hdrs, tags: { name: 'chat_messages_page2' } }
                );
                const elapsed2 = Date.now() - start2;

                p1MsgListPageDur.add(elapsed2);
                p1SuccessRate.add(res2.status === 200 ? 1 : 0);
                apiSuccessRate.add(res2.status === 200 ? 1 : 0);
                if (res2.status >= 500) { p1Errors.add(1); serverErrors.add(1); }
            }
        } catch { /* ignore */ }
    }
}

function readRoomListP1(hdrs, userId) {
    const clubId = getClubIdForUser(userId);
    if (!clubId) { p1SuccessRate.add(1); return; }
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
        headers: hdrs,
        tags: { name: 'chat_room_list' },
    });
    const elapsed = Date.now() - start;

    p1RoomListDuration.add(elapsed);
    const ok = res.status === 200;
    p1SuccessRate.add(ok ? 1 : 0);
    apiSuccessRate.add(ok ? 1 : 0);
    if (res.status >= 500) { p1Errors.add(1); serverErrors.add(1); }
    if (elapsed > 500) dbSlowQueries.add(1);

    check(res, { 'P1 RoomList 200': (r) => r.status === 200 });
}

// ============================================
// Phase 2: 메시지 전송 테스트
// ============================================
export function messageSendTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = getUserRoomId(user.userId);
    if (!roomId) { sleep(0.2); return; }

    const start = Date.now();
    const payload = JSON.stringify({
        text: `k6-perf-test msg from user${user.userId} at ${Date.now()}`,
    });

    const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
        headers: hdrs,
        tags: { name: 'chat_send_message' },
    });
    const elapsed = Date.now() - start;

    p2SendDuration.add(elapsed);
    const ok = res.status === 200 || res.status === 201;
    p2SuccessRate.add(ok ? 1 : 0);
    apiSuccessRate.add(ok ? 1 : 0);
    if (res.status >= 500) { p2Errors.add(1); serverErrors.add(1); }

    check(res, { 'P2 Send 2xx': (r) => r.status === 200 || r.status === 201 });

    sleep(Math.random() * 0.5 + 0.2);
}

// ============================================
// Phase 3: 혼합 테스트
// ============================================
export function mixedTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = getUserRoomId(user.userId);
    if (!roomId) { sleep(0.2); return; }

    const r = Math.random();
    if (r < 0.40) {
        // 메시지 조회
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: hdrs,
            tags: { name: 'chat_messages' },
        });
        const elapsed = Date.now() - start;
        p3MsgListDuration.add(elapsed);
        p3SuccessRate.add(res.status === 200 ? 1 : 0);
        apiSuccessRate.add(res.status === 200 ? 1 : 0);
        if (res.status >= 500) { p3Errors.add(1); serverErrors.add(1); }
    } else if (r < 0.70) {
        // 메시지 전송
        const start = Date.now();
        const payload = JSON.stringify({
            text: `k6-mixed msg from user${user.userId}`,
        });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: hdrs,
            tags: { name: 'chat_send_message' },
        });
        const elapsed = Date.now() - start;
        p3SendDuration.add(elapsed);
        const ok = res.status === 200 || res.status === 201;
        p3SuccessRate.add(ok ? 1 : 0);
        apiSuccessRate.add(ok ? 1 : 0);
        if (res.status >= 500) { p3Errors.add(1); serverErrors.add(1); }
    } else if (r < 0.85) {
        // 채팅방 목록
        const clubId = getClubIdForUser(user.userId);
        if (!clubId) { p3SuccessRate.add(1); sleep(0.1); return; }
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/chat`, {
            headers: hdrs,
            tags: { name: 'chat_room_list' },
        });
        const elapsed = Date.now() - start;
        p3RoomListDuration.add(elapsed);
        p3SuccessRate.add(res.status === 200 ? 1 : 0);
        apiSuccessRate.add(res.status === 200 ? 1 : 0);
        if (res.status >= 500) { p3Errors.add(1); serverErrors.add(1); }
    } else {
        // 메시지 삭제 (본인 메시지)
        // 먼저 전송 후 삭제
        const sendPayload = JSON.stringify({
            text: `k6-delete-target from user${user.userId}`,
        });
        const sendRes = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, sendPayload, {
            headers: hdrs,
            tags: { name: 'chat_send_for_delete' },
        });

        if (sendRes.status === 200 || sendRes.status === 201) {
            try {
                const body = JSON.parse(sendRes.body);
                const msgId = body.data?.messageId || body.messageId;
                if (msgId) {
                    const start = Date.now();
                    const delRes = http.del(`${BASE_URL}/api/v1/chat/messages/${msgId}`, null, {
                        headers: hdrs,
                        tags: { name: 'chat_delete_message' },
                    });
                    const elapsed = Date.now() - start;
                    p3DeleteDuration.add(elapsed);
                    p3SuccessRate.add(delRes.status === 204 || delRes.status === 200 ? 1 : 0);
                    apiSuccessRate.add(delRes.status === 204 || delRes.status === 200 ? 1 : 0);
                    if (delRes.status >= 500) { p3Errors.add(1); serverErrors.add(1); }
                }
            } catch { /* ignore */ }
        }
    }

    sleep(Math.random() * 0.3 + 0.1);
}

// ============================================
// Phase 4: WebSocket 테스트
// ============================================
export function websocketTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const roomId = getUserRoomId(user.userId);
    if (!roomId) { sleep(0.5); return; }

    // WebSocket URL (native endpoint)
    const wsUrl = BASE_URL.replace('http://', 'ws://').replace('https://', 'wss://');

    const start = Date.now();

    const res = ws.connect(`${wsUrl}/ws-native`, { headers: { 'Authorization': `Bearer ${token}` } }, function (socket) {
        const connectTime = Date.now() - start;
        p4WsConnectDuration.add(connectTime);

        let connected = false;
        let msgReceived = false;

        socket.on('open', function () {
            connected = true;
            p4WsSuccess.add(1);

            // STOMP CONNECT frame
            socket.send('CONNECT\naccept-version:1.1,1.2\nheart-beat:0,0\nAuthorization:Bearer ' + token + '\n\n\0');
        });

        socket.on('message', function (msg) {
            // STOMP CONNECTED frame 수신 확인
            if (msg.startsWith('CONNECTED')) {
                // SUBSCRIBE to chat room
                socket.send('SUBSCRIBE\nid:sub-' + roomId + '\ndestination:/sub/chat/' + roomId + '/messages\n\n\0');

                // SEND a message
                const msgStart = Date.now();
                const payload = JSON.stringify({
                    text: 'k6-ws-test from user' + user.userId,
                });
                socket.send('SEND\ndestination:/pub/chat/' + roomId + '/messages\ncontent-type:application/json\n\n' + payload + '\0');

                // 메시지 왕복 시간 측정 (다음 MESSAGE 수신까지)
                socket.on('message', function (innerMsg) {
                    if (innerMsg.startsWith('MESSAGE') && !msgReceived) {
                        msgReceived = true;
                        p4WsMsgSendDuration.add(Date.now() - msgStart);
                    }
                });
            }
        });

        socket.on('error', function () {
            p4WsErrors.add(1);
            p4WsSuccess.add(0);
        });

        // 3초 후 종료
        socket.setTimeout(function () {
            // DISCONNECT frame
            socket.send('DISCONNECT\n\n\0');
            socket.close();
        }, 3000);
    });

    if (res.status !== 101) {
        p4WsErrors.add(1);
        p4WsSuccess.add(0);
    }

    sleep(Math.random() * 0.5 + 0.5);
}

// ============================================
// Phase 5: 스파이크 테스트
// ============================================
export function spikeTest() {
    const user = getUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roomId = getUserRoomId(user.userId);
    if (!roomId) { sleep(0.2); return; }

    if (Math.random() < 0.6) {
        // 읽기
        const start = Date.now();
        const res = http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`, {
            headers: hdrs,
            tags: { name: 'chat_messages' },
        });
        const elapsed = Date.now() - start;
        p5MsgListDuration.add(elapsed);
        p5SuccessRate.add(res.status === 200 ? 1 : 0);
        apiSuccessRate.add(res.status === 200 ? 1 : 0);
        if (res.status >= 500) { p5Errors.add(1); serverErrors.add(1); }
    } else {
        // 쓰기
        const start = Date.now();
        const payload = JSON.stringify({
            text: `k6-spike msg user${user.userId}`,
        });
        const res = http.post(`${BASE_URL}/api/v1/chat/${roomId}/messages`, payload, {
            headers: hdrs,
            tags: { name: 'chat_send_message' },
        });
        const elapsed = Date.now() - start;
        p5SendDuration.add(elapsed);
        const ok = res.status === 200 || res.status === 201;
        p5SuccessRate.add(ok ? 1 : 0);
        apiSuccessRate.add(ok ? 1 : 0);
        if (res.status >= 500) { p5Errors.add(1); serverErrors.add(1); }
    }

    sleep(Math.random() * 0.2);
}

// ============================================
// 결과 요약 — Phase별 분리 출력
// ============================================
export function handleSummary(data) {
    const m = data.metrics;
    const val = (metric, key) => metric?.values?.[key];
    const fmt = (v) => v != null ? v.toFixed(0) : '-';
    const fmtMs = (v) => v != null ? v.toFixed(1) : '-';
    const pct = (v) => v != null ? (v * 100).toFixed(1) : '-';

    const sections = [
        {
            name: 'Phase 1: 메시지 조회 (최대 400 VU)',
            list: val(m.p1_msg_list_duration, 'p(95)'),
            listAvg: val(m.p1_msg_list_duration, 'avg'),
            page2: val(m.p1_msg_list_page2_duration, 'p(95)'),
            roomList: val(m.p1_room_list_duration, 'p(95)'),
            roomListAvg: val(m.p1_room_list_duration, 'avg'),
            rate: val(m.p1_success_rate, 'rate'),
            err5xx: val(m.p1_5xx_errors, 'count'),
        },
        {
            name: 'Phase 2: 메시지 전송 (최대 300 VU)',
            send: val(m.p2_msg_send_duration, 'p(95)'),
            sendAvg: val(m.p2_msg_send_duration, 'avg'),
            rate: val(m.p2_success_rate, 'rate'),
            err5xx: val(m.p2_5xx_errors, 'count'),
        },
        {
            name: 'Phase 3: 혼합 (최대 400 VU)',
            list: val(m.p3_msg_list_duration, 'p(95)'),
            send: val(m.p3_msg_send_duration, 'p(95)'),
            roomList: val(m.p3_room_list_duration, 'p(95)'),
            del: val(m.p3_msg_delete_duration, 'p(95)'),
            rate: val(m.p3_success_rate, 'rate'),
            err5xx: val(m.p3_5xx_errors, 'count'),
        },
        {
            name: 'Phase 4: WebSocket (최대 300 VU)',
            wsConnect: val(m.p4_ws_connect_duration, 'p(95)'),
            wsRoundTrip: val(m.p4_ws_msg_round_trip, 'p(95)'),
            wsRate: val(m.p4_ws_success_rate, 'rate'),
            wsErr: val(m.p4_ws_errors, 'count'),
        },
        {
            name: 'Phase 5: 스파이크 (최대 500 VU)',
            list: val(m.p5_msg_list_duration, 'p(95)'),
            send: val(m.p5_msg_send_duration, 'p(95)'),
            rate: val(m.p5_success_rate, 'rate'),
            err5xx: val(m.p5_5xx_errors, 'count'),
        },
    ];

    console.log('\n╔══════════════════════════════════════════════════════════════╗');
    console.log('║              채팅 성능 부하 테스트 결과 (Phase별)               ║');
    console.log('╠══════════════════════════════════════════════════════════════╣');

    // Phase 1
    const s1 = sections[0];
    console.log(`║  ── ${s1.name.padEnd(42)}──  ║`);
    console.log(`║    메시지조회  p95: ${fmt(s1.list).padStart(7)}ms  avg: ${fmt(s1.listAvg).padStart(7)}ms          ║`);
    console.log(`║    2페이지     p95: ${fmt(s1.page2).padStart(7)}ms                              ║`);
    console.log(`║    채팅방목록  p95: ${fmt(s1.roomList).padStart(7)}ms  avg: ${fmt(s1.roomListAvg).padStart(7)}ms          ║`);
    console.log(`║    성공률: ${pct(s1.rate).padStart(5)}%  │  5xx: ${String(s1.err5xx || 0).padStart(5)}건                     ║`);
    console.log('║                                                              ║');

    // Phase 2
    const s2 = sections[1];
    console.log(`║  ── ${s2.name.padEnd(42)}──  ║`);
    console.log(`║    메시지전송  p95: ${fmt(s2.send).padStart(7)}ms  avg: ${fmt(s2.sendAvg).padStart(7)}ms          ║`);
    console.log(`║    성공률: ${pct(s2.rate).padStart(5)}%  │  5xx: ${String(s2.err5xx || 0).padStart(5)}건                     ║`);
    console.log('║                                                              ║');

    // Phase 3
    const s3 = sections[2];
    console.log(`║  ── ${s3.name.padEnd(42)}──  ║`);
    console.log(`║    메시지조회  p95: ${fmt(s3.list).padStart(7)}ms                              ║`);
    console.log(`║    메시지전송  p95: ${fmt(s3.send).padStart(7)}ms                              ║`);
    console.log(`║    채팅방목록  p95: ${fmt(s3.roomList).padStart(7)}ms                              ║`);
    console.log(`║    메시지삭제  p95: ${fmt(s3.del).padStart(7)}ms                              ║`);
    console.log(`║    성공률: ${pct(s3.rate).padStart(5)}%  │  5xx: ${String(s3.err5xx || 0).padStart(5)}건                     ║`);
    console.log('║                                                              ║');

    // Phase 4
    const s4 = sections[3];
    console.log(`║  ── ${s4.name.padEnd(42)}──  ║`);
    console.log(`║    WS연결      p95: ${fmt(s4.wsConnect).padStart(7)}ms                              ║`);
    console.log(`║    메시지왕복  p95: ${fmt(s4.wsRoundTrip).padStart(7)}ms                              ║`);
    console.log(`║    연결성공률: ${pct(s4.wsRate).padStart(5)}%  │  오류: ${String(s4.wsErr || 0).padStart(5)}건                ║`);
    console.log('║                                                              ║');

    // Phase 5
    const s5 = sections[4];
    console.log(`║  ── ${s5.name.padEnd(42)}──  ║`);
    console.log(`║    메시지조회  p95: ${fmt(s5.list).padStart(7)}ms                              ║`);
    console.log(`║    메시지전송  p95: ${fmt(s5.send).padStart(7)}ms                              ║`);
    console.log(`║    성공률: ${pct(s5.rate).padStart(5)}%  │  5xx: ${String(s5.err5xx || 0).padStart(5)}건                     ║`);

    // 통합
    const allRate = val(m.api_success_rate, 'rate') || 0;
    const allSlow = val(m.db_slow_queries, 'count') || 0;
    const all5xx = val(m.server_5xx_errors, 'count') || 0;
    console.log('╠══════════════════════════════════════════════════════════════╣');
    console.log(`║  API 성공률: ${pct(allRate).padStart(5)}% │ 슬로우: ${String(allSlow).padStart(5)}건 │ 5xx: ${String(all5xx).padStart(5)}건  ║`);
    console.log('╚══════════════════════════════════════════════════════════════╝');

    // 병목 경고
    const warnings = [];
    if (s1.list > 300)     warnings.push(`[P1] 메시지조회 p95 ${fmt(s1.list)}ms > 300ms`);
    if (s1.roomList > 500) warnings.push(`[P1] 채팅방목록 p95 ${fmt(s1.roomList)}ms > 500ms`);
    if (s2.send > 500)     warnings.push(`[P2] 메시지전송 p95 ${fmt(s2.send)}ms > 500ms`);
    if (s3.list > 500)     warnings.push(`[P3] 혼합 조회 p95 ${fmt(s3.list)}ms > 500ms`);
    if (s3.send > 800)     warnings.push(`[P3] 혼합 전송 p95 ${fmt(s3.send)}ms > 800ms`);
    if (s1.rate < 0.95)    warnings.push(`[P1] 성공률 ${pct(s1.rate)}% < 95%`);
    if (s2.rate < 0.95)    warnings.push(`[P2] 성공률 ${pct(s2.rate)}% < 95%`);
    if (s4.wsRate < 0.80)  warnings.push(`[P4] WS 성공률 ${pct(s4.wsRate)}% < 80%`);

    if (warnings.length > 0) {
        console.log('\n병목점 경고:');
        warnings.forEach(w => console.log(`  ${w}`));
    } else {
        console.log('\n주요 병목점 미발견 — 모든 Phase 정상');
    }

    return {
        'stdout': '',
        '/results/chat-perf-result.json': JSON.stringify(data, null, 2),
    };
}
