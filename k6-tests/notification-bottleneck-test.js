// =============================================================
// notification-bottleneck-test.js
// 알림 시스템 구간별 병목 탐지 부하 테스트
// =============================================================
//
// 실행 방법 (Docker):
//   MSYS_NO_PATHCONV=1 docker run --rm \
//     -v "$(pwd)/k6-tests:/scripts" \
//     --add-host=host.docker.internal:host-gateway \
//     grafana/k6:latest run /scripts/notification-bottleneck-test.js
//
// 환경 변수:
//   BASE_URL     (기본: http://host.docker.internal:8080)
//   USER_COUNT   (기본: 100) — 테스트 유저 수
//   JWT_SECRET   (기본: 로컬 시크릿)
//
// =============================================================
// 테스트 구조 (9 Phase, 총 ~12분):
//
//   Phase 1 — Warmup: 서버 JIT/커넥션 풀 워밍업
//   Phase 2 — List Baseline: 알림 목록 조회 단독 (커서 페이지네이션)
//   Phase 3 — Unread Count Baseline: 읽지않은 개수 조회 단독 (Redis vs DB)
//   Phase 4 — Mark Read: 단건 읽음 처리 (UPDATE + Redis 감소)
//   Phase 5 — Mark All Read: 전체 읽음 처리 (bulk UPDATE + Redis 리셋)
//   Phase 6 — Delete: 알림 삭제 (SELECT + DELETE + Redis 감소)
//   Phase 7 — Create + SSE: 알림 생성 → 이벤트 발행 → SSE 전송 풀체인
//   Phase 8 — Mixed Realistic: 실제 사용 패턴 혼합 부하
//   Phase 9 — Spike: 급격한 트래픽 증가 대응 테스트
// =============================================================

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, fetchNotificationIds, connectSSE } from './lib/common.js';

// ============================================
// 커스텀 메트릭 — 구간별 세밀한 측정
// ============================================

// --- Phase 2: 목록 조회 ---
const listDuration       = new Trend('noti_list_duration',       true);
const listFirstPage      = new Trend('noti_list_first_page',     true);
const listNextPage       = new Trend('noti_list_next_page',      true);
const listEmptyPage      = new Trend('noti_list_empty_page',     true);
const listSuccess        = new Rate('noti_list_success');
const listErrors         = new Counter('noti_list_errors');

// --- Phase 3: 읽지않은 개수 ---
const unreadDuration     = new Trend('noti_unread_duration',     true);
const unreadCacheHit     = new Trend('noti_unread_cache_hit',    true);  // <10ms → Redis hit 추정
const unreadCacheMiss    = new Trend('noti_unread_cache_miss',   true);  // ≥10ms → DB fallback 추정
const unreadSuccess      = new Rate('noti_unread_success');

// --- Phase 4: 단건 읽음 ---
const markReadDuration   = new Trend('noti_mark_read_duration',  true);
const markReadSuccess    = new Rate('noti_mark_read_success');

// --- Phase 5: 전체 읽음 ---
const markAllDuration    = new Trend('noti_mark_all_duration',   true);
const markAllSuccess     = new Rate('noti_mark_all_success');

// --- Phase 6: 삭제 ---
const deleteDuration     = new Trend('noti_delete_duration',     true);
const deleteSuccess      = new Rate('noti_delete_success');

// --- Phase 7: 생성 + SSE ---
const createDuration     = new Trend('noti_create_duration',     true);
const createSuccess      = new Rate('noti_create_success');
const sseConnDuration    = new Trend('noti_sse_conn_duration',   true);
const sseConnSuccess     = new Rate('noti_sse_conn_success');

// --- Phase 8: 혼합 ---
const mixedDuration      = new Trend('noti_mixed_duration',      true);
const mixedSuccess       = new Rate('noti_mixed_success');

// --- Phase 9: 스파이크 ---
const spikeDuration      = new Trend('noti_spike_duration',      true);
const spikeSuccess       = new Rate('noti_spike_success');
const spikeErrors        = new Counter('noti_spike_errors');

// ============================================
// 테스트 설정
// ============================================
const USER_COUNT = parseInt(__ENV.USER_COUNT || '100');

export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: 'warmup' },
        },
        // Phase 2: 알림 목록 조회 (커서 페이지네이션 병목 탐지)
        list_baseline: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '20s', target: 30 },
                { duration: '40s', target: 30 },
                { duration: '10s', target: 0 },
            ],
            startTime: '35s',
            exec: 'listBaseline',
            tags: { phase: 'list' },
        },
        // Phase 3: 읽지않은 개수 (Redis hit/miss 분리 측정)
        unread_baseline: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '15s', target: 50 },
                { duration: '30s', target: 50 },
                { duration: '10s', target: 0 },
            ],
            startTime: '110s',
            exec: 'unreadBaseline',
            tags: { phase: 'unread' },
        },
        // Phase 4: 단건 읽음 처리 (UPDATE + Redis decrement)
        mark_read: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '15s', target: 30 },
                { duration: '30s', target: 30 },
                { duration: '10s', target: 0 },
            ],
            startTime: '170s',
            exec: 'markRead',
            tags: { phase: 'mark_read' },
        },
        // Phase 5: 전체 읽음 (bulk UPDATE + Redis reset — 가장 무거운 쓰기)
        mark_all_read: {
            executor: 'ramping-vus',
            startVUs: 3,
            stages: [
                { duration: '15s', target: 20 },
                { duration: '25s', target: 20 },
                { duration: '10s', target: 0 },
            ],
            startTime: '230s',
            exec: 'markAllRead',
            tags: { phase: 'mark_all' },
        },
        // Phase 6: 알림 삭제 (SELECT is_read → DELETE + Redis decrement)
        delete_noti: {
            executor: 'ramping-vus',
            startVUs: 3,
            stages: [
                { duration: '15s', target: 20 },
                { duration: '25s', target: 20 },
                { duration: '10s', target: 0 },
            ],
            startTime: '285s',
            exec: 'deleteNotification',
            tags: { phase: 'delete' },
        },
        // Phase 7: 알림 생성 + SSE 풀체인
        create_sse: {
            executor: 'ramping-vus',
            startVUs: 3,
            stages: [
                { duration: '15s', target: 20 },
                { duration: '30s', target: 20 },
                { duration: '10s', target: 0 },
            ],
            startTime: '340s',
            exec: 'createAndSSE',
            tags: { phase: 'create_sse' },
        },
        // Phase 8: 실제 사용 패턴 혼합 부하
        mixed_realistic: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 50 },
                { duration: '60s', target: 50 },
                { duration: '15s', target: 0 },
            ],
            startTime: '400s',
            exec: 'mixedRealistic',
            tags: { phase: 'mixed' },
        },
        // Phase 9: 스파이크 테스트
        spike: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '10s', target: 100 },  // 급격히 증가
                { duration: '20s', target: 100 },   // 유지
                { duration: '10s', target: 5 },     // 급격히 감소
                { duration: '20s', target: 5 },     // 안정
            ],
            startTime: '500s',
            exec: 'spikeTest',
            tags: { phase: 'spike' },
        },
    },

    thresholds: {
        // 목록 조회: p95 < 500ms
        'noti_list_first_page':    ['p(95)<500'],
        'noti_list_next_page':     ['p(95)<500'],
        // 읽지않은 개수: p95 < 100ms (Redis hit), p95 < 300ms (DB fallback)
        'noti_unread_cache_hit':   ['p(95)<100'],
        'noti_unread_cache_miss':  ['p(95)<300'],
        // 읽음/삭제: p95 < 300ms
        'noti_mark_read_duration': ['p(95)<300'],
        'noti_delete_duration':    ['p(95)<300'],
        // 전체 읽음: p95 < 1000ms (bulk UPDATE)
        'noti_mark_all_duration':  ['p(95)<1000'],
        // 생성: p95 < 500ms
        'noti_create_duration':    ['p(95)<500'],
        // 전체 성공률
        'noti_list_success':       ['rate>0.99'],
        'noti_unread_success':     ['rate>0.99'],
        'noti_mixed_success':      ['rate>0.95'],
        'noti_spike_success':      ['rate>0.90'],
    },
};

// ============================================
// 유저 생성 유틸
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

// ============================================
// Phase 1: Warmup
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);

    http.get(`${BASE_URL}/api/v1/notifications?size=5`, {
        headers: headers(token),
        tags: { name: 'warmup_list' },
    });

    http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: headers(token),
        tags: { name: 'warmup_unread' },
    });

    sleep(0.5);
}

// ============================================
// Phase 2: 알림 목록 조회 (커서 페이지네이션 구간 분리)
// ============================================
export function listBaseline() {
    const user = testUser(__VU);
    const token = generateJWT(user);

    group('list_first_page', () => {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(token),
            tags: { name: 'noti_list_first' },
        });
        const dur = res.timings.duration;
        listDuration.add(dur);
        listFirstPage.add(dur);
        const ok = res.status === 200;
        listSuccess.add(ok);
        if (!ok) listErrors.add(1);

        // 커서 기반 다음 페이지 조회
        if (ok) {
            try {
                const body = JSON.parse(res.body);
                const data = body.data || body;
                if (data.hasMore && data.cursor) {
                    group('list_next_page', () => {
                        const res2 = http.get(
                            `${BASE_URL}/api/v1/notifications?size=20&cursor=${data.cursor}`, {
                                headers: headers(token),
                                tags: { name: 'noti_list_next' },
                            });
                        listNextPage.add(res2.timings.duration);
                        listDuration.add(res2.timings.duration);
                        listSuccess.add(res2.status === 200);
                    });
                }
            } catch (e) { /* parse error */ }
        }
    });

    // 데이터 없는 유저 (빈 페이지 성능)
    group('list_empty_user', () => {
        const emptyUser = { userId: 9999, kakaoId: 10009999, status: 'ACTIVE', role: 'ROLE_USER' };
        const emptyToken = generateJWT(emptyUser);
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(emptyToken),
            tags: { name: 'noti_list_empty' },
        });
        listEmptyPage.add(res.timings.duration);
    });

    sleep(0.3);
}

// ============================================
// Phase 3: 읽지않은 개수 (Redis hit/miss 분리)
// ============================================
export function unreadBaseline() {
    const user = testUser(__VU);
    const token = generateJWT(user);

    group('unread_count', () => {
        // 첫 번째 호출: Redis miss → DB fallback 가능성
        const res1 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(token),
            tags: { name: 'noti_unread_1st' },
        });
        const dur1 = res1.timings.duration;
        unreadDuration.add(dur1);
        if (dur1 < 10) {
            unreadCacheHit.add(dur1);
        } else {
            unreadCacheMiss.add(dur1);
        }
        unreadSuccess.add(res1.status === 200);

        sleep(0.1);

        // 두 번째 호출: Redis hit 확정 (캐시 워밍 후)
        const res2 = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(token),
            tags: { name: 'noti_unread_2nd' },
        });
        const dur2 = res2.timings.duration;
        unreadDuration.add(dur2);
        if (dur2 < 10) {
            unreadCacheHit.add(dur2);
        } else {
            unreadCacheMiss.add(dur2);
        }
        unreadSuccess.add(res2.status === 200);

        // 응답 값 검증
        check(res2, {
            'unread_count >= 0': (r) => {
                try {
                    const body = JSON.parse(r.body);
                    const count = body.data !== undefined ? body.data : body;
                    return count >= 0;
                } catch (e) { return false; }
            },
        });
    });

    sleep(0.2);
}

// ============================================
// Phase 4: 단건 읽음 처리
// ============================================
export function markRead() {
    const user = testUser(__VU);
    const token = generateJWT(user);

    group('mark_single_read', () => {
        // 먼저 읽지않은 알림 ID 획득
        const ids = fetchNotificationIds(token, 5);
        if (ids.length === 0) {
            sleep(0.5);
            return;
        }

        const targetId = ids[Math.floor(Math.random() * ids.length)];
        const res = http.put(
            `${BASE_URL}/api/v1/notifications/${targetId}/read`,
            null,
            {
                headers: headers(token),
                tags: { name: 'noti_mark_read' },
            }
        );
        markReadDuration.add(res.timings.duration);
        markReadSuccess.add(res.status === 200);

        check(res, {
            'mark_read status 200': (r) => r.status === 200,
        });
    });

    sleep(0.3);
}

// ============================================
// Phase 5: 전체 읽음 (가장 무거운 쓰기 연산)
// ============================================
export function markAllRead() {
    const user = testUser(__VU);
    const token = generateJWT(user);

    group('mark_all_read', () => {
        const res = http.put(
            `${BASE_URL}/api/v1/notifications/read-all`,
            null,
            {
                headers: headers(token),
                tags: { name: 'noti_mark_all' },
            }
        );
        markAllDuration.add(res.timings.duration);
        markAllSuccess.add(res.status === 200);

        check(res, {
            'mark_all status 200': (r) => r.status === 200,
        });
    });

    // 읽음 처리 후 unread 카운터 0 검증
    group('verify_all_read', () => {
        sleep(0.1);
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(token),
            tags: { name: 'noti_verify_all_read' },
        });
        check(res, {
            'unread is 0 after mark_all': (r) => {
                try {
                    const body = JSON.parse(r.body);
                    const count = body.data !== undefined ? body.data : body;
                    return count === 0;
                } catch (e) { return false; }
            },
        });
    });

    sleep(0.5);
}

// ============================================
// Phase 6: 알림 삭제
// ============================================
export function deleteNotification() {
    const user = testUser(__VU);
    const token = generateJWT(user);

    group('delete_notification', () => {
        const ids = fetchNotificationIds(token, 5);
        if (ids.length === 0) {
            sleep(0.5);
            return;
        }

        const targetId = ids[Math.floor(Math.random() * ids.length)];
        const res = http.del(
            `${BASE_URL}/api/v1/notifications/${targetId}`,
            null,
            {
                headers: headers(token),
                tags: { name: 'noti_delete' },
            }
        );
        deleteDuration.add(res.timings.duration);
        deleteSuccess.add(res.status === 200);

        check(res, {
            'delete status 200': (r) => r.status === 200,
        });
    });

    sleep(0.3);
}

// ============================================
// Phase 7: 알림 생성 + SSE 풀체인
//   createNotification API가 없으므로 SSE 연결 + 목록 조회로 대체
//   → SSE 연결 성능 + 미전송 알림 복구 성능 측정
// ============================================
export function createAndSSE() {
    const user = testUser(__VU);

    group('sse_connection', () => {
        const sseResult = connectSSE(user, '3s');
        sseConnDuration.add(sseResult.duration);
        sseConnSuccess.add(sseResult.success);

        check(null, {
            'sse connected event received': () => sseResult.connectedEvent,
            'sse connection established': () => sseResult.success,
        });
    });

    sleep(0.2);

    // SSE 연결 직후 목록 조회 (Recovery와 동시 수행 시 경합 측정)
    group('post_sse_list', () => {
        const token = generateJWT(user);
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=10`, {
            headers: headers(token),
            tags: { name: 'noti_post_sse_list' },
        });
        createDuration.add(res.timings.duration);
        createSuccess.add(res.status === 200);
    });

    sleep(0.5);
}

// ============================================
// Phase 8: 실제 사용 패턴 혼합 부하
//   - 60% 목록 조회
//   - 20% 읽지않은 개수
//   - 10% 읽음 처리
//   - 5% 전체 읽음
//   - 5% 삭제
// ============================================
export function mixedRealistic() {
    const user = testUser(__VU);
    const token = generateJWT(user);
    const roll = Math.random();
    let ok = false;

    if (roll < 0.60) {
        // 60%: 목록 조회
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(token),
            tags: { name: 'mixed_list' },
        });
        mixedDuration.add(res.timings.duration);
        ok = res.status === 200;

    } else if (roll < 0.80) {
        // 20%: 읽지않은 개수
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(token),
            tags: { name: 'mixed_unread' },
        });
        mixedDuration.add(res.timings.duration);
        ok = res.status === 200;

    } else if (roll < 0.90) {
        // 10%: 단건 읽음
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const targetId = ids[Math.floor(Math.random() * ids.length)];
            const res = http.put(
                `${BASE_URL}/api/v1/notifications/${targetId}/read`,
                null,
                { headers: headers(token), tags: { name: 'mixed_read' } }
            );
            mixedDuration.add(res.timings.duration);
            ok = res.status === 200;
        } else {
            ok = true; // 데이터 없으면 스킵
        }

    } else if (roll < 0.95) {
        // 5%: 전체 읽음
        const res = http.put(
            `${BASE_URL}/api/v1/notifications/read-all`,
            null,
            { headers: headers(token), tags: { name: 'mixed_read_all' } }
        );
        mixedDuration.add(res.timings.duration);
        ok = res.status === 200;

    } else {
        // 5%: 삭제
        const ids = fetchNotificationIds(token, 5);
        if (ids.length > 0) {
            const targetId = ids[Math.floor(Math.random() * ids.length)];
            const res = http.del(
                `${BASE_URL}/api/v1/notifications/${targetId}`,
                null,
                { headers: headers(token), tags: { name: 'mixed_delete' } }
            );
            mixedDuration.add(res.timings.duration);
            ok = res.status === 200;
        } else {
            ok = true;
        }
    }

    mixedSuccess.add(ok);
    sleep(0.1 + Math.random() * 0.3);
}

// ============================================
// Phase 9: 스파이크 테스트
// ============================================
export function spikeTest() {
    const user = randomUser();
    const token = generateJWT(user);

    const ops = ['list', 'unread', 'read'];
    const op = ops[Math.floor(Math.random() * ops.length)];
    let ok = false;

    if (op === 'list') {
        const res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
            headers: headers(token),
            tags: { name: 'spike_list' },
        });
        spikeDuration.add(res.timings.duration);
        ok = res.status === 200;

    } else if (op === 'unread') {
        const res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
            headers: headers(token),
            tags: { name: 'spike_unread' },
        });
        spikeDuration.add(res.timings.duration);
        ok = res.status === 200;

    } else {
        const ids = fetchNotificationIds(token, 3);
        if (ids.length > 0) {
            const targetId = ids[0];
            const res = http.put(
                `${BASE_URL}/api/v1/notifications/${targetId}/read`,
                null,
                { headers: headers(token), tags: { name: 'spike_read' } }
            );
            spikeDuration.add(res.timings.duration);
            ok = res.status === 200;
        } else {
            ok = true;
        }
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
