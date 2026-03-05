// =============================================================
// 피드 캐시 무효화 + 동시성 경합 부하 테스트
// =============================================================
// 실행: MSYS_NO_PATHCONV=1 docker run --rm -i --network=host \
//   -v "$(pwd)/k6-tests:/scripts" grafana/k6 run /scripts/feed/feed-cache-concurrency-test.js
//
// 테스트 목적:
//   1. 캐시 무효화 — 피드 생성/수정 후 즉시 목록 조회 시 반영 여부
//   2. 동시 수정 경합 — 동일 피드를 다수 VU가 동시 수정, 5xx 발생 여부
//   3. 동시 삭제+읽기 — 삭제 직후 읽기, 404 or 200 (5xx 아님) 보장
//   4. 좋아요 핫스팟 — 소수 피드에 집중 토글, Redis Lua 경합
//
// Phase 구성 (~8분, 최대 500 VUs):
// ┌────────┬──────────────────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                              │ VU   │ 시간  │
// ├────────┼──────────────────────────────────────┼──────┼───────┤
// │ 1      │ Warmup                               │ 50   │ 30s   │
// │ 2      │ Cache invalidation (create→list)     │ 200  │ 2m    │
// │ 3      │ Same-feed concurrent update          │ 300  │ 2m    │
// │ 4      │ Same-feed concurrent delete+read     │ 200  │ 1.5m  │
// │ 5      │ Like hotspot contention              │ 500  │ 1.5m  │
// │ 6      │ Cooldown                             │ 5    │ 30s   │
// └────────┴──────────────────────────────────────┴──────┴───────┘
//
// 사전 준비:
//   1. seed-all-domains.sql 실행 (100K 유저, 50K 클럽, 10M 피드 시드)
// =============================================================

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { generateJWT, headers, BASE_URL, makeUser, getRandomUserClub } from '../lib/common.js';
import { THRESHOLDS } from '../lib/bottleneck.js';

// ============================================
// 테스트 데이터
// ============================================
const USER_COUNT = parseInt(__ENV.USER_COUNT || '100000');

// ── 테스트 대상 피드 — setup()에서 API로 실제 ID 수집 ──
const TEST_CLUB_IDS = [64, 159, 381, 501, 747];
let CONCURRENT_UPDATE_TARGETS = [];
let CONCURRENT_DELETE_TARGETS = [];
let LIKE_HOTSPOT_FEEDS = [];

// ============================================
// 커스텀 메트릭
// ============================================
const feedCacheHit              = new Rate('feed_cache_hit');
const feedConcurrentUpdateOk    = new Rate('feed_concurrent_update_ok');
const feedConcurrentDeleteOk    = new Rate('feed_concurrent_delete_ok');
const feedLikeHotspotDuration   = new Trend('feed_like_hotspot_duration', true);
const feedLikeHotspotConflict   = new Counter('feed_like_hotspot_conflict');

// Phase별 성공률
const phase2Success = new Rate('cache_phase2_success');
const phase3Success = new Rate('cache_phase3_success');
const phase4Success = new Rate('cache_phase4_success');
const phase5Success = new Rate('cache_phase5_success');

const totalErrors = new Counter('cache_total_errors');

// ============================================
// 시나리오 설정
// ============================================
export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: 50,
            duration: '30s',
            exec: 'warmup',
            tags: { phase: '1_warmup' },
        },

        // Phase 2: Cache invalidation — 피드 생성 후 목록 조회
        cache_invalidation: {
            executor: 'constant-vus',
            vus: 200,
            duration: '120s',
            startTime: '35s',
            exec: 'cacheInvalidation',
            tags: { phase: '2_cache_invalidation' },
        },

        // Phase 3: Same-feed concurrent update — 10개 피드에 300 VU 동시 수정
        concurrent_update: {
            executor: 'constant-vus',
            vus: 300,
            duration: '120s',
            startTime: '160s',
            exec: 'concurrentUpdate',
            tags: { phase: '3_concurrent_update' },
        },

        // Phase 4: Same-feed concurrent delete+read — 200 VU
        concurrent_delete_read: {
            executor: 'constant-vus',
            vus: 200,
            duration: '90s',
            startTime: '285s',
            exec: 'concurrentDeleteRead',
            tags: { phase: '4_concurrent_delete_read' },
        },

        // Phase 5: Like hotspot — 5개 피드에 500 VU 동시 좋아요 토글
        like_hotspot: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 500 },
                { duration: '60s', target: 500 },
                { duration: '15s', target: 0 },
            ],
            startTime: '380s',
            exec: 'likeHotspot',
            tags: { phase: '5_like_hotspot' },
        },

        // Phase 6: Cooldown
        cooldown: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            startTime: '475s',
            exec: 'warmup',
            tags: { phase: '6_cooldown' },
        },
    },

    thresholds: {
        // -- 글로벌 --
        http_req_failed: ['rate<0.05'],

        // -- 커스텀 메트릭 --
        'feed_cache_hit':                ['rate>0.80'],           // 캐시 반영률 80% 이상
        'feed_concurrent_update_ok':     ['rate>0.95'],           // 동시 수정 성공률 95% 이상
        'feed_concurrent_delete_ok':     ['rate>0.95'],           // 동시 삭제 성공률 95% 이상
        'feed_like_hotspot_duration':    [`p(95)<${THRESHOLDS.FAST}`],  // 200ms

        // -- Phase별 성공률 --
        'cache_phase2_success':  ['rate>0.95'],
        'cache_phase3_success':  ['rate>0.95'],
        'cache_phase4_success':  ['rate>0.95'],
        'cache_phase5_success':  ['rate>0.95'],
    },
};

// ============================================
// 유저 유틸
// ============================================
function randomUser() {
    const userId = Math.floor(Math.random() * USER_COUNT) + 1;
    return makeUser(userId);
}

function vuUser(vuId) {
    const userId = ((vuId - 1) % USER_COUNT) + 1;
    return makeUser(userId);
}

let _feedMap = {};

function initData(data) {
    if (data && data.feedMap && Object.keys(_feedMap).length === 0) {
        _feedMap = data.feedMap;
        if (data.updateTargets) CONCURRENT_UPDATE_TARGETS = data.updateTargets;
        if (data.deleteTargets) CONCURRENT_DELETE_TARGETS = data.deleteTargets;
        if (data.hotspotFeeds) LIKE_HOTSPOT_FEEDS = data.hotspotFeeds;
    }
}

function getRandomFeedForClub(clubId) {
    const feeds = _feedMap[clubId];
    if (feeds && feeds.length > 0) {
        return feeds[Math.floor(Math.random() * feeds.length)];
    }
    return clubId;
}

// ============================================
// setup() — 클럽별 실제 feedId를 API로 수집
// ============================================
export function setup() {
    const adminUser = makeUser(1);
    const token = generateJWT(adminUser);
    const hdrs = headers(token);

    const feedMap = {};
    const updateTargets = [];
    const deleteTargets = [];
    const hotspotFeeds = [];

    for (const clubId of TEST_CLUB_IDS) {
        const res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=20`, {
            headers: hdrs, tags: { name: 'setup_feed_list' },
        });
        if (res.status === 200) {
            try {
                const body = JSON.parse(res.body);
                const feedList = (body.data && body.data.feeds) || body.data || [];
                const ids = Array.isArray(feedList) ? feedList.map(f => f.feedId || f.feed_id || f.id).filter(Boolean) : [];
                feedMap[clubId] = ids;

                // 처음 2개: 수정 대상, 다음 2개: 삭제 대상, 처음 1개: 핫스팟
                ids.slice(0, 2).forEach(fid => updateTargets.push({ feedId: fid, clubId }));
                ids.slice(2, 4).forEach(fid => deleteTargets.push({ feedId: fid, clubId }));
                if (ids.length > 0) hotspotFeeds.push({ feedId: ids[0], clubId });
            } catch (e) {
                feedMap[clubId] = [clubId];
            }
        } else {
            feedMap[clubId] = [clubId];
        }
    }

    // fallback: 빈 배열 방지
    if (updateTargets.length === 0) updateTargets.push({ feedId: 1, clubId: 1 });
    if (deleteTargets.length === 0) deleteTargets.push({ feedId: 1, clubId: 1 });
    if (hotspotFeeds.length === 0) hotspotFeeds.push({ feedId: 1, clubId: 1 });

    return { feedMap, updateTargets, deleteTargets, hotspotFeeds };
}

// ============================================
// Phase 1 & 6: Warmup / Cooldown
// ============================================
export function warmup(data) {
    initData(data);
    const user = randomUser();
    const token = generateJWT(user);
    const clubId = getRandomUserClub(user.userId);

    http.get(`${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=5`, {
        headers: headers(token), tags: { name: 'warmup_list' },
    });

    sleep(0.3);
}

// ============================================
// Phase 2: Cache Invalidation
// ============================================
// 피드 생성 → 즉시 클럽 피드 목록 조회 → 새 피드가 목록에 포함되는지 검증
export function cacheInvalidation(data) {
    initData(data);
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const clubId = getRandomUserClub(user.userId);

    // Step 1: 피드 생성
    const uniqueMarker = `k6_cache_test_${Date.now()}_${Math.random().toString(36).substring(2, 8)}`;
    const createRes = http.post(
        `${BASE_URL}/api/v1/clubs/${clubId}/feeds`,
        JSON.stringify({
            feedUrls: ['https://example.com/cache-test.jpg'],
            content: uniqueMarker,
        }),
        { headers: hdrs, tags: { name: 'ci_create_feed' } }
    );

    const createOk = createRes.status >= 200 && createRes.status < 300;
    phase2Success.add(createOk);

    if (!createOk) {
        totalErrors.add(1);
        feedCacheHit.add(0);
        sleep(0.2);
        return;
    }

    // Step 2: 즉시 목록 조회 (page=0, 최신순이면 첫 페이지에 포함되어야 함)
    const listRes = http.get(
        `${BASE_URL}/api/v1/clubs/${clubId}/feeds?page=0&limit=20`,
        { headers: hdrs, tags: { name: 'ci_list_after_create' } }
    );

    const listOk = listRes.status === 200;
    phase2Success.add(listOk);

    if (!listOk) {
        totalErrors.add(1);
        feedCacheHit.add(0);
        sleep(0.2);
        return;
    }

    // Step 3: 응답에서 새로 생성한 피드의 content가 포함되는지 확인
    let found = false;
    try {
        const body = listRes.body;
        if (body && body.indexOf(uniqueMarker) !== -1) {
            found = true;
        }
    } catch (e) {
        // parse error — treat as miss
    }

    feedCacheHit.add(found ? 1 : 0);

    check(listRes, {
        'cache: new feed appears in list': () => found,
    });

    sleep(0.1 + Math.random() * 0.2);
}

// ============================================
// Phase 3: Same-Feed Concurrent Update
// ============================================
// 10개 타겟 피드에 300 VU가 동시에 PUT 요청 → 5xx 발생 여부 확인
export function concurrentUpdate(data) {
    initData(data);
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 10개 타겟 중 랜덤 선택
    const target = CONCURRENT_UPDATE_TARGETS[Math.floor(Math.random() * CONCURRENT_UPDATE_TARGETS.length)];
    const timestamp = Date.now();
    const vuTag = `vu${__VU}_iter${__ITER}`;

    const updateRes = http.put(
        `${BASE_URL}/api/v1/clubs/${target.clubId}/feeds/${target.feedId}`,
        JSON.stringify({
            content: `concurrent update ${vuTag} at ${timestamp}`,
            feedUrls: ['https://example.com/updated.jpg'],
        }),
        { headers: hdrs, tags: { name: 'cu_update_feed' } }
    );

    // 성공: 200, 비즈니스 에러(권한 없음 등): 4xx → OK, 서버 에러: 5xx → FAIL
    const isNotServerError = updateRes.status < 500;
    feedConcurrentUpdateOk.add(isNotServerError ? 1 : 0);
    phase3Success.add(isNotServerError);

    if (!isNotServerError) {
        totalErrors.add(1);
    }

    check(updateRes, {
        'concurrent update: no 5xx': (r) => r.status < 500,
    });

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 4: Same-Feed Concurrent Delete + Read
// ============================================
// 50% VU가 DELETE, 50% VU가 GET → 결과는 200(존재) or 404(삭제됨)이어야 함, 5xx 불가
export function concurrentDeleteRead(data) {
    initData(data);
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    const target = CONCURRENT_DELETE_TARGETS[Math.floor(Math.random() * CONCURRENT_DELETE_TARGETS.length)];
    const roll = Math.random();

    let res;
    if (roll < 0.5) {
        // DELETE 시도
        res = http.del(
            `${BASE_URL}/api/v1/clubs/${target.clubId}/feeds/${target.feedId}`,
            null,
            { headers: hdrs, tags: { name: 'cdr_delete_feed' } }
        );
    } else {
        // GET 시도 (상세 조회)
        res = http.get(
            `${BASE_URL}/api/v1/clubs/${target.clubId}/feeds/${target.feedId}`,
            { headers: hdrs, tags: { name: 'cdr_read_feed' } }
        );
    }

    // 200(성공), 204(삭제 성공), 404(이미 삭제), 403(권한 없음) 등 4xx → OK
    // 5xx → FAIL
    const isNotServerError = res.status < 500;
    feedConcurrentDeleteOk.add(isNotServerError ? 1 : 0);
    phase4Success.add(isNotServerError);

    if (!isNotServerError) {
        totalErrors.add(1);
    }

    check(res, {
        'concurrent delete/read: no 5xx': (r) => r.status < 500,
        'concurrent delete/read: valid status': (r) =>
            r.status === 200 || r.status === 204 || r.status === 404 || r.status === 403 || r.status === 400,
    });

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 5: Like Hotspot Contention
// ============================================
// 5개 피드에 500 VU가 동시에 좋아요 토글 → Redis Lua 경합 측정
export function likeHotspot(data) {
    initData(data);
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 5개 핫스팟 중 랜덤 선택
    const target = LIKE_HOTSPOT_FEEDS[Math.floor(Math.random() * LIKE_HOTSPOT_FEEDS.length)];

    const likeRes = http.put(
        `${BASE_URL}/api/v1/clubs/${target.clubId}/feeds/${target.feedId}/likes`,
        null,
        { headers: hdrs, tags: { name: 'lh_like_toggle' } }
    );

    feedLikeHotspotDuration.add(likeRes.timings.duration);

    const ok = likeRes.status === 200 || likeRes.status === 404;
    phase5Success.add(ok);

    if (likeRes.status >= 500) {
        totalErrors.add(1);
        feedLikeHotspotConflict.add(1);
    }

    // 409 Conflict 또는 높은 latency(>500ms)를 경합으로 카운트
    if (likeRes.status === 409 || likeRes.timings.duration > 500) {
        feedLikeHotspotConflict.add(1);
    }

    check(likeRes, {
        'like hotspot: no 5xx': (r) => r.status < 500,
        'like hotspot: p95 under 200ms': (r) => r.timings.duration < 200,
    });

    sleep(0.02 + Math.random() * 0.03);
}

// ============================================
// default function (fallback)
// ============================================
export default function () {
    warmup();
}

// ============================================
// 결과 요약 리포트
// ============================================
export function handleSummary(data) {
    const m = data.metrics;
    const pad = (s, n) => String(s).padEnd(n);
    const num = (v, d = 1) => v !== undefined && v !== null ? Number(v).toFixed(d) : 'N/A';
    const pct = (v) => v !== undefined && v !== null ? (Number(v) * 100).toFixed(1) + '%' : 'N/A';

    function metricRow(label, durKey, rateKey) {
        const p50 = num(m[durKey]?.values?.med);
        const p95 = num(m[durKey]?.values?.['p(95)']);
        const p99 = num(m[durKey]?.values?.['p(99)']);
        const rate = pct(m[rateKey]?.values?.rate);
        return `│ ${pad(label, 22)} │ ${pad(p50, 8)} │ ${pad(p95, 8)} │ ${pad(p99, 8)} │ ${pad(rate, 7)} │`;
    }

    const lines = [
        '',
        '╔══════════════════════════════════════════════════════════════════════╗',
        '║        피드 캐시 무효화 + 동시성 경합 부하 테스트 리포트               ║',
        '╚══════════════════════════════════════════════════════════════════════╝',
        '',
        '┌────────────────────────────────────────────────────────────────────┐',
        '│ 1. 캐시 무효화 (Phase 2)                                          │',
        '├────────────────────────────────────────────────────────────────────┤',
        `│ 캐시 적중률 (생성 직후 목록 반영):  ${pad(pct(m.feed_cache_hit?.values?.rate), 10)}                    │`,
        `│ Phase 2 성공률:                    ${pad(pct(m.cache_phase2_success?.values?.rate), 10)}                    │`,
        '└────────────────────────────────────────────────────────────────────┘',
        '',
        '┌────────────────────────────────────────────────────────────────────┐',
        '│ 2. 동시 수정 경합 (Phase 3)                                       │',
        '├────────────────────────────────────────────────────────────────────┤',
        `│ 수정 성공률 (no 5xx):  ${pad(pct(m.feed_concurrent_update_ok?.values?.rate), 10)}                              │`,
        `│ Phase 3 성공률:       ${pad(pct(m.cache_phase3_success?.values?.rate), 10)}                              │`,
        '└────────────────────────────────────────────────────────────────────┘',
        '',
        '┌────────────────────────────────────────────────────────────────────┐',
        '│ 3. 동시 삭제+읽기 (Phase 4)                                       │',
        '├────────────────────────────────────────────────────────────────────┤',
        `│ 삭제/읽기 성공률 (no 5xx):  ${pad(pct(m.feed_concurrent_delete_ok?.values?.rate), 10)}                         │`,
        `│ Phase 4 성공률:            ${pad(pct(m.cache_phase4_success?.values?.rate), 10)}                         │`,
        '└────────────────────────────────────────────────────────────────────┘',
        '',
        '┌────────────────────────────────────────────────────────────────────────────┐',
        '│ 4. 좋아요 핫스팟 (Phase 5)                                                │',
        '├────────────────────────┬──────────┬──────────┬──────────┬─────────────────┤',
        '│ Metric                 │ p50 (ms) │ p95 (ms) │ p99 (ms) │ 성공률          │',
        '├────────────────────────┼──────────┼──────────┼──────────┼─────────────────┤',
        metricRow('like_hotspot', 'feed_like_hotspot_duration', 'cache_phase5_success'),
        '└────────────────────────┴──────────┴──────────┴──────────┴─────────────────┘',
        `  경합 카운트 (5xx + 409 + >500ms): ${num(m.feed_like_hotspot_conflict?.values?.count, 0)} 건`,
        '',
        '┌────────────────────────────────────────────────────────────────────┐',
        '│ 종합                                                              │',
        '├────────────────────────────────────────────────────────────────────┤',
        `│ HTTP 실패율:   ${pad(pct(m.http_req_failed?.values?.rate), 10)}                                       │`,
        `│ 총 에러 수:    ${pad(num(m.cache_total_errors?.values?.count, 0), 10)} 건                                   │`,
        `│ 총 요청 수:    ${pad(num(m.http_reqs?.values?.count, 0), 10)} 건                                   │`,
        `│ 평균 RPS:      ${pad(num(m.http_reqs?.values?.rate, 1), 10)}                                       │`,
        '└────────────────────────────────────────────────────────────────────┘',
        '',
        '판정 기준:',
        `  캐시 적중률 > 80%    동시 수정 성공률 > 95%    동시 삭제 성공률 > 95%`,
        `  좋아요 핫스팟 p95 < ${THRESHOLDS.FAST}ms    HTTP 실패율 < 5%`,
        '',
    ];

    console.log(lines.join('\n'));
    return {};
}
