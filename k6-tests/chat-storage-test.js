// =============================================================
// chat-storage-test.js
// 채팅 메시지 Storage 추상화 부하 테스트 (MySQL vs MongoDB)
// =============================================================
//
// 실행 방법 (Docker):
//   MSYS_NO_PATHCONV=1 docker run --rm \
//     -v "$(pwd)/k6-tests:/scripts" \
//     --add-host=host.docker.internal:host-gateway \
//     grafana/k6:latest run /scripts/chat-storage-test.js
//
// 환경 변수:
//   BASE_URL     (기본: http://host.docker.internal:8080)
//   USER_COUNT   (기본: 1000) — 테스트 유저 수
//
// =============================================================
// 테스트 구조 (9 Phase, 총 ~11분):
//
//   Phase 1 — Warmup: JIT/커넥션 풀 워밍업
//   Phase 2 — Send Baseline: REST 메시지 전송(저장+발행) 단독
//   Phase 3 — List Latest: 최신 메시지 조회 (초기 로드)
//   Phase 4 — Cursor Pagination: 커서 기반 이전 메시지 조회
//   Phase 5 — Chat Room List: 채팅방 목록 + 마지막 메시지
//   Phase 6 — WebSocket STOMP: 실시간 연결+구독+전송+수신
//   Phase 7 — Mixed Realistic: REST + WebSocket 혼합
//   Phase 8 — Spike: 급격한 트래픽 증가
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

// Phase 2: 메시지 전송
const sendDuration   = new Trend('chat_send_duration',   true);
const sendSuccess    = new Rate('chat_send_success');
const sendErrors     = new Counter('chat_send_errors');

// Phase 3: 최신 메시지 조회
const listDuration   = new Trend('chat_list_duration',   true);
const listSuccess    = new Rate('chat_list_success');

// Phase 4: 커서 페이지네이션
const cursorDuration = new Trend('chat_cursor_duration', true);
const cursorSuccess  = new Rate('chat_cursor_success');

// Phase 5: 채팅방 목록
const roomsDuration  = new Trend('chat_rooms_duration',  true);
const roomsSuccess   = new Rate('chat_rooms_success');

// Phase 6: 혼합
const mixedDuration  = new Trend('chat_mixed_duration',  true);
const mixedSuccess   = new Rate('chat_mixed_success');

// Phase 7: 스파이크
const spikeDuration  = new Trend('chat_spike_duration',  true);
const spikeSuccess   = new Rate('chat_spike_success');
const spikeErrors    = new Counter('chat_spike_errors');

// Phase 2: 메시지 삭제
const deleteDuration = new Trend('chat_delete_duration', true);
const deleteSuccess  = new Rate('chat_delete_success');

// Phase 6: WebSocket STOMP
const wsConnectDuration = new Trend('ws_connect_duration', true);
const wsConnectSuccess  = new Rate('ws_connect_success');
const wsMsgSent         = new Counter('ws_msg_sent');
const wsMsgReceived     = new Counter('ws_msg_received');
const wsErrors          = new Counter('ws_errors');

// ============================================
// 설정
// ============================================
const USER_COUNT = parseInt(__ENV.USER_COUNT || '1000');

// 시드 데이터 기준 대형 채팅방 (멤버 989~996명, 거의 전 유저 참여)
// CLUB: 64, 159, 381, 501, 747   SCHEDULE: 864(club64), 959(club159)
const TOP_ROOM_IDS = [64, 159, 381, 501, 747, 864, 959];

// 채팅방 목록 API에 사용할 club_id (대형 CLUB 방의 club_id)
const CLUB_IDS = [64, 159, 381, 501, 747];

export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: 10,
            duration: '20s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },
        // Phase 2: 메시지 전송 (POST — 저장 + Redis 발행)
        send_baseline: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 100 },
                { duration: '50s', target: 100 },
                { duration: '10s', target: 0 },
            ],
            startTime: '25s',
            exec: 'sendBaseline',
            tags: { phase: 'send' },
        },
        // Phase 3: 최신 메시지 조회 (GET — 초기 로드)
        list_latest: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 150 },
                { duration: '50s', target: 150 },
                { duration: '10s', target: 0 },
            ],
            startTime: '100s',
            exec: 'listLatest',
            tags: { phase: 'list' },
        },
        // Phase 4: 커서 페이지네이션 (이전 메시지)
        cursor_pagination: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 100 },
                { duration: '50s', target: 100 },
                { duration: '10s', target: 0 },
            ],
            startTime: '180s',
            exec: 'cursorPagination',
            tags: { phase: 'cursor' },
        },
        // Phase 5: 채팅방 목록 + 마지막 메시지
        room_list: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 100 },
                { duration: '50s', target: 100 },
                { duration: '10s', target: 0 },
            ],
            startTime: '260s',
            exec: 'roomList',
            tags: { phase: 'rooms' },
        },
        // Phase 6: WebSocket STOMP (연결+구독+전송+수신)
        websocket_stomp: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 100 },
                { duration: '50s', target: 100 },
                { duration: '10s', target: 0 },
            ],
            startTime: '340s',
            exec: 'websocketStomp',
            tags: { phase: 'websocket' },
        },
        // Phase 7: 실제 사용 패턴 혼합 (REST + WebSocket)
        mixed_realistic: {
            executor: 'ramping-vus',
            startVUs: 20,
            stages: [
                { duration: '20s', target: 200 },
                { duration: '60s', target: 200 },
                { duration: '15s', target: 0 },
            ],
            startTime: '420s',
            exec: 'mixedRealistic',
            tags: { phase: 'mixed' },
        },
        // Phase 8: 스파이크
        spike: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '10s', target: 300 },
                { duration: '25s', target: 300 },
                { duration: '10s', target: 10 },
                { duration: '15s', target: 10 },
            ],
            startTime: '520s',
            exec: 'spikeTest',
            tags: { phase: 'spike' },
        },
    },

    thresholds: {
        // 메시지 전송: p95 < 2000ms
        'chat_send_duration':   ['p(95)<2000'],
        // 최신 목록: p95 < 1000ms
        'chat_list_duration':   ['p(95)<1000'],
        // 커서 페이지네이션: p95 < 2000ms
        'chat_cursor_duration': ['p(95)<2000'],
        // 채팅방 목록: p95 < 3000ms
        'chat_rooms_duration':  ['p(95)<3000'],
        // WebSocket 연결: p95 < 3000ms
        'ws_connect_duration':  ['p(95)<3000'],
        // WebSocket 연결 성공률 > 90%
        'ws_connect_success':   ['rate>0.90'],
        // 혼합: 성공률 > 95%
        'chat_mixed_success':   ['rate>0.95'],
        // 스파이크: 성공률 > 90%
        'chat_spike_success':   ['rate>0.90'],
    },
};

// ============================================
// 유저/채팅방 유틸
// ============================================
function testUser(vuId) {
    const userId = (vuId % USER_COUNT) + 1;
    return {
        userId,
        kakaoId: 10000000 + userId,
        status: 'ACTIVE',
        role: 'ROLE_USER',
    };
}

function randomUser() {
    const userId = Math.floor(Math.random() * USER_COUNT) + 1;
    return {
        userId,
        kakaoId: 10000000 + userId,
        status: 'ACTIVE',
        role: 'ROLE_USER',
    };
}

function randomRoomId() {
    return TOP_ROOM_IDS[Math.floor(Math.random() * TOP_ROOM_IDS.length)];
}

function randomClubId() {
    return CLUB_IDS[Math.floor(Math.random() * CLUB_IDS.length)];
}

// ============================================
// Phase 1: Warmup
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    const roomId = randomRoomId();

    http.get(`${BASE_URL}/api/v1/chat/${roomId}/messages?size=5`, {
        headers: headers(token),
        tags: { name: 'warmup_list' },
    });

    sleep(0.5);
}

// ============================================
// Phase 2: 메시지 전송
// ============================================
export function sendBaseline() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const msgNum = Math.floor(Math.random() * 100000);

    group('send_message', () => {
        const payload = JSON.stringify({
            text: `k6-storage-test msg from user${user.userId} #${msgNum}`,
        });

        const res = http.post(
            `${BASE_URL}/api/v1/chat/${roomId}/messages`,
            payload,
            {
                headers: headers(token),
                tags: { name: 'chat_send' },
            }
        );

        const dur = res.timings.duration;
        sendDuration.add(dur);
        const ok = res.status === 200;
        sendSuccess.add(ok);
        if (!ok) sendErrors.add(1);

        check(res, {
            'send status 200': (r) => r.status === 200,
            'send has messageId': (r) => {
                try {
                    const body = JSON.parse(r.body);
                    return body.data && body.data.messageId > 0;
                } catch (e) { return false; }
            },
        });

        // 전송 직후 삭제 테스트 (10% 확률)
        if (ok && Math.random() < 0.1) {
            try {
                const body = JSON.parse(res.body);
                const msgId = body.data.messageId;
                const delRes = http.del(
                    `${BASE_URL}/api/v1/chat/messages/${msgId}`,
                    null,
                    {
                        headers: headers(token),
                        tags: { name: 'chat_delete' },
                    }
                );
                deleteDuration.add(delRes.timings.duration);
                deleteSuccess.add(delRes.status === 204);
            } catch (e) { /* ignore */ }
        }
    });

    sleep(0.1 + Math.random() * 0.2);
}

// ============================================
// Phase 3: 최신 메시지 조회 (초기 로드)
// ============================================
export function listLatest() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();

    group('list_latest', () => {
        const res = http.get(
            `${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`,
            {
                headers: headers(token),
                tags: { name: 'chat_list_latest' },
            }
        );

        listDuration.add(res.timings.duration);
        const ok = res.status === 200;
        listSuccess.add(ok);

        check(res, {
            'list status 200': (r) => r.status === 200,
            'list has messages': (r) => {
                try {
                    const body = JSON.parse(r.body);
                    return body.data && Array.isArray(body.data.messages);
                } catch (e) { return false; }
            },
        });
    });

    sleep(0.1 + Math.random() * 0.2);
}

// ============================================
// Phase 4: 커서 페이지네이션 (이전 메시지)
// ============================================
export function cursorPagination() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();

    group('cursor_pagination', () => {
        // 1차: 최신 페이지
        const res1 = http.get(
            `${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`,
            {
                headers: headers(token),
                tags: { name: 'chat_cursor_first' },
            }
        );

        if (res1.status !== 200) {
            cursorDuration.add(res1.timings.duration);
            cursorSuccess.add(false);
            return;
        }

        cursorDuration.add(res1.timings.duration);
        cursorSuccess.add(true);

        // 2차: 커서 기반 이전 페이지
        try {
            const body = JSON.parse(res1.body);
            const data = body.data;
            if (data.hasMore && data.nextCursorId && data.nextCursorAt) {
                const res2 = http.get(
                    `${BASE_URL}/api/v1/chat/${roomId}/messages?size=20&cursorId=${data.nextCursorId}&cursorAt=${data.nextCursorAt}`,
                    {
                        headers: headers(token),
                        tags: { name: 'chat_cursor_next' },
                    }
                );
                cursorDuration.add(res2.timings.duration);
                cursorSuccess.add(res2.status === 200);

                check(res2, {
                    'cursor next status 200': (r) => r.status === 200,
                    'cursor next has messages': (r) => {
                        try {
                            const b = JSON.parse(r.body);
                            return b.data && b.data.messages.length > 0;
                        } catch (e) { return false; }
                    },
                });
            }
        } catch (e) { /* ignore */ }
    });

    sleep(0.1 + Math.random() * 0.2);
}

// ============================================
// Phase 5: 채팅방 목록 + 마지막 메시지
// ============================================
export function roomList() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const clubId = randomClubId();

    group('room_list', () => {
        const res = http.get(
            `${BASE_URL}/api/v1/clubs/${clubId}/chat`,
            {
                headers: headers(token),
                tags: { name: 'chat_room_list' },
            }
        );

        roomsDuration.add(res.timings.duration);
        const ok = res.status === 200;
        roomsSuccess.add(ok);

        check(res, {
            'rooms status 200': (r) => r.status === 200,
        });
    });

    sleep(0.2 + Math.random() * 0.3);
}

// ============================================
// Phase 6: WebSocket STOMP
//   CONNECT → SUBSCRIBE → SEND 메시지 × N → 수신 확인 → DISCONNECT
// ============================================
export function websocketStomp() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const connectStart = Date.now();

    const res = ws.connect(WS_URL, null, function (socket) {
        let connected = false;
        let subscribed = false;
        let msgSentCount = 0;
        let msgRecvCount = 0;
        const MSGS_TO_SEND = 5;

        socket.on('open', function () {
            // STOMP CONNECT
            socket.send(
                'CONNECT\n' +
                'accept-version:1.2\n' +
                'Authorization:Bearer ' + token + '\n' +
                'heart-beat:0,0\n' +
                '\n\0'
            );
        });

        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED')) {
                connected = true;
                wsConnectDuration.add(Date.now() - connectStart);
                wsConnectSuccess.add(true);

                // STOMP SUBSCRIBE
                socket.send(
                    'SUBSCRIBE\n' +
                    'id:sub-0\n' +
                    'destination:/sub/chat/' + roomId + '/messages\n' +
                    '\n\0'
                );
                subscribed = true;

                // 메시지 전송 시작
                for (let i = 0; i < MSGS_TO_SEND; i++) {
                    const body = JSON.stringify({
                        text: 'k6-ws msg ' + __VU + '-' + i,
                        imageUrl: null,
                    });
                    socket.send(
                        'SEND\n' +
                        'destination:/pub/chat/' + roomId + '/messages\n' +
                        'content-type:application/json\n' +
                        '\n' +
                        body + '\0'
                    );
                    msgSentCount++;
                    wsMsgSent.add(1);
                }
            }

            if (data.startsWith('MESSAGE')) {
                msgRecvCount++;
                wsMsgReceived.add(1);
            }

            if (data.startsWith('ERROR')) {
                wsErrors.add(1);
            }
        });

        socket.on('error', function (e) {
            wsConnectSuccess.add(false);
            wsErrors.add(1);
        });

        // 수신 대기 후 종료
        socket.setTimeout(function () {
            // STOMP DISCONNECT
            socket.send('DISCONNECT\nreceipt:disc-0\n\n\0');
            socket.close();
        }, 3000);
    });

    if (res.status !== 101) {
        wsConnectSuccess.add(false);
        wsConnectDuration.add(Date.now() - connectStart);
    }

    sleep(0.1);
}

// ============================================
// Phase 7: 실제 사용 패턴 혼합
//   - 40% 최신 메시지 조회
//   - 25% 메시지 전송
//   - 20% 커서 페이지네이션
//   - 15% 채팅방 목록
// ============================================
export function mixedRealistic() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const roll = Math.random();
    let ok = false;

    if (roll < 0.40) {
        // 40%: 최신 메시지 조회
        const res = http.get(
            `${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`,
            { headers: headers(token), tags: { name: 'mixed_list' } }
        );
        mixedDuration.add(res.timings.duration);
        ok = res.status === 200;

    } else if (roll < 0.65) {
        // 25%: 메시지 전송
        const payload = JSON.stringify({
            text: `k6-mixed msg from user${user.userId}`,
        });
        const res = http.post(
            `${BASE_URL}/api/v1/chat/${roomId}/messages`,
            payload,
            { headers: headers(token), tags: { name: 'mixed_send' } }
        );
        mixedDuration.add(res.timings.duration);
        ok = res.status === 200;

    } else if (roll < 0.85) {
        // 20%: 커서 기반 조회
        const res = http.get(
            `${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`,
            { headers: headers(token), tags: { name: 'mixed_cursor' } }
        );
        mixedDuration.add(res.timings.duration);
        ok = res.status === 200;

    } else {
        // 15%: 채팅방 목록
        const clubId = randomClubId();
        const res = http.get(
            `${BASE_URL}/api/v1/clubs/${clubId}/chat`,
            { headers: headers(token), tags: { name: 'mixed_rooms' } }
        );
        mixedDuration.add(res.timings.duration);
        ok = res.status === 200;
    }

    mixedSuccess.add(ok);
    sleep(0.05 + Math.random() * 0.15);
}

// ============================================
// Phase 7: 스파이크
// ============================================
export function spikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const roomId = randomRoomId();
    const ops = ['list', 'send', 'cursor'];
    const op = ops[Math.floor(Math.random() * ops.length)];
    let ok = false;

    if (op === 'list') {
        const res = http.get(
            `${BASE_URL}/api/v1/chat/${roomId}/messages?size=50`,
            { headers: headers(token), tags: { name: 'spike_list' } }
        );
        spikeDuration.add(res.timings.duration);
        ok = res.status === 200;

    } else if (op === 'send') {
        const payload = JSON.stringify({
            text: `k6-spike msg from user${user.userId}`,
        });
        const res = http.post(
            `${BASE_URL}/api/v1/chat/${roomId}/messages`,
            payload,
            { headers: headers(token), tags: { name: 'spike_send' } }
        );
        spikeDuration.add(res.timings.duration);
        ok = res.status === 200;

    } else {
        const res = http.get(
            `${BASE_URL}/api/v1/chat/${roomId}/messages?size=20`,
            { headers: headers(token), tags: { name: 'spike_cursor' } }
        );
        spikeDuration.add(res.timings.duration);
        ok = res.status === 200;
    }

    spikeSuccess.add(ok);
    if (!ok) spikeErrors.add(1);
    sleep(0.05);
}

// ============================================
// 기본 함수 (fallback)
// ============================================
export default function () {
    warmup();
}
