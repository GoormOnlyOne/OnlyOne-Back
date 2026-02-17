// 채팅 도메인 고강도 부하 테스트 - 병목지점 탐색
//
// WebSocket STOMP 프로토콜 기반 극한 부하 테스트
// - DB: 1,000 채팅방, 10,000 user_chat_room (방당 10명), 1,000,000 메시지
// - 목표: 서버 한계점(병목) 발견 - 연결 실패, 메시지 지연, 타임아웃 등
//
// 시나리오 (점진적 부하 증가):
//   1. 워밍업 (100 VU, 1m) - 기본 동작 확인
//   2. 중부하 (0→500 VU, 3m) - 연결 스트레스 증가
//   3. 고부하 유지 (500 VU, 2m) - 안정성 확인
//   4. 극한 부하 (500→1000 VU, 3m) - 한계 도달
//   5. 최대 부하 (1000 VU, 2m) - 병목 관찰
//   6. 초과 부하 (1000→1500 VU, 2m) - 브레이킹 포인트
// 총 ~13분

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// 커스텀 메트릭
// ============================================
const wsConnectSuccess = new Rate('ws_connect_success');
const wsConnectTime = new Trend('ws_connect_time_ms');
const wsMessageSent = new Counter('ws_messages_sent');
const wsMessageReceived = new Counter('ws_messages_received');
const wsMsgRoundtrip = new Trend('ws_msg_roundtrip_ms');
const wsConnectFailed = new Counter('ws_connect_failed');
const wsErrors = new Counter('ws_errors');
const totalChatOps = new Counter('total_chat_ops');
const msgSaveLatency = new Trend('msg_save_latency_ms');

// ============================================
// 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WS_URL = BASE_URL.replace('http://', 'ws://').replace('https://', 'wss://');
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key';

const TOTAL_ROOMS = 1000;
const USERS_PER_ROOM = 10;
const TOTAL_USERS = 10000;

export const options = {
    scenarios: {
        // 1. 워밍업 - 100 VU 기본 확인
        warmup: {
            executor: 'constant-vus',
            exec: 'wsWorkload',
            vus: 100,
            duration: '1m',
            gracefulStop: '30s',
        },

        // 2. 중부하 - 0→500 VU 점진 증가
        ramp_mid: {
            executor: 'ramping-vus',
            exec: 'wsBurstWorkload',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 250 },
                { duration: '1m', target: 500 },
                { duration: '1m', target: 500 },
            ],
            startTime: '1m10s',
            gracefulRampDown: '30s',
        },

        // 3. 고부하 유지 - 500 VU 지속
        sustain_high: {
            executor: 'constant-vus',
            exec: 'wsBurstWorkload',
            vus: 500,
            duration: '2m',
            startTime: '4m20s',
            gracefulStop: '30s',
        },

        // 4. 극한 부하 - 500→1000 VU
        ramp_extreme: {
            executor: 'ramping-vus',
            exec: 'wsBurstWorkload',
            startVUs: 500,
            stages: [
                { duration: '1m', target: 750 },
                { duration: '1m', target: 1000 },
                { duration: '1m', target: 1000 },
            ],
            startTime: '6m30s',
            gracefulRampDown: '30s',
        },

        // 5. 최대 부하 - 1000 VU 유지
        sustain_max: {
            executor: 'constant-vus',
            exec: 'wsBurstWorkload',
            vus: 1000,
            duration: '2m',
            startTime: '9m40s',
            gracefulStop: '30s',
        },

        // 6. 초과 부하 - 1000→1500 VU (브레이킹 포인트)
        breaking_point: {
            executor: 'ramping-vus',
            exec: 'wsBurstWorkload',
            startVUs: 1000,
            stages: [
                { duration: '1m', target: 1250 },
                { duration: '1m', target: 1500 },
            ],
            startTime: '11m50s',
            gracefulRampDown: '30s',
        },
    },

    thresholds: {
        // 병목 탐색용: 느슨한 임계값 (실패 시 병목 발견)
        'ws_connect_success': ['rate>0.70'],     // 70% 이하면 연결 병목
        'ws_connect_time_ms': ['p(95)<5000'],    // 5초 이상이면 연결 지연 병목
        'ws_msg_roundtrip_ms': ['p(95)<10000'],  // 10초 이상이면 메시지 처리 병목
    },
};

// ============================================
// 유틸리티
// ============================================
function getUserRoom(userId) {
    return Math.ceil(userId / USERS_PER_ROOM);
}

function randomUserId() {
    return Math.floor(Math.random() * TOTAL_USERS) + 1;
}

function randomText() {
    const texts = [
        '안녕하세요!', '오늘 모임 어디서 해요?', '네 좋아요~',
        '시간 괜찮으시죠?', '장소 정했나요?', '저도 참여할게요!',
        '다들 잘 지내시죠?', '오랜만이에요~', '주말에 봐요!',
        '사진 공유해주세요', '감사합니다!', '알겠습니다~',
    ];
    return texts[Math.floor(Math.random() * texts.length)];
}

// ============================================
// JWT 생성
// ============================================
function generateJWT(userId) {
    const kakaoId = 10000000 + userId;
    const now = Date.now();
    const header = { alg: 'HS512', typ: 'JWT' };
    const payload = {
        sub: userId.toString(),
        kakaoId: kakaoId.toString(),
        nickname: 'testuser' + userId,
        status: 'ACTIVE',
        role: 'ROLE_USER',
        type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor((now + 3600000) / 1000),
    };
    const h = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const p = encoding.b64encode(JSON.stringify(payload), 'rawurl');
    const sig = hmac('sha512', JWT_SECRET, h + '.' + p, 'base64rawurl');
    return h + '.' + p + '.' + sig;
}

// ============================================
// STOMP 프레임 유틸
// ============================================
function stompFrame(command, hdrs, body) {
    let frame = command + '\n';
    for (const key of Object.keys(hdrs || {})) {
        frame += key + ':' + hdrs[key] + '\n';
    }
    frame += '\n';
    if (body) frame += body;
    frame += '\0';
    return frame;
}

function parseStompFrame(data) {
    const nullIdx = data.indexOf('\0');
    const raw = nullIdx >= 0 ? data.substring(0, nullIdx) : data;
    const parts = raw.split('\n\n');
    const headerLines = parts[0].split('\n');
    const command = headerLines[0];
    const body = parts.length > 1 ? parts[1] : '';
    return { command, body };
}

// ============================================
// Setup
// ============================================
export function setup() {
    console.log('=== Chat WebSocket Bottleneck Test ===');
    console.log(`WS URL: ${WS_URL}/ws-native`);
    console.log(`DB: ${TOTAL_ROOMS} rooms, ${TOTAL_USERS} users, 1M messages`);
    console.log(`Max VU: 1500, Duration: ~14 min`);
    console.log('======================================');

    const healthRes = http.get(`${BASE_URL}/actuator/health`);
    check(healthRes, { 'Setup: health OK': (r) => r.status === 200 });

    // STOMP 연결 확인 (JWT 인증 포함)
    const setupToken = generateJWT(1);
    let wsOk = false;
    ws.connect(`${WS_URL}/ws-native`, {}, function (socket) {
        socket.on('open', function () {
            socket.send(stompFrame('CONNECT', {
                'accept-version': '1.1,1.2',
                'heart-beat': '0,0',
                'Authorization': 'Bearer ' + setupToken,
            }));
        });
        socket.on('message', function (msg) {
            const frame = parseStompFrame(msg);
            if (frame.command === 'CONNECTED') {
                wsOk = true;
                socket.send(stompFrame('DISCONNECT', {}));
                socket.close();
            }
        });
        socket.setTimeout(function () { socket.close(); }, 5000);
    });

    console.log(`STOMP connect: ${wsOk}`);
    check(null, { 'Setup: STOMP connected': () => wsOk });
    return { wsOk };
}

// ============================================
// 일반 워크로드 (연결 + 메시지 3~5개, 긴 세션)
// ============================================
export function wsWorkload() {
    const userId = randomUserId();
    const roomId = getUserRoom(userId);
    const kakaoId = 10000000 + userId;
    const token = generateJWT(userId);

    const wsUrl = `${WS_URL}/ws-native`;
    const connectStart = Date.now();
    let connected = false;

    ws.connect(wsUrl, {}, function (socket) {
        socket.on('open', function () {
            socket.send(stompFrame('CONNECT', {
                'accept-version': '1.1,1.2',
                'heart-beat': '10000,10000',
                'Authorization': 'Bearer ' + token,
            }));
        });

        socket.on('message', function (msg) {
            const frame = parseStompFrame(msg);

            if (frame.command === 'CONNECTED') {
                connected = true;
                wsConnectTime.add(Date.now() - connectStart);
                wsConnectSuccess.add(true);

                socket.send(stompFrame('SUBSCRIBE', {
                    'id': 'sub-' + roomId,
                    'destination': '/sub/chat/' + roomId + '/messages',
                }));

                const msgTotal = Math.floor(Math.random() * 3) + 3;
                for (let i = 0; i < msgTotal; i++) {
                    socket.setTimeout(function () {
                        const sendTime = Date.now();
                        const chatMsg = JSON.stringify({
                            userId: kakaoId,
                            text: randomText(),
                            _ts: sendTime,
                        });
                        socket.send(stompFrame('SEND', {
                            'destination': '/pub/chat/' + roomId + '/messages',
                            'content-type': 'application/json',
                        }, chatMsg));
                        wsMessageSent.add(1);
                        totalChatOps.add(1);
                    }, (i + 1) * (Math.random() * 2000 + 2000));
                }
            }

            if (frame.command === 'MESSAGE') {
                wsMessageReceived.add(1);
                try {
                    const msgBody = JSON.parse(frame.body);
                    if (msgBody._ts && msgBody.userId === kakaoId) {
                        wsMsgRoundtrip.add(Date.now() - msgBody._ts);
                    }
                } catch (e) { /* ignore */ }
            }

            if (frame.command === 'ERROR') {
                wsErrors.add(1);
            }
        });

        socket.on('error', function () {
            wsConnectSuccess.add(false);
            wsConnectFailed.add(1);
        });

        const holdTime = Math.floor(Math.random() * 10000) + 15000;
        socket.setTimeout(function () {
            if (connected) {
                socket.send(stompFrame('DISCONNECT', { 'receipt': 'disc-1' }));
            }
            socket.close();
        }, holdTime);
    });

    if (!connected) {
        wsConnectSuccess.add(false);
        wsConnectFailed.add(1);
    }

    sleep(0.5);
}

// ============================================
// 버스트 워크로드 (빠른 메시지, 짧은 세션)
// ============================================
export function wsBurstWorkload() {
    const userId = randomUserId();
    const roomId = getUserRoom(userId);
    const kakaoId = 10000000 + userId;
    const token = generateJWT(userId);

    const wsUrl = `${WS_URL}/ws-native`;
    const connectStart = Date.now();
    let connected = false;

    ws.connect(wsUrl, {}, function (socket) {
        socket.on('open', function () {
            socket.send(stompFrame('CONNECT', {
                'accept-version': '1.1,1.2',
                'heart-beat': '10000,10000',
                'Authorization': 'Bearer ' + token,
            }));
        });

        socket.on('message', function (msg) {
            const frame = parseStompFrame(msg);

            if (frame.command === 'CONNECTED') {
                connected = true;
                wsConnectTime.add(Date.now() - connectStart);
                wsConnectSuccess.add(true);

                socket.send(stompFrame('SUBSCRIBE', {
                    'id': 'sub-' + roomId,
                    'destination': '/sub/chat/' + roomId + '/messages',
                }));

                // 메시지 8~15개 빠르게 전송 (0.3~1초 간격)
                const msgTotal = Math.floor(Math.random() * 8) + 8;
                for (let i = 0; i < msgTotal; i++) {
                    socket.setTimeout(function () {
                        const sendTime = Date.now();
                        const chatMsg = JSON.stringify({
                            userId: kakaoId,
                            text: randomText(),
                            _ts: sendTime,
                        });
                        socket.send(stompFrame('SEND', {
                            'destination': '/pub/chat/' + roomId + '/messages',
                            'content-type': 'application/json',
                        }, chatMsg));
                        wsMessageSent.add(1);
                        totalChatOps.add(1);
                    }, (i + 1) * (Math.random() * 700 + 300));
                }
            }

            if (frame.command === 'MESSAGE') {
                wsMessageReceived.add(1);
                try {
                    const msgBody = JSON.parse(frame.body);
                    if (msgBody._ts && msgBody.userId === kakaoId) {
                        wsMsgRoundtrip.add(Date.now() - msgBody._ts);
                    }
                } catch (e) { /* ignore */ }
            }

            if (frame.command === 'ERROR') {
                wsErrors.add(1);
            }
        });

        socket.on('error', function () {
            wsConnectSuccess.add(false);
            wsConnectFailed.add(1);
        });

        // 6~12초 후 disconnect (짧은 세션)
        const holdTime = Math.floor(Math.random() * 6000) + 6000;
        socket.setTimeout(function () {
            if (connected) {
                socket.send(stompFrame('DISCONNECT', { 'receipt': 'disc-1' }));
            }
            socket.close();
        }, holdTime);
    });

    if (!connected) {
        wsConnectSuccess.add(false);
        wsConnectFailed.add(1);
    }

    sleep(0.3);
}

// ============================================
// Teardown
// ============================================
export function teardown() {
    console.log('=== Chat WebSocket Bottleneck Test Completed ===');
}
