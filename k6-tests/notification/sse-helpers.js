// =============================================================
// 알림 SSE 테스트 공용 헬퍼 (xk6-sse 모듈 필요)
// =============================================================
// 실행 시 K6_ENABLE_COMMUNITY_EXTENSIONS=true 필요
//
// SSE 이벤트 형식 (서버 기준):
//   event: connected   id: init_<epochMs>           data: "OK"
//   event: notification id: evt_<epochMs>_<counter>  data: NotificationSseDto JSON
//
// NotificationSseDto:
//   { notificationId: Long, content: String, type: String,
//     isRead: boolean, createdAt: ISO, sentAtEpochMs: long }

import sse from 'k6/x/sse';
import { SSE_SUBSCRIBE_URL } from '../lib/common.js';

/**
 * xk6-sse 기반 SSE 구독.
 *
 * @param {string} token - JWT 토큰
 * @param {number} timeoutSec - 최대 대기 시간 (서버 SSE timeout이 더 짧으면 서버가 먼저 끊음)
 * @param {number} maxEvents - 수신할 notification 이벤트 수 (도달 시 close)
 * @param {string} [tagName] - k6 태그 이름 (기본: 'sse_subscribe')
 * @returns {{ connected: boolean, notifEvents: Array, connectDuration: number, duration: number }}
 */
export function sseSubscribe(token, timeoutSec, maxEvents, tagName) {
    const notifEvents = [];
    let connected = false;
    let connectDuration = 0;
    const startTime = Date.now();
    const timeoutMs = (timeoutSec || 3) * 1000;

    const params = {
        method: 'GET',
        headers: {
            'Authorization': `Bearer ${token}`,
            'Accept': 'text/event-stream',
            'Cache-Control': 'no-cache',
        },
        tags: { name: tagName || 'sse_subscribe' },
        timeout: `${timeoutMs}ms`,
    };

    const response = sse.open(SSE_SUBSCRIBE_URL, params, function (client) {
        client.on('open', function () {
            connected = true;
            connectDuration = Date.now() - startTime;
        });

        client.on('event', function (event) {
            if (!connected) {
                connected = true;
                connectDuration = Date.now() - startTime;
            }

            if (event.name === 'notification' && event.data) {
                try {
                    const data = JSON.parse(event.data);
                    notifEvents.push({
                        notificationId: data.notificationId,
                        sentAtEpochMs: data.sentAtEpochMs || 0,
                        type: data.type,
                        content: data.content,
                    });
                } catch (_) {}
            }

            if (maxEvents && notifEvents.length >= maxEvents) {
                client.close();
            }
        });

        client.on('error', function (_) {
            // timeout 또는 에러 발생 시 연결 종료 → sse.open() 반환 보장
            client.close();
        });
    });

    const isConnected = connected || (response && response.status === 200);
    if (isConnected && connectDuration === 0) {
        connectDuration = Date.now() - startTime;
    }

    return {
        connected: isConnected,
        notifEvents: notifEvents,
        connectDuration: connectDuration,
        duration: Date.now() - startTime,
    };
}
