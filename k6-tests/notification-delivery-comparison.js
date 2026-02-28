// =============================================================
// 알림 전송(Delivery) 성능 비교 테스트 — SSE vs WebSocket(STOMP)
//
// 사용법:
//   SSE:       k6 run --env DELIVERY_MODE=sse notification-delivery-comparison.js
//   WebSocket: k6 run --env DELIVERY_MODE=websocket notification-delivery-comparison.js
//
// 환경변수:
//   DELIVERY_MODE  - sse | websocket (기본: websocket)
//   BASE_URL       - 서버 주소 (기본: http://host.docker.internal:8080)
//   RECEIVERS      - 수신 VU 수 (기본: 50)
//   SENDERS        - 발신 VU 수 (기본: 20)
//   DURATION       - 테스트 시간 (기본: 60s)
//
// SSE 모드: xk6-sse 확장 사용 (import sse from "k6/x/sse")
//   → 이벤트 콜백으로 실시간 latency 측정 가능
// =============================================================

import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import http from 'k6/http';
import ws from 'k6/ws';
import sse from 'k6/x/sse';
import {
    BASE_URL, generateJWT, headers,
    stompConnect, stompSubscribe, stompDisconnect, parseStompFrames,
} from './lib/common.js';

// ============================================
// 커스텀 메트릭
// ============================================
const deliveryLatency   = new Trend('delivery_latency_ms', true);
const deliverySuccess   = new Rate('delivery_success_rate');
const connectionSuccess = new Rate('connection_success_rate');
const connectDuration   = new Trend('connect_duration_ms', true);
const createApiDuration = new Trend('create_api_duration_ms', true);
const messagesReceived  = new Counter('messages_received');

// ============================================
// 설정
// ============================================
const DELIVERY_MODE = __ENV.DELIVERY_MODE || 'websocket';
const RECEIVERS     = parseInt(__ENV.RECEIVERS || '50');
const SENDERS       = parseInt(__ENV.SENDERS || '20');
const DURATION      = __ENV.DURATION || '60s';

export const options = {
    scenarios: {
        receivers: {
            executor: 'constant-vus',
            vus: RECEIVERS,
            duration: DURATION,
            exec: 'receiver',
            tags: { role: 'receiver' },
        },
        senders: {
            executor: 'constant-vus',
            vus: SENDERS,
            duration: DURATION,
            startTime: '5s',
            exec: 'sender',
            tags: { role: 'sender' },
        },
    },
    thresholds: {
        'delivery_latency_ms':      ['p(50)<500', 'p(95)<2000'],
        'delivery_success_rate':    ['rate>0.90'],
        'connection_success_rate':  ['rate>0.95'],
        'create_api_duration_ms':   ['p(95)<1000'],
    },
};

// ============================================
// 유저 풀
// ============================================
function receiverUser(vuId) {
    return {
        userId: vuId,
        kakaoId: 10000000 + vuId,
        status: 'ACTIVE',
        role: 'ROLE_USER',
    };
}

function randomTargetUserId() {
    return Math.floor(Math.random() * RECEIVERS) + 1;
}

// ============================================
// Sender 시나리오
// ============================================
export function sender() {
    const targetUserId = randomTargetUserId();
    const payload = JSON.stringify({ targetUserId, type: 'LIKE' });

    const res = http.post(`${BASE_URL}/test/notifications/create`, payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'create_notification' },
    });

    createApiDuration.add(res.timings.duration);
    check(res, { 'create: status 200': (r) => r.status === 200 });
    sleep(0.5 + Math.random() * 0.5);
}

// ============================================
// Receiver 시나리오
// ============================================
export function receiver() {
    if (DELIVERY_MODE === 'sse') {
        receiverSSE();
    } else {
        receiverWebSocket();
    }
}

// ============================================
// SSE Receiver — xk6-sse 확장 사용
// 이벤트 콜백으로 수신 즉시 latency 측정 가능
// ============================================
function receiverSSE() {
    const user = receiverUser(__VU);
    const token = generateJWT(user);
    const connectStart = Date.now();

    const response = sse.open(`${BASE_URL}/api/v1/sse/subscribe`, {
        headers: {
            'Authorization': `Bearer ${token}`,
            'Accept': 'text/event-stream',
            'Cache-Control': 'no-cache',
        },
        tags: { name: 'sse_subscribe' },
    }, function (client) {
        client.on('open', function () {
            connectionSuccess.add(1);
            connectDuration.add(Date.now() - connectStart);
        });

        client.on('event', function (event) {
            if (event.name === 'notification' && event.data) {
                const receiveTime = Date.now();
                messagesReceived.add(1);
                deliverySuccess.add(1);

                try {
                    const data = JSON.parse(event.data);
                    if (data.sentAtEpochMs) {
                        const latency = receiveTime - data.sentAtEpochMs;
                        if (latency >= 0 && latency < 60000) {
                            deliveryLatency.add(latency);
                        }
                    }
                } catch (_) { /* ignore */ }
            }

            // connected 이벤트도 카운트하지 않음 (알림만)
        });

        client.on('error', function (e) {
            connectionSuccess.add(0);
        });
    });
}

// ============================================
// WebSocket(STOMP) Receiver
// ============================================
function receiverWebSocket() {
    const user = receiverUser(__VU);
    const token = generateJWT(user);
    const wsUrl = BASE_URL.replace('http://', 'ws://').replace('https://', 'wss://');
    const connectStart = Date.now();

    ws.connect(`${wsUrl}/ws-native`, null, function (socket) {
        let connected = false;

        socket.on('open', function () {
            socket.send(stompConnect(token));
        });

        socket.on('message', function (data) {
            const frames = parseStompFrames(data);
            for (const frame of frames) {
                if (frame.command === 'CONNECTED') {
                    connected = true;
                    connectionSuccess.add(1);
                    connectDuration.add(Date.now() - connectStart);
                    socket.send(stompSubscribe('sub-0', '/user/sub/notifications'));
                }

                if (frame.command === 'MESSAGE') {
                    const receiveTime = Date.now();
                    messagesReceived.add(1);
                    deliverySuccess.add(1);

                    try {
                        const body = JSON.parse(frame.body);
                        if (body.sentAtEpochMs) {
                            const latency = receiveTime - body.sentAtEpochMs;
                            if (latency >= 0 && latency < 60000) {
                                deliveryLatency.add(latency);
                            }
                        }
                    } catch (_) { /* ignore */ }
                }

                if (frame.command === 'ERROR') {
                    connectionSuccess.add(0);
                    socket.close();
                }
            }
        });

        socket.on('close', function () {
            if (!connected) connectionSuccess.add(0);
        });

        socket.on('error', function () {
            connectionSuccess.add(0);
        });

        socket.setTimeout(function () {
            if (connected) socket.send(stompDisconnect());
            socket.close();
        }, parseInt(DURATION) * 1000 || 60000);
    });
}

// ============================================
// 요약 출력
// ============================================
export function handleSummary(data) {
    const mode = DELIVERY_MODE.toUpperCase();
    const recvd = data.metrics.messages_received
        ? data.metrics.messages_received.values.count : 0;
    const p50 = data.metrics.delivery_latency_ms
        ? data.metrics.delivery_latency_ms.values['p(50)'] : 'N/A';
    const p95 = data.metrics.delivery_latency_ms
        ? data.metrics.delivery_latency_ms.values['p(95)'] : 'N/A';
    const successRate = data.metrics.delivery_success_rate
        ? (data.metrics.delivery_success_rate.values.rate * 100).toFixed(1) : 'N/A';
    const connRate = data.metrics.connection_success_rate
        ? (data.metrics.connection_success_rate.values.rate * 100).toFixed(1) : 'N/A';
    const apiP95 = data.metrics.create_api_duration_ms
        ? data.metrics.create_api_duration_ms.values['p(95)'] : 'N/A';

    console.log('\n' + '='.repeat(60));
    console.log(`  알림 전송 성능 테스트 결과 — ${mode} 모드`);
    console.log('='.repeat(60));
    console.log(`  수신 VU:        ${RECEIVERS}`);
    console.log(`  발신 VU:        ${SENDERS}`);
    console.log(`  수신 메시지:    ${recvd}`);
    console.log(`  연결 성공률:    ${connRate}%`);
    console.log(`  전송 성공률:    ${successRate}%`);
    console.log(`  API p95:        ${typeof apiP95 === 'number' ? apiP95.toFixed(1) : apiP95}ms`);
    console.log(`  Latency p50:    ${typeof p50 === 'number' ? p50.toFixed(1) : p50}ms`);
    console.log(`  Latency p95:    ${typeof p95 === 'number' ? p95.toFixed(1) : p95}ms`);
    console.log('='.repeat(60) + '\n');

    return { stdout: JSON.stringify(data, null, 2) };
}
