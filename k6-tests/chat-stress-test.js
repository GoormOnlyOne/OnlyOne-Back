// 채팅 도메인 극한 스트레스 테스트 - 브레이킹 포인트 탐색
//
// 이전 테스트(1500 VU)에서 100% 성공 → 더 강하게 밀어붙여 병목점 발견
//
// 시나리오:
//   1. 워밍업 (200 VU, 1m) - 기준선 확보
//   2. 동시 연결 폭발 (200→2000 VU, 3m) - 연결 한계 탐색
//   3. 최대 동시 연결 유지 (2000 VU, 2m) - 안정성 확인
//   4. 극한 부하 (2000→3000 VU, 2m) - 브레이킹 포인트
//   5. 초과 부하 (3000 VU, 2m) - 서버 한계 관찰
//   6. 핫 룸 집중 (500 VU, 2m) - 동일 채팅방 경합 테스트
//   7. 연결 폭풍 (1000 VU, 2m) - 초고속 connect/disconnect 반복
// 총 ~14분

import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter, Gauge } from 'k6/metrics';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// 커스텀 메트릭 - 병목 구간별
// ============================================

// 연결 메트릭
const wsConnectSuccess = new Rate('ws_connect_success');
const wsConnectTime = new Trend('ws_connect_time_ms');
const wsConnectFailed = new Counter('ws_connect_failed');
const wsConcurrentPeak = new Gauge('ws_concurrent_peak');

// 메시지 메트릭
const wsMessageSent = new Counter('ws_messages_sent');
const wsMessageReceived = new Counter('ws_messages_received');
const wsMsgRoundtrip = new Trend('ws_msg_roundtrip_ms');
const msgThroughput = new Rate('msg_throughput_ok');

// 에러 메트릭
const wsErrors = new Counter('ws_errors');
const wsStompErrors = new Counter('ws_stomp_errors');
const wsTimeouts = new Counter('ws_timeouts');

// 병목 구간 식별 메트릭
const dbWriteLatency = new Trend('db_write_latency_ms');      // async 메시지 저장 지연
const redisPublishFail = new Counter('redis_publish_fail');    // Redis pub/sub 실패
const stompQueueFull = new Counter('stomp_queue_full');        // STOMP 큐 포화
const connRefused = new Counter('conn_refused');               // 연결 거부

// 핫 룸 메트릭
const hotRoomLatency = new Trend('hot_room_latency_ms');
const hotRoomContention = new Rate('hot_room_contention');

// 연결 폭풍 메트릭
const reconnectSuccess = new Rate('reconnect_success');
const reconnectTime = new Trend('reconnect_time_ms');

// ============================================
// 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WS_URL = BASE_URL.replace('http://', 'ws://').replace('https://', 'wss://');
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key';

const TOTAL_ROOMS = 1000;
const USERS_PER_ROOM = 10;
const TOTAL_USERS = 10000;
const HOT_ROOMS = [1, 2, 3, 4, 5]; // 핫 룸 5개에 집중

export const options = {
    scenarios: {
        // Phase 1: 워밍업 - 기준선 확보
        warmup: {
            executor: 'constant-vus',
            exec: 'normalWorkload',
            vus: 200,
            duration: '1m',
            gracefulStop: '30s',
        },

        // Phase 2: 동시 연결 폭발 - 200→2000
        connection_explosion: {
            executor: 'ramping-vus',
            exec: 'aggressiveWorkload',
            startVUs: 200,
            stages: [
                { duration: '1m', target: 1000 },
                { duration: '1m', target: 1500 },
                { duration: '1m', target: 2000 },
            ],
            startTime: '1m10s',
            gracefulRampDown: '30s',
        },

        // Phase 3: 최대 동시 연결 유지
        sustain_max: {
            executor: 'constant-vus',
            exec: 'aggressiveWorkload',
            vus: 2000,
            duration: '2m',
            startTime: '4m20s',
            gracefulStop: '30s',
        },

        // Phase 4: 극한 부하 - 2000→3000
        extreme_ramp: {
            executor: 'ramping-vus',
            exec: 'aggressiveWorkload',
            startVUs: 2000,
            stages: [
                { duration: '1m', target: 2500 },
                { duration: '1m', target: 3000 },
            ],
            startTime: '6m30s',
            gracefulRampDown: '30s',
        },

        // Phase 5: 초과 부하 유지 - 3000 VU
        beyond_limit: {
            executor: 'constant-vus',
            exec: 'aggressiveWorkload',
            vus: 3000,
            duration: '2m',
            startTime: '8m40s',
            gracefulStop: '30s',
        },

        // Phase 6: 핫 룸 집중 - 소수 방에 대량 유저
        hot_room_contention: {
            executor: 'constant-vus',
            exec: 'hotRoomWorkload',
            vus: 500,
            duration: '2m',
            startTime: '10m50s',
            gracefulStop: '30s',
        },

        // Phase 7: 연결 폭풍 - 초고속 connect/disconnect
        connection_storm: {
            executor: 'constant-vus',
            exec: 'connectionStormWorkload',
            vus: 1000,
            duration: '2m',
            startTime: '13m',
            gracefulStop: '30s',
        },
    },

    thresholds: {
        // 느슨한 임계값 - 병목점 발견이 목표
        'ws_connect_success': ['rate>0.50'],           // 50% 이하면 심각한 병목
        'ws_connect_time_ms': ['p(95)<10000'],         // 10초 이상이면 연결 병목
        'ws_msg_roundtrip_ms': ['p(95)<15000'],        // 15초 이상이면 메시지 병목
        'hot_room_latency_ms': ['p(95)<20000'],        // 핫 룸 20초 이상이면 경합 병목
        'reconnect_time_ms': ['p(95)<8000'],           // 재연결 8초 이상이면 자원 해제 병목
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
        '재밌겠다ㅋㅋ', '좋은 아이디어!', '다음에 또 만나요',
        '오늘 날씨 좋네요', '맛집 추천해주세요!', '벌써 이 시간이네',
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
    const headers = {};
    for (let i = 1; i < headerLines.length; i++) {
        const colonIdx = headerLines[i].indexOf(':');
        if (colonIdx > 0) {
            headers[headerLines[i].substring(0, colonIdx)] = headerLines[i].substring(colonIdx + 1);
        }
    }
    const body = parts.length > 1 ? parts[1] : '';
    return { command, headers, body };
}

// ============================================
// Setup
// ============================================
export function setup() {
    console.log('╔══════════════════════════════════════════════╗');
    console.log('║   Chat WebSocket EXTREME Stress Test         ║');
    console.log('╠══════════════════════════════════════════════╣');
    console.log(`║ WS URL: ${WS_URL}/ws-native`);
    console.log(`║ DB: ${TOTAL_ROOMS} rooms, ${TOTAL_USERS} users`);
    console.log('║ Phases:');
    console.log('║   1. Warmup         200 VU    (0-1m)');
    console.log('║   2. Explosion      200→2000  (1-4m)');
    console.log('║   3. Sustain Max    2000 VU   (4-6m)');
    console.log('║   4. Extreme Ramp   2000→3000 (6-8m)');
    console.log('║   5. Beyond Limit   3000 VU   (8-10m)');
    console.log('║   6. Hot Room       500 VU    (10-12m)');
    console.log('║   7. Conn Storm     1000 VU   (13-15m)');
    console.log('╚══════════════════════════════════════════════╝');

    const healthRes = http.get(`${BASE_URL}/actuator/health`);
    check(healthRes, { 'Setup: health OK': (r) => r.status === 200 });

    // STOMP 연결 확인
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

    console.log(`STOMP connect verify: ${wsOk}`);
    check(null, { 'Setup: STOMP connected': () => wsOk });

    // 서버 메트릭 기준선
    const metricsRes = http.get(`${BASE_URL}/actuator/metrics/jvm.threads.live`);
    if (metricsRes.status === 200) {
        try {
            const data = JSON.parse(metricsRes.body);
            console.log(`Baseline JVM threads: ${data.measurements[0].value}`);
        } catch(e) {}
    }

    return { wsOk };
}

// ============================================
// Phase 1 & 기본: 일반 워크로드
// ============================================
export function normalWorkload() {
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
                const ct = Date.now() - connectStart;
                wsConnectTime.add(ct);
                wsConnectSuccess.add(true);

                socket.send(stompFrame('SUBSCRIBE', {
                    'id': 'sub-' + roomId,
                    'destination': '/sub/chat/' + roomId + '/messages',
                }));

                // 3~5 메시지, 2~4초 간격
                const msgTotal = Math.floor(Math.random() * 3) + 3;
                for (let i = 0; i < msgTotal; i++) {
                    socket.setTimeout(function () {
                        const sendTime = Date.now();
                        const chatMsg = JSON.stringify({
                            userId: kakaoId, text: randomText(), _ts: sendTime,
                        });
                        socket.send(stompFrame('SEND', {
                            'destination': '/pub/chat/' + roomId + '/messages',
                            'content-type': 'application/json',
                        }, chatMsg));
                        wsMessageSent.add(1);
                    }, (i + 1) * (Math.random() * 2000 + 2000));
                }
            }

            if (frame.command === 'MESSAGE') {
                wsMessageReceived.add(1);
                try {
                    const msgBody = JSON.parse(frame.body);
                    if (msgBody._ts && msgBody.userId === kakaoId) {
                        wsMsgRoundtrip.add(Date.now() - msgBody._ts);
                        msgThroughput.add(true);
                    }
                } catch (e) { /* ignore */ }
            }

            if (frame.command === 'ERROR') {
                wsStompErrors.add(1);
                const errMsg = frame.headers['message'] || frame.body || '';
                if (errMsg.includes('queue') || errMsg.includes('capacity')) {
                    stompQueueFull.add(1);
                }
            }
        });

        socket.on('error', function (e) {
            wsConnectSuccess.add(false);
            wsConnectFailed.add(1);
            if (e && e.error && e.error().includes('refused')) {
                connRefused.add(1);
            }
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
// Phase 2-5: 공격적 워크로드 (빠른 메시지, 짧은 세션)
// ============================================
export function aggressiveWorkload() {
    const userId = randomUserId();
    const roomId = getUserRoom(userId);
    const kakaoId = 10000000 + userId;
    const token = generateJWT(userId);

    const wsUrl = `${WS_URL}/ws-native`;
    const connectStart = Date.now();
    let connected = false;
    let msgSentCount = 0;

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
                const ct = Date.now() - connectStart;
                wsConnectTime.add(ct);
                wsConnectSuccess.add(true);

                // VU 수 추적
                wsConcurrentPeak.add(__VU);

                socket.send(stompFrame('SUBSCRIBE', {
                    'id': 'sub-' + roomId,
                    'destination': '/sub/chat/' + roomId + '/messages',
                }));

                // 10~20개 메시지를 0.1~0.5초 간격으로 빠르게 전송
                const msgTotal = Math.floor(Math.random() * 11) + 10;
                for (let i = 0; i < msgTotal; i++) {
                    socket.setTimeout(function () {
                        const sendTime = Date.now();
                        const chatMsg = JSON.stringify({
                            userId: kakaoId, text: randomText(), _ts: sendTime,
                        });
                        socket.send(stompFrame('SEND', {
                            'destination': '/pub/chat/' + roomId + '/messages',
                            'content-type': 'application/json',
                        }, chatMsg));
                        wsMessageSent.add(1);
                        msgSentCount++;
                    }, (i + 1) * (Math.random() * 400 + 100)); // 0.1~0.5초 간격
                }
            }

            if (frame.command === 'MESSAGE') {
                wsMessageReceived.add(1);
                try {
                    const msgBody = JSON.parse(frame.body);
                    if (msgBody._ts && msgBody.userId === kakaoId) {
                        const rt = Date.now() - msgBody._ts;
                        wsMsgRoundtrip.add(rt);
                        msgThroughput.add(rt < 5000);

                        // 메시지 저장 지연 (DB write 병목 감지)
                        dbWriteLatency.add(rt);
                    }
                } catch (e) { /* ignore */ }
            }

            if (frame.command === 'ERROR') {
                wsStompErrors.add(1);
                wsErrors.add(1);
                const errMsg = frame.headers['message'] || frame.body || '';
                if (errMsg.includes('queue') || errMsg.includes('capacity')) {
                    stompQueueFull.add(1);
                }
                if (errMsg.includes('redis') || errMsg.includes('Redis')) {
                    redisPublishFail.add(1);
                }
            }
        });

        socket.on('error', function (e) {
            wsConnectSuccess.add(false);
            wsConnectFailed.add(1);
            wsErrors.add(1);
        });

        // 5~10초 유지 (짧은 세션으로 연결 회전 빠르게)
        const holdTime = Math.floor(Math.random() * 5000) + 5000;
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

    sleep(0.2);
}

// ============================================
// Phase 6: 핫 룸 워크로드 - 소수 방에 대량 유저 집중
// ============================================
export function hotRoomWorkload() {
    const userId = randomUserId();
    const hotRoom = HOT_ROOMS[Math.floor(Math.random() * HOT_ROOMS.length)];
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
                const ct = Date.now() - connectStart;
                wsConnectTime.add(ct);
                wsConnectSuccess.add(true);
                hotRoomLatency.add(ct);

                // 핫 룸 구독
                socket.send(stompFrame('SUBSCRIBE', {
                    'id': 'sub-hot-' + hotRoom,
                    'destination': '/sub/chat/' + hotRoom + '/messages',
                }));

                // 핫 룸: 20~30개 메시지 초고속 전송 (0.05~0.2초)
                const msgTotal = Math.floor(Math.random() * 11) + 20;
                for (let i = 0; i < msgTotal; i++) {
                    socket.setTimeout(function () {
                        const sendTime = Date.now();
                        const chatMsg = JSON.stringify({
                            userId: kakaoId, text: randomText() + ' [hot:' + hotRoom + ']', _ts: sendTime,
                        });
                        socket.send(stompFrame('SEND', {
                            'destination': '/pub/chat/' + hotRoom + '/messages',
                            'content-type': 'application/json',
                        }, chatMsg));
                        wsMessageSent.add(1);
                    }, (i + 1) * (Math.random() * 150 + 50)); // 50~200ms 간격
                }
            }

            if (frame.command === 'MESSAGE') {
                wsMessageReceived.add(1);
                try {
                    const msgBody = JSON.parse(frame.body);
                    if (msgBody._ts && msgBody.userId === kakaoId) {
                        const rt = Date.now() - msgBody._ts;
                        hotRoomLatency.add(rt);
                        hotRoomContention.add(rt < 3000); // 3초 이내 성공률
                    }
                } catch (e) { /* ignore */ }
            }

            if (frame.command === 'ERROR') {
                wsStompErrors.add(1);
                hotRoomContention.add(false);
            }
        });

        socket.on('error', function (e) {
            wsConnectSuccess.add(false);
            wsConnectFailed.add(1);
            hotRoomContention.add(false);
        });

        // 8~15초 유지
        const holdTime = Math.floor(Math.random() * 7000) + 8000;
        socket.setTimeout(function () {
            if (connected) {
                socket.send(stompFrame('DISCONNECT', {}));
            }
            socket.close();
        }, holdTime);
    });

    if (!connected) {
        wsConnectSuccess.add(false);
        wsConnectFailed.add(1);
    }

    sleep(0.1);
}

// ============================================
// Phase 7: 연결 폭풍 - 초고속 connect → 메시지 1개 → disconnect
// ============================================
export function connectionStormWorkload() {
    const userId = randomUserId();
    const roomId = getUserRoom(userId);
    const kakaoId = 10000000 + userId;
    const token = generateJWT(userId);

    const wsUrl = `${WS_URL}/ws-native`;

    // 반복 3회: 빠른 connect/disconnect 사이클
    for (let cycle = 0; cycle < 3; cycle++) {
        const connectStart = Date.now();
        let connected = false;

        ws.connect(wsUrl, {}, function (socket) {
            socket.on('open', function () {
                socket.send(stompFrame('CONNECT', {
                    'accept-version': '1.1,1.2',
                    'heart-beat': '0,0',
                    'Authorization': 'Bearer ' + token,
                }));
            });

            socket.on('message', function (msg) {
                const frame = parseStompFrame(msg);

                if (frame.command === 'CONNECTED') {
                    connected = true;
                    const ct = Date.now() - connectStart;
                    reconnectTime.add(ct);
                    reconnectSuccess.add(true);

                    // 메시지 1개만 보내고 즉시 disconnect
                    const chatMsg = JSON.stringify({
                        userId: kakaoId, text: randomText(), _ts: Date.now(),
                    });
                    socket.send(stompFrame('SEND', {
                        'destination': '/pub/chat/' + roomId + '/messages',
                        'content-type': 'application/json',
                    }, chatMsg));
                    wsMessageSent.add(1);

                    // 0.5초 후 disconnect
                    socket.setTimeout(function () {
                        socket.send(stompFrame('DISCONNECT', {}));
                        socket.close();
                    }, 500);
                }

                if (frame.command === 'ERROR') {
                    wsStompErrors.add(1);
                }
            });

            socket.on('error', function () {
                reconnectSuccess.add(false);
                wsConnectFailed.add(1);
            });

            // 3초 타임아웃
            socket.setTimeout(function () {
                if (!connected) {
                    wsTimeouts.add(1);
                    reconnectSuccess.add(false);
                }
                socket.close();
            }, 3000);
        });

        if (!connected) {
            reconnectSuccess.add(false);
        }

        sleep(0.3); // 사이클 간 짧은 대기
    }
}

// ============================================
// Teardown - 결과 요약
// ============================================
export function teardown() {
    console.log('');
    console.log('╔══════════════════════════════════════════════╗');
    console.log('║   EXTREME Stress Test Completed              ║');
    console.log('╠══════════════════════════════════════════════╣');
    console.log('║ 결과 분석 가이드:');
    console.log('║');
    console.log('║ [연결 병목]');
    console.log('║   ws_connect_success < 80% → Tomcat/STOMP 스레드 포화');
    console.log('║   ws_connect_time p95 > 5s  → 연결 큐 대기 병목');
    console.log('║   conn_refused > 0          → 서버 연결 한계 초과');
    console.log('║');
    console.log('║ [메시지 병목]');
    console.log('║   ws_msg_roundtrip p95 > 5s → 메시지 처리 지연');
    console.log('║   db_write_latency p95 > 3s → DB 저장 병목 (HikariCP)');
    console.log('║   redis_publish_fail > 0    → Redis 연결 풀 고갈');
    console.log('║   stomp_queue_full > 0      → STOMP 큐 용량 부족');
    console.log('║');
    console.log('║ [경합 병목]');
    console.log('║   hot_room_contention < 70% → 핫 룸 경합 심각');
    console.log('║   hot_room_latency p95 > 10s → 단일 방 동시성 한계');
    console.log('║');
    console.log('║ [자원 해제 병목]');
    console.log('║   reconnect_success < 80%   → 연결 해제 지연');
    console.log('║   reconnect_time p95 > 5s   → 자원 반납 병목');
    console.log('╚══════════════════════════════════════════════╝');

    // 서버 최종 상태 확인
    try {
        const healthRes = http.get(`${BASE_URL}/actuator/health`);
        console.log(`Server health after test: ${healthRes.status}`);

        const threadsRes = http.get(`${BASE_URL}/actuator/metrics/jvm.threads.live`);
        if (threadsRes.status === 200) {
            const data = JSON.parse(threadsRes.body);
            console.log(`Final JVM threads: ${data.measurements[0].value}`);
        }

        const hikariRes = http.get(`${BASE_URL}/actuator/metrics/hikaricp.connections.active`);
        if (hikariRes.status === 200) {
            const data = JSON.parse(hikariRes.body);
            console.log(`Final HikariCP active: ${data.measurements[0].value}`);
        }
    } catch (e) {
        console.log(`Server may be down after stress test: ${e}`);
    }
}
