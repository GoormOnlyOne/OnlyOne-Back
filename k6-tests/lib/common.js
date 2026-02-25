// =============================================================
// 알림/SSE 부하 테스트 공용 유틸리티 모듈
// =============================================================

import http from 'k6/http';
import { hmac } from 'k6/crypto';
import encoding from 'k6/encoding';

// ============================================
// 기본 설정
// ============================================
export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8888';
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
