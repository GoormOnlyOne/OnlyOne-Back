// =============================================================
// 알림/SSE 부하 테스트 공용 유틸리티 모듈
// =============================================================

import http from 'k6/http';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// 기본 설정
// ============================================
export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
export const JWT_SECRET = __ENV.JWT_SECRET || '7e9eeb12d176a2d72f554c6b096522b4e1a34d799727e45a96f192bbff2a2a851ede29ed24b10b6e6b1835ac94380e2469df99ff9713477bf4d43eeaa9cd16a3';
export const SSE_CONNECT_TIMEOUT = '2s';

// ============================================
// JWT 생성
// ============================================
export function generateJWT(user) {
    const now = Date.now();
    const header = { alg: 'HS512', typ: 'JWT' };
    const payload = {
        sub: user.userId.toString(),
        kakaoId: user.kakaoId.toString(),
        nickname: `testuser${user.userId}`,
        status: user.status,
        role: user.role,
        type: 'access',
        iat: Math.floor(now / 1000),
        exp: Math.floor((now + 3600000) / 1000),
    };
    const h = encoding.b64encode(JSON.stringify(header), 'rawurl');
    const p = encoding.b64encode(JSON.stringify(payload), 'rawurl');
    const sig = hmac('sha512', JWT_SECRET, `${h}.${p}`, 'base64rawurl');
    return `${h}.${p}.${sig}`;
}

// ============================================
// 헤더 유틸리티
// ============================================
export function headers(token) {
    return {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
    };
}

export function sseHeaders(token) {
    return {
        'Authorization': `Bearer ${token}`,
        'Accept': 'text/event-stream',
        'Cache-Control': 'no-cache',
    };
}

// ============================================
// SSE 이벤트 파싱
// ============================================
export function parseSSEEvents(body) {
    const events = [];
    if (!body) return events;

    const rawEvents = body.split('\n\n');
    rawEvents.forEach(raw => {
        if (raw.trim() === '') return;
        const lines = raw.trim().split('\n');
        const event = {};
        for (const line of lines) {
            if (line.startsWith('id:')) event.id = line.substring(3).trim();
            else if (line.startsWith('event:')) event.name = line.substring(6).trim();
            else if (line.startsWith('data:')) event.data = line.substring(5).trim();
        }
        if (Object.keys(event).length > 0) {
            events.push(event);
        }
    });
    return events;
}

// ============================================
// SSE 연결 + 이벤트 파싱 결과 반환
// ============================================
export function connectSSE(user, timeout) {
    const token = generateJWT(user);
    const hdrs = sseHeaders(token);
    const startTime = Date.now();

    const res = http.get(`${BASE_URL}/api/v1/sse/subscribe`, {
        headers: hdrs,
        timeout: timeout || SSE_CONNECT_TIMEOUT,
        responseType: 'text',
        tags: { name: 'sse_subscribe' },
    });

    const duration = Date.now() - startTime;

    // SSE는 스트리밍이므로 status 0 (k6 타임아웃)도 연결 수립으로 볼 수 있음
    const isConnected = res.status === 200 || res.status === 0;

    const events = parseSSEEvents(res.body);
    const connectedEvent = events.some(e => e.name === 'connected');
    const notificationEvents = events.filter(e => e.name === 'notification');

    return {
        success: isConnected,
        status: res.status,
        eventCount: events.length,
        events: events,
        connectedEvent: connectedEvent,
        notificationEvents: notificationEvents,
        duration: duration,
        timings: res.timings,
    };
}

// ============================================
// STOMP 프레임 유틸리티
// ============================================
const NULL_CHAR = '\u0000';

export function stompConnect(token) {
    return `CONNECT\nAuthorization:Bearer ${token}\naccept-version:1.2\nheart-beat:10000,10000\n\n${NULL_CHAR}`;
}

export function stompSubscribe(id, destination) {
    return `SUBSCRIBE\nid:${id}\ndestination:${destination}\n\n${NULL_CHAR}`;
}

export function stompDisconnect(receiptId) {
    return `DISCONNECT\nreceipt:${receiptId || 'disc-1'}\n\n${NULL_CHAR}`;
}

export function parseStompFrames(data) {
    const frames = [];
    if (!data) return frames;

    const rawFrames = data.split(NULL_CHAR);
    for (const raw of rawFrames) {
        const trimmed = raw.replace(/^\n+/, '');
        if (!trimmed) continue;

        const firstNewline = trimmed.indexOf('\n');
        if (firstNewline === -1) continue;

        const command = trimmed.substring(0, firstNewline);
        const rest = trimmed.substring(firstNewline + 1);

        const headerBodySep = rest.indexOf('\n\n');
        let headers = {};
        let body = '';

        if (headerBodySep !== -1) {
            const headerSection = rest.substring(0, headerBodySep);
            body = rest.substring(headerBodySep + 2);
            for (const line of headerSection.split('\n')) {
                const colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    headers[line.substring(0, colonIdx)] = line.substring(colonIdx + 1);
                }
            }
        }

        frames.push({ command, headers, body });
    }
    return frames;
}

// ============================================
// 알림 목록에서 ID 배열 추출
// ============================================
export function fetchNotificationIds(token, size) {
    const res = http.get(`${BASE_URL}/api/v1/notifications?size=${size || 20}`, {
        headers: headers(token),
        tags: { name: 'fetch_notification_ids' },
    });

    if (res.status !== 200) return [];

    try {
        const body = JSON.parse(res.body);
        if (body.data && body.data.notifications) {
            return body.data.notifications.map(n => n.notificationId);
        }
    } catch (e) {
        // ignore parse error
    }
    return [];
}
