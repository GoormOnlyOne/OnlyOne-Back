// =============================================================
// MySQL vs PostgreSQL RDBMS 락/동시성 비교 부하 테스트
// =============================================================
// 실행 (MySQL):
//   MSYS_NO_PATHCONV=1 docker run --rm --network host \
//     -v "$(pwd)/k6-tests:/scripts" grafana/k6 run \
//     -e BASE_URL=http://localhost:8080 -e STORAGE_VENDOR=mysql \
//     /scripts/rdbms-lock-comparison-test.js
//
// 실행 (PostgreSQL):
//   MSYS_NO_PATHCONV=1 docker run --rm --network host \
//     -v "$(pwd)/k6-tests:/scripts" grafana/k6 run \
//     -e BASE_URL=http://localhost:8080 -e STORAGE_VENDOR=postgresql \
//     /scripts/rdbms-lock-comparison-test.js
//
// 사전조건:
//   - seed-all-domains.sql (MySQL) 또는 seed-postgres.sql (PostgreSQL) 실행
//   - SPRING_PROFILES_ACTIVE=local,loadtest
//
// 엔드포인트 (6개, 락 집중):
//   1. POST /settlements/{id}/request       — 정산 multi-row TX (25%)
//   2. POST /schedules/{id}/join            — user_limit 체크 + gap lock (20%)
//   3. PATCH /notifications/read-all        — 대량 UPDATE 락 (20%)
//   4. POST /payments/confirm               — 지갑 잔액 락 (15%)
//   5. POST /feeds/{id}/comments            — comment_count 단일 row (10%)
//   6. DELETE+POST /clubs/{id}/leave+join   — FK cascade + unique (10%)
// =============================================================

import http from 'k6/http';
import { sleep, group, check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL } from './lib/common.js';
import {
    getMetrics, recordResponse, buildThresholds,
    progressiveStages, randomUser, THRESHOLDS,
} from './lib/bottleneck.js';

// ── 환경 변수 ──
const VENDOR = __ENV.STORAGE_VENDOR || 'mysql';
const vendorPrefix = VENDOR === 'postgresql' ? 'pg' : 'mysql';

// ── 커스텀 메트릭 ──
const lockTimeoutErrors = new Counter('lock_timeout_errors');

// 벤더별 Trend (결과 구분용)
const vendorTrends = {
    settlement: new Trend(`${vendorPrefix}_settlement_duration`, true),
    schedule_join: new Trend(`${vendorPrefix}_schedule_join_duration`, true),
    notification_read: new Trend(`${vendorPrefix}_notification_read_duration`, true),
    payment_confirm: new Trend(`${vendorPrefix}_payment_confirm_duration`, true),
    feed_comment: new Trend(`${vendorPrefix}_feed_comment_duration`, true),
    club_rejoin: new Trend(`${vendorPrefix}_club_rejoin_duration`, true),
};

// ── 엔드포인트별 임계값 ──
const ENDPOINT_THRESHOLDS = {
    settlement_request:    THRESHOLDS.VERY_SLOW,  // 3000ms
    schedule_join:         THRESHOLDS.NORMAL,      // 500ms
    notification_read_all: THRESHOLDS.NORMAL,      // 500ms
    payment_confirm:       THRESHOLDS.SLOW,        // 1000ms
    feed_comment_create:   THRESHOLDS.NORMAL,      // 500ms
    club_rejoin:           THRESHOLDS.NORMAL,      // 500ms
};

export const options = {
    stages: progressiveStages(20, 120, '2m'),
    thresholds: buildThresholds(ENDPOINT_THRESHOLDS),
};

// ── Setup: 테스트 데이터 수집 ──
export function setup() {
    const user = { userId: 1, kakaoId: 1000001, status: 'ACTIVE', role: 'ROLE_USER' };
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 정산 ID 수집
    const settlementIds = [];
    const settleRes = http.get(`${BASE_URL}/api/v1/settlements/my?page=0&size=100`, { headers: hdrs });
    if (settleRes.status === 200) {
        try {
            const body = JSON.parse(settleRes.body);
            const list = body.data?.settlements || body.data || [];
            if (Array.isArray(list)) {
                list.forEach(s => settlementIds.push(s.settlementId || s.settlement_id));
            }
        } catch (e) { /* ignore */ }
    }

    // 피드 ID 수집 (클럽 1의 피드)
    const feedIds = [];
    const feedRes = http.get(`${BASE_URL}/api/v1/feeds/club/1?page=0&size=50`, { headers: hdrs });
    if (feedRes.status === 200) {
        try {
            const body = JSON.parse(feedRes.body);
            const list = body.data?.feeds || body.data?.content || body.data || [];
            if (Array.isArray(list)) {
                list.forEach(f => feedIds.push(f.feedId || f.feed_id));
            }
        } catch (e) { /* ignore */ }
    }

    // 스케줄 ID 수집
    const scheduleIds = [];
    const schedRes = http.get(`${BASE_URL}/api/v1/clubs/1/schedules?page=0&size=50`, { headers: hdrs });
    if (schedRes.status === 200) {
        try {
            const body = JSON.parse(schedRes.body);
            const list = body.data?.schedules || body.data?.content || body.data || [];
            if (Array.isArray(list)) {
                list.forEach(s => scheduleIds.push(s.scheduleId || s.schedule_id));
            }
        } catch (e) { /* ignore */ }
    }

    console.log(`[${VENDOR}] Setup: settlements=${settlementIds.length}, feeds=${feedIds.length}, schedules=${scheduleIds.length}`);

    return {
        vendor: VENDOR,
        settlementIds,
        feedIds,
        scheduleIds,
    };
}

// ── 락 타임아웃/데드락 5xx 추적 ──
function trackLockErrors(res) {
    if (res.status >= 500) {
        const body = (res.body || '').toLowerCase();
        if (body.includes('deadlock') || body.includes('lock wait timeout') || body.includes('lock_timeout')) {
            lockTimeoutErrors.add(1);
        }
    }
}

function recordWithVendor(metricName, vendorTrendKey, res, expectedStatus) {
    recordResponse(metricName, res, expectedStatus || 200);
    vendorTrends[vendorTrendKey].add(res.timings.duration);
    trackLockErrors(res);
}

// ── 메인 시나리오 ──
export default function (data) {
    const user = randomUser(1, 100);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();

    // ── 1. 정산 요청 (25%) — multi-row TX 락 ──
    if (roll < 0.25) {
        group('정산 요청 (락)', () => {
            if (data.settlementIds && data.settlementIds.length > 0) {
                const sid = data.settlementIds[Math.floor(Math.random() * data.settlementIds.length)];
                const res = http.post(`${BASE_URL}/api/v1/settlements/${sid}/request`, null, {
                    headers: hdrs,
                    tags: { name: 'settlement_request' },
                });
                recordWithVendor('settlement_request', 'settlement', res);
            }
        });
    }
    // ── 2. 일정 참가 (20%) — user_limit 체크 + gap lock ──
    else if (roll < 0.45) {
        group('일정 참가 (락)', () => {
            if (data.scheduleIds && data.scheduleIds.length > 0) {
                const schId = data.scheduleIds[Math.floor(Math.random() * data.scheduleIds.length)];
                const res = http.post(`${BASE_URL}/api/v1/schedules/${schId}/join`, null, {
                    headers: hdrs,
                    tags: { name: 'schedule_join' },
                });
                recordWithVendor('schedule_join', 'schedule_join', res);
            }
        });
    }
    // ── 3. 알림 전체 읽음 (20%) — 대량 UPDATE ──
    else if (roll < 0.65) {
        group('알림 전체 읽음 (락)', () => {
            const res = http.patch(`${BASE_URL}/api/v1/notifications/read-all`, null, {
                headers: hdrs,
                tags: { name: 'notification_read_all' },
            });
            recordWithVendor('notification_read_all', 'notification_read', res);
        });
    }
    // ── 4. 결제 확인 (15%) — 지갑 잔액 락 ──
    else if (roll < 0.80) {
        group('결제 확인 (락)', () => {
            const orderId = `locktest-${user.userId}-${Date.now()}`;
            // 결제 생성
            const createRes = http.post(`${BASE_URL}/api/v1/payments`, JSON.stringify({
                tossOrderId: orderId,
                totalAmount: 100,
                scheduleId: 50001 + Math.floor(Math.random() * 500),
            }), { headers: hdrs, tags: { name: 'payment_create_lock' } });

            if (createRes.status === 200 || createRes.status === 201) {
                sleep(0.05);
                const confirmRes = http.post(`${BASE_URL}/api/v1/payments/confirm`, JSON.stringify({
                    orderId: orderId,
                    paymentKey: `pk-${orderId}`,
                    amount: 100,
                }), { headers: hdrs, tags: { name: 'payment_confirm' } });
                recordWithVendor('payment_confirm', 'payment_confirm', confirmRes);
            }
        });
    }
    // ── 5. 피드 댓글 작성 (10%) — comment_count 증가 ──
    else if (roll < 0.90) {
        group('피드 댓글 (락)', () => {
            if (data.feedIds && data.feedIds.length > 0) {
                const fid = data.feedIds[Math.floor(Math.random() * data.feedIds.length)];
                const res = http.post(`${BASE_URL}/api/v1/feeds/${fid}/comments`, JSON.stringify({
                    content: `락테스트 댓글 ${Date.now()}`,
                }), {
                    headers: hdrs,
                    tags: { name: 'feed_comment_create' },
                });
                recordWithVendor('feed_comment_create', 'feed_comment', res);
            }
        });
    }
    // ── 6. 클럽 탈퇴 + 재가입 (10%) — FK cascade + unique ──
    else {
        group('클럽 탈퇴+재가입 (락)', () => {
            const clubId = Math.floor(Math.random() * 100) + 1;
            // 탈퇴
            http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, {
                headers: hdrs,
                tags: { name: 'club_leave_lock' },
            });
            sleep(0.05);
            // 재가입
            const joinRes = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/join`, null, {
                headers: hdrs,
                tags: { name: 'club_rejoin' },
            });
            recordWithVendor('club_rejoin', 'club_rejoin', joinRes);
        });
    }

    sleep(0.3 + Math.random() * 0.5);
}

export function handleSummary(data) {
    const vendorLabel = VENDOR === 'postgresql' ? 'PostgreSQL' : 'MySQL';

    console.log('\n' + '='.repeat(80));
    console.log(`  RDBMS 락/동시성 비교 결과 — ${vendorLabel}`);
    console.log('='.repeat(80));

    const endpoints = [
        ['settlement_request',    '정산 요청'],
        ['schedule_join',         '일정 참가'],
        ['notification_read_all', '알림 전체읽음'],
        ['payment_confirm',       '결제 확인'],
        ['feed_comment_create',   '피드 댓글'],
        ['club_rejoin',           '클럽 재가입'],
    ];

    console.log(`  ${'엔드포인트'.padEnd(18)} | ${'count'.padStart(7)} | ${'avg(ms)'.padStart(9)} | ${'p95(ms)'.padStart(9)} | ${'err%'.padStart(7)}`);
    console.log('  ' + '-'.repeat(70));

    for (const [key, label] of endpoints) {
        const duration = data.metrics[`${key}_duration`];
        const errors = data.metrics[`${key}_errors`];
        const count = data.metrics[`${key}_count`];

        if (duration && count) {
            const avg = duration.values?.avg?.toFixed(1) || '-';
            const p95 = duration.values?.['p(95)']?.toFixed(1) || '-';
            const errRate = errors ? (errors.values?.rate * 100).toFixed(2) : '0.00';
            const cnt = count.values?.count || 0;

            console.log(`  ${label.padEnd(18)} | ${String(cnt).padStart(7)} | ${String(avg).padStart(9)} | ${String(p95).padStart(9)} | ${String(errRate).padStart(6)}%`);
        }
    }

    const lockErrors = data.metrics['lock_timeout_errors'];
    if (lockErrors) {
        console.log('\n  락 타임아웃/데드락 5xx 수: ' + (lockErrors.values?.count || 0));
    }

    console.log('='.repeat(80) + '\n');

    return {};
}
