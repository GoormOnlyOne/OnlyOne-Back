import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';
import ws from 'k6/ws';

// ============================================
// Chat 도메인 부하 테스트
// 대상 병목: REST 메시지 처리량, 커서 페이징, WebSocket STOMP, Redis Pub/Sub
// 총 소요시간: ~32분
// ============================================

// 커스텀 메트릭
const errorRate = new Rate('errors');
const chatSendDuration = new Trend('chat_send_duration');
const chatHistoryDuration = new Trend('chat_history_duration');
const wsConnectionDuration = new Trend('ws_connection_duration');
const wsMessageLatency = new Trend('ws_message_latency');
const messageDeliveryRate = new Rate('message_delivery_rate');
const wsConnectionFailRate = new Rate('ws_connection_fail_rate');
const messagesSent = new Counter('messages_sent');
const messagesReceived = new Counter('messages_received');

// ============================================
// 테스트 설정
// ============================================
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const WS_URL = __ENV.WS_URL || 'ws://localhost:8080/ws';
const JWT_SECRET = __ENV.JWT_SECRET || 'test-secret-key-for-testing-min-256-bits';

export const options = {
    scenarios: {
        // 시나리오 1: REST 메시지 처리량
        rest_message_throughput: {
            executor: 'ramping-arrival-rate',
            exec: 'restMessageThroughput',
            startRate: 50,
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 300,
            stages: [
                { duration: '1m', target: 100 },
                { duration: '2m', target: 500 },
                { duration: '2m', target: 1000 },
                { duration: '1m', target: 200 },
            ],
            gracefulStop: '30s',
        },

        // 시나리오 2: 채팅 히스토리 커서 페이징
        chat_history_pagination: {
            executor: 'constant-vus',
            exec: 'chatHistoryPagination',
            vus: 200,
            duration: '4m',
            startTime: '7m',
            gracefulStop: '30s',
        },

        // 시나리오 3: WebSocket STOMP 동시접속 유지
        ws_stomp_sustained: {
            executor: 'constant-vus',
            exec: 'wsStompSustained',
            vus: 500,
            duration: '8m',
            startTime: '12m',
            gracefulStop: '30s',
        },

        // 시나리오 4: WebSocket 메시지 폭주 (Redis Pub/Sub)
        ws_message_flood: {
            executor: 'ramping-vus',
            exec: 'wsMessageFlood',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 200 },
                { duration: '2m', target: 500 },
                { duration: '1m', target: 300 },
                { duration: '1m', target: 0 },
            ],
            startTime: '21m',
            gracefulRampDown: '30s',
        },

        // 시나리오 5: REST + WS 복합 부하
        mixed_chat_workload: {
            executor: 'ramping-vus',
            exec: 'mixedChatWorkload',
            startVUs: 0,
            stages: [
                { duration: '1m', target: 100 },
                { duration: '2m', target: 300 },
                { duration: '1m', target: 150 },
                { duration: '1m', target: 0 },
            ],
            startTime: '27m',
            gracefulRampDown: '30s',
        },
    },

    thresholds: {
        chat_send_duration: ['p(95)<300'],
        ws_message_latency: ['p(95)<500'],
        message_delivery_rate: ['rate>0.9'],
        chat_history_duration: ['p(95)<400'],
        http_req_duration: ['p(95)<1000'],
        http_req_failed: ['rate<0.05'],
        errors: ['rate<0.05'],
    },
};

// ============================================
// 테스트 데이터
// ============================================
const testUsers = new SharedArray('chat_test_users', function () {
    const users = [];
    for (let i = 1; i <= 1000; i++) {
        users.push({
            userId: i,
            kakaoId: 10000000 + i,
            status: 'ACTIVE',
            role: 'ROLE_USER',
        });
    }
    return users;
});

const chatRoomIds = new SharedArray('chat_room_ids', function () {
    const ids = [];
    for (let i = 1; i <= 1000; i++) {
        ids.push(i);
    }
    return ids;
});

// ============================================
// 유틸리티 함수
// ============================================
function generateJWT(user) {
    const now = Date.now();
    const expiryDate = now + (3600 * 1000);

    const header = { alg: 'HS512', typ: 'JWT' };
    const payload = {
        sub: user.userId.toString(),
        kakaoId: user.kakaoId.toString(),
        nickname: `testuser${user.userId}`,
        status: user.status,
        role: user.role,
        type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor(expiryDate / 1000),
    };

    const headerEncoded = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const payloadEncoded = encoding.b64encode(JSON.stringify(payload), 'rawurl');
    const signatureInput = `${headerEncoded}.${payloadEncoded}`;
    const signature = hmac('sha512', JWT_SECRET, signatureInput, 'base64rawurl');

    return `${signatureInput}.${signature}`;
}

function getHeaders(token) {
    return {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
    };
}

function getRandomUser() {
    return testUsers[Math.floor(Math.random() * testUsers.length)];
}

function getRandomChatRoomId() {
    return chatRoomIds[Math.floor(Math.random() * chatRoomIds.length)];
}

// STOMP 프레임 생성 헬퍼
function stompConnect(token) {
    return `CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer ${token}\n\n\0`;
}

function stompSubscribe(id, destination) {
    return `SUBSCRIBE\nid:${id}\ndestination:${destination}\n\n\0`;
}

function stompSend(destination, body) {
    return `SEND\ndestination:${destination}\ncontent-type:application/json\n\n${body}\0`;
}

// ============================================
// 시나리오 1: REST 메시지 처리량
// ============================================
export function restMessageThroughput() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const chatRoomId = getRandomChatRoomId();

    const payload = JSON.stringify({
        text: `Load test message from VU ${__VU} at ${Date.now()}`,
    });

    const res = http.post(
        `${BASE_URL}/chat/${chatRoomId}/messages`,
        payload,
        { headers }
    );

    check(res, {
        'REST send: status 200 or 201': (r) => r.status === 200 || r.status === 201,
    });

    chatSendDuration.add(res.timings.duration);
    messagesSent.add(1);
    errorRate.add(res.status !== 200 && res.status !== 201);
}

// ============================================
// 시나리오 2: 채팅 히스토리 커서 페이징
// ============================================
export function chatHistoryPagination() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const chatRoomId = getRandomChatRoomId();

    group('Chat History Pagination', () => {
        // 첫 페이지
        const res1 = http.get(
            `${BASE_URL}/chat/${chatRoomId}/messages?size=30`,
            { headers }
        );

        check(res1, {
            'chat history page 1: status 200': (r) => r.status === 200,
        });
        chatHistoryDuration.add(res1.timings.duration);
        sleep(0.5);

        // 커서 기반 다음 페이지
        if (res1.status === 200) {
            try {
                const body = JSON.parse(res1.body);
                const cursor = body.data && body.data.cursor;
                if (cursor) {
                    const res2 = http.get(
                        `${BASE_URL}/chat/${chatRoomId}/messages?cursor=${cursor}&size=30`,
                        { headers }
                    );
                    check(res2, {
                        'chat history page 2: status 200': (r) => r.status === 200,
                    });
                    chatHistoryDuration.add(res2.timings.duration);
                    sleep(0.5);

                    // 세 번째 페이지
                    if (res2.status === 200) {
                        const body2 = JSON.parse(res2.body);
                        const cursor2 = body2.data && body2.data.cursor;
                        if (cursor2) {
                            const res3 = http.get(
                                `${BASE_URL}/chat/${chatRoomId}/messages?cursor=${cursor2}&size=30`,
                                { headers }
                            );
                            chatHistoryDuration.add(res3.timings.duration);
                        }
                    }
                }
            } catch (e) {
                // JSON 파싱 실패 무시
            }
        }
    });

    sleep(1);
}

// ============================================
// 시나리오 3: WebSocket STOMP 동시접속 유지
// ============================================
export function wsStompSustained() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const chatRoomId = getRandomChatRoomId();

    const wsUrl = `${WS_URL}?token=${token}`;

    const res = ws.connect(wsUrl, {}, function (socket) {
        let connected = false;
        let messageCount = 0;

        socket.on('open', function () {
            // STOMP CONNECT
            socket.send(stompConnect(token));
        });

        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED')) {
                connected = true;
                wsConnectionDuration.add(0);  // 성공 기록

                // SUBSCRIBE
                socket.send(stompSubscribe(
                    `sub-${__VU}`,
                    `/sub/chat/${chatRoomId}/messages`
                ));

                // 주기적으로 메시지 전송
                socket.setInterval(function () {
                    if (connected) {
                        const sendTime = Date.now();
                        const body = JSON.stringify({
                            text: `WS sustained msg ${__VU}-${messageCount}`,
                            sendTime: sendTime,
                        });
                        socket.send(stompSend(
                            `/pub/chat/${chatRoomId}/messages`,
                            body
                        ));
                        messagesSent.add(1);
                        messageCount++;
                    }
                }, 5000);  // 5초마다 메시지
            }

            if (data.startsWith('MESSAGE')) {
                messagesReceived.add(1);
                messageDeliveryRate.add(1);

                // 메시지 지연 측정
                try {
                    const bodyStart = data.indexOf('\n\n') + 2;
                    const bodyEnd = data.indexOf('\0');
                    const msgBody = JSON.parse(data.substring(bodyStart, bodyEnd));
                    if (msgBody.sendTime) {
                        wsMessageLatency.add(Date.now() - msgBody.sendTime);
                    }
                } catch (e) {
                    // 파싱 실패 무시
                }
            }

            if (data.startsWith('ERROR')) {
                wsConnectionFailRate.add(1);
            }
        });

        socket.on('error', function (e) {
            wsConnectionFailRate.add(1);
            errorRate.add(1);
        });

        socket.on('close', function () {
            connected = false;
        });

        // 연결 유지 시간 (시나리오 duration 내에서)
        socket.setTimeout(function () {
            socket.close();
        }, 30000);  // 30초 유지 후 재연결
    });

    check(res, {
        'WS connection: status 101': (r) => r && r.status === 101,
    });

    if (!res || res.status !== 101) {
        wsConnectionFailRate.add(1);
    } else {
        wsConnectionFailRate.add(0);
    }

    sleep(1);
}

// ============================================
// 시나리오 4: WebSocket 메시지 폭주 (Redis Pub/Sub)
// ============================================
export function wsMessageFlood() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const chatRoomId = getRandomChatRoomId();

    const wsUrl = `${WS_URL}?token=${token}`;

    const res = ws.connect(wsUrl, {}, function (socket) {
        let connected = false;

        socket.on('open', function () {
            socket.send(stompConnect(token));
        });

        socket.on('message', function (data) {
            if (data.startsWith('CONNECTED')) {
                connected = true;

                // SUBSCRIBE
                socket.send(stompSubscribe(
                    `sub-flood-${__VU}`,
                    `/sub/chat/${chatRoomId}/messages`
                ));

                // 빠른 메시지 전송 (200ms 간격)
                socket.setInterval(function () {
                    if (connected) {
                        const body = JSON.stringify({
                            text: `Flood msg ${__VU}-${Date.now()}`,
                            sendTime: Date.now(),
                        });
                        socket.send(stompSend(
                            `/pub/chat/${chatRoomId}/messages`,
                            body
                        ));
                        messagesSent.add(1);
                    }
                }, 200);
            }

            if (data.startsWith('MESSAGE')) {
                messagesReceived.add(1);
                messageDeliveryRate.add(1);
            }
        });

        socket.on('error', function () {
            errorRate.add(1);
        });

        socket.setTimeout(function () {
            socket.close();
        }, 15000);  // 15초 유지
    });

    sleep(2);
}

// ============================================
// 시나리오 5: REST + WS 복합 부하
// ============================================
export function mixedChatWorkload() {
    const user = getRandomUser();
    const token = generateJWT(user);
    const headers = getHeaders(token);
    const chatRoomId = getRandomChatRoomId();

    group('Mixed Chat Workload', () => {
        const action = Math.random();

        if (action < 0.4) {
            // 40%: REST 메시지 전송
            const payload = JSON.stringify({
                text: `Mixed chat msg ${Date.now()}`,
            });
            const res = http.post(
                `${BASE_URL}/chat/${chatRoomId}/messages`,
                payload,
                { headers }
            );
            check(res, {
                'mixed send: status 200 or 201': (r) => r.status === 200 || r.status === 201,
            });
            chatSendDuration.add(res.timings.duration);
            messagesSent.add(1);
        } else if (action < 0.7) {
            // 30%: 히스토리 조회
            const res = http.get(
                `${BASE_URL}/chat/${chatRoomId}/messages?size=30`,
                { headers }
            );
            check(res, {
                'mixed history: status 200': (r) => r.status === 200,
            });
            chatHistoryDuration.add(res.timings.duration);
        } else {
            // 30%: WebSocket 단발 연결
            const wsUrl = `${WS_URL}?token=${token}`;
            const res = ws.connect(wsUrl, {}, function (socket) {
                socket.on('open', function () {
                    socket.send(stompConnect(token));
                });

                socket.on('message', function (data) {
                    if (data.startsWith('CONNECTED')) {
                        const body = JSON.stringify({
                            text: `Mixed WS msg ${Date.now()}`,
                        });
                        socket.send(stompSend(
                            `/pub/chat/${chatRoomId}/messages`,
                            body
                        ));
                        messagesSent.add(1);

                        socket.setTimeout(function () {
                            socket.close();
                        }, 3000);
                    }

                    if (data.startsWith('MESSAGE')) {
                        messagesReceived.add(1);
                    }
                });

                socket.setTimeout(function () {
                    socket.close();
                }, 5000);
            });
        }
    });

    sleep(0.5);
}

// ============================================
// 테스트 라이프사이클
// ============================================
export function setup() {
    console.log('=== Chat Domain Load Test Started ===');
    console.log(`Base URL: ${BASE_URL}`);
    console.log(`WebSocket URL: ${WS_URL}`);
    console.log(`Test Users: ${testUsers.length}`);
    console.log('');
    console.log('Testing 5 scenarios:');
    console.log('1. REST Message Throughput (0-6m, 50->1000/s)');
    console.log('2. Chat History Pagination (7-11m, 200 VU)');
    console.log('3. WS STOMP Sustained (12-20m, 500 VU)');
    console.log('4. WS Message Flood (21-26m, 0->500 VU)');
    console.log('5. Mixed Chat Workload (27-32m, 0->300 VU)');
    console.log('');
    console.log('Total Duration: ~32 minutes');
    console.log('=====================================');
}

export function teardown(data) {
    console.log('');
    console.log('=== Chat Domain Load Test Completed ===');
    console.log('Review metrics: chat_send_duration, ws_message_latency,');
    console.log('message_delivery_rate, ws_connection_fail_rate');
    console.log('========================================');
}
