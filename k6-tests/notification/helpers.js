// =============================================================
// 알림 테스트 공용 헬퍼 (xk6-sse 미사용 — 모든 테스트에서 import 가능)
// =============================================================

import http from 'k6/http';
import { generateJWT, headers, BASE_URL, makeUser, TOTAL_USERS } from '../lib/common.js';

// ── 공용 상수 (환경변수 오버라이드 가능) ──
export const USER_COUNT   = parseInt(__ENV.USER_COUNT || '') || TOTAL_USERS;
export const HOT_USER_MAX = parseInt(__ENV.HOT_USER_MAX || '10');

// ── 유저 팩토리 ──

/** 1~count 범위 랜덤 유저 */
export function randomUser(count) {
    return makeUser(Math.floor(Math.random() * (count || USER_COUNT)) + 1);
}

/** VU ID 기반 고정 유저 (VU당 1명, count 범위 내 순환) */
export function vuUser(vuId, count) {
    return makeUser(((vuId - 1) % (count || USER_COUNT)) + 1);
}

/** 소수 핫유저 (lock 경합 테스트용) */
export function hotUser(max) {
    return makeUser(Math.floor(Math.random() * (max || HOT_USER_MAX)) + 1);
}

// ── 테스트 알림 생성 ──
// POST /test/notifications/create (local, test 프로필 전용)
//
// 서버 흐름:
//   1. DB 저장 (sse_sent=false)
//   2. unreadCounter 증가
//   3. NotificationCreatedEvent 발행 (AFTER_COMMIT)
//   4. → BatchProcessor가 SSE 연결 유저에게 전달, sse_sent=true 설정
//   5. → 미연결 유저면 스킵, sse_sent=false 유지
//   6. → 재연결 시 MissedNotificationRecovery가 sse_sent=false 최대 50건 전달
//
// 응답: { success: true, data: { notificationId: Long, serverTimestamp: epochMs } }
export function createNotification(userId, type) {
    const res = http.post(`${BASE_URL}/test/notifications/create`,
        JSON.stringify({ targetUserId: userId, type: type || 'LIKE' }),
        { headers: { 'Content-Type': 'application/json' }, tags: { name: 'create_notification' } }
    );
    const duration = res.timings.duration;
    if (res.status !== 200) return { ok: false, duration };
    try {
        const body = JSON.parse(res.body);
        return {
            ok: true,
            notificationId: body.data.notificationId,   // Long (MySQL auto-increment)
            serverTimestamp: body.data.serverTimestamp,   // epochMs (비교용)
            duration,
        };
    } catch (_) { return { ok: false, duration }; }
}

// ── handleSummary 유틸 ──
export const pad = (s, n) => String(s).padEnd(n);
export const num = (v, d = 1) => v != null ? Number(v).toFixed(d) : 'N/A';
export const pct = (v) => v != null ? (Number(v) * 100).toFixed(1) + '%' : 'N/A';
