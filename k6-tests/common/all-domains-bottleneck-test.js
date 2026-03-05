// =============================================================
// 전체 도메인 통합 병목 탐지 부하 테스트
// =============================================================
// 실행: MSYS_NO_PATHCONV=1 docker run --rm -i --network=host \
//   -v "$(pwd)/k6-tests:/scripts" grafana/k6 run /scripts/all-domains-bottleneck-test.js
//
// 모든 도메인의 핵심 엔드포인트를 실제 사용 비율로 혼합하여 호출.
// 동시에 여러 도메인에 부하가 걸릴 때의 병목 지점을 찾기 위한 테스트.
//
// 트래픽 분배:
//   피드:     35% (가장 빈번한 도메인)
//   알림:     20%
//   검색:     15%
//   채팅:     15%
//   클럽/스케줄: 10%
//   결제/정산:   5%
// =============================================================

import http from 'k6/http';
import { sleep, group } from 'k6';
import { Counter } from 'k6/metrics';
import { generateJWT, headers, sseHeaders, BASE_URL } from '../lib/common.js';
import { getMetrics, recordResponse, recordCustom, buildThresholds, progressiveStages, randomUser, parseData, THRESHOLDS } from '../lib/bottleneck.js';

// ── 전체 엔드포인트 임계값 ──
const ENDPOINT_THRESHOLDS = {
    // 피드
    feed_club_list:      THRESHOLDS.NORMAL,
    feed_detail:         THRESHOLDS.SLOW,
    feed_like_toggle:    THRESHOLDS.FAST,
    feed_comments:       THRESHOLDS.SLOW,
    feed_personal:       THRESHOLDS.SLOW,
    feed_popular:        THRESHOLDS.SLOW,
    // 알림
    notif_list:          THRESHOLDS.NORMAL,
    notif_unread_count:  THRESHOLDS.FAST,
    notif_read_single:   THRESHOLDS.FAST,
    notif_sse_connect:   THRESHOLDS.SLOW,
    // 검색
    search_keyword:      THRESHOLDS.NORMAL,
    search_category:     THRESHOLDS.NORMAL,
    search_suggest:      THRESHOLDS.FAST,
    // 채팅
    chat_room_list:      THRESHOLDS.NORMAL,
    chat_messages:       THRESHOLDS.NORMAL,
    chat_send_message:   THRESHOLDS.FAST,
    // 클럽/스케줄
    club_detail:         THRESHOLDS.FAST,
    club_my_list:        THRESHOLDS.NORMAL,
    schedule_list:       THRESHOLDS.NORMAL,
    schedule_detail:     THRESHOLDS.FAST,
    // 결제/정산
    wallet_balance:      THRESHOLDS.FAST,
    settlement_my_list:  THRESHOLDS.NORMAL,
};

// ── 에러 카테고리 메트릭 ──
const error4xx = new Counter('all_domains_4xx_errors');
const error5xx = new Counter('all_domains_5xx_errors');
const errorTimeout = new Counter('all_domains_timeout_errors');
const domainErrors = {
    feed:     new Counter('domain_feed_errors'),
    notif:    new Counter('domain_notif_errors'),
    search:   new Counter('domain_search_errors'),
    chat:     new Counter('domain_chat_errors'),
    club:     new Counter('domain_club_errors'),
    finance:  new Counter('domain_finance_errors'),
};

export const options = {
    stages: progressiveStages(30, 500, '5m'),
    thresholds: {
        ...buildThresholds(ENDPOINT_THRESHOLDS),
        'all_domains_5xx_errors': ['count<50'],
        'all_domains_timeout_errors': ['count<20'],
    },
};

// ── 검색어 풀 ──
const KEYWORDS = ['축구', '농구', '독서', '영화', '등산', '기타', '요리', '영어', '주식', '캠핑'];
const CATEGORIES = ['CULTURE', 'EXERCISE', 'TRAVEL', 'MUSIC', 'CRAFT', 'SOCIAL', 'LANGUAGE', 'FINANCE'];

const MIN_CLUB = parseInt(__ENV.MIN_CLUB_ID || '1');
const MIN_FEED = parseInt(__ENV.MIN_FEED_ID || '1');
const MIN_CHATROOM = parseInt(__ENV.MIN_CHATROOM_ID || '1');
const MIN_SCHEDULE = parseInt(__ENV.MIN_SCHEDULE_ID || '1');

function rand(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min; }

// ═══════════════════════════════════════════
// 도메인별 시나리오 함수
// ═══════════════════════════════════════════

function trackError(domain, res) {
    if (res.status >= 400 && res.status < 500) error4xx.add(1);
    if (res.status >= 500) { error5xx.add(1); domainErrors[domain].add(1); }
    if (res.timings && res.timings.duration > 10000) errorTimeout.add(1);
}

function scenarioFeed(hdrs) {
    const totalClubs = parseInt(__ENV.TOTAL_CLUBS || '5000');
    const totalFeeds = parseInt(__ENV.TOTAL_FEEDS || '500000');
    const clubId = MIN_CLUB + rand(0, totalClubs - 1);
    const feedId = MIN_FEED + rand(0, totalFeeds - 1);

    // 클럽 피드 목록
    let res = http.get(`${BASE_URL}/api/v1/feeds/club/${clubId}?page=0&size=20`, {
        headers: hdrs, tags: { name: 'feed_club_list' },
    });
    recordResponse('feed_club_list', res);
    trackError('feed', res);
    sleep(0.2);

    // 피드 상세
    res = http.get(`${BASE_URL}/api/v1/feeds/${feedId}`, {
        headers: hdrs, tags: { name: 'feed_detail' },
    });
    recordResponse('feed_detail', res);
    sleep(0.2);

    // 좋아요 토글
    res = http.post(`${BASE_URL}/api/v1/feeds/${feedId}/like`, null, {
        headers: hdrs, tags: { name: 'feed_like_toggle' },
    });
    recordResponse('feed_like_toggle', res);
    sleep(0.2);

    // 댓글 목록
    res = http.get(`${BASE_URL}/api/v1/feeds/${feedId}/comments?page=0&size=20`, {
        headers: hdrs, tags: { name: 'feed_comments' },
    });
    recordResponse('feed_comments', res);
    sleep(0.2);

    // 개인 피드 (50%)
    if (Math.random() < 0.5) {
        res = http.get(`${BASE_URL}/api/v1/feeds/personal?page=0&size=20`, {
            headers: hdrs, tags: { name: 'feed_personal' },
        });
        recordResponse('feed_personal', res);
    }

    // 인기 피드 (30%)
    if (Math.random() < 0.3) {
        res = http.get(`${BASE_URL}/api/v1/feeds/popular?page=0&size=20`, {
            headers: hdrs, tags: { name: 'feed_popular' },
        });
        recordResponse('feed_popular', res);
    }
}

function scenarioNotification(hdrs, token) {
    // 알림 목록
    let res = http.get(`${BASE_URL}/api/v1/notifications?size=20`, {
        headers: hdrs, tags: { name: 'notif_list' },
    });
    recordResponse('notif_list', res);

    let notifIds = [];
    if (res.status === 200) {
        try {
            const body = JSON.parse(res.body);
            if (body.data && body.data.notifications) {
                notifIds = body.data.notifications.map(n => n.notificationId);
            }
        } catch (e) { /* ignore */ }
    }
    sleep(0.2);

    // 안읽은 수
    res = http.get(`${BASE_URL}/api/v1/notifications/unread-count`, {
        headers: hdrs, tags: { name: 'notif_unread_count' },
    });
    recordResponse('notif_unread_count', res);
    sleep(0.2);

    // 읽음 처리
    if (notifIds.length > 0) {
        const id = notifIds[rand(0, notifIds.length - 1)];
        res = http.patch(`${BASE_URL}/api/v1/notifications/${id}/read`, null, {
            headers: hdrs, tags: { name: 'notif_read_single' },
        });
        recordResponse('notif_read_single', res);
    }

    // SSE (10%)
    if (Math.random() < 0.1) {
        const startTime = Date.now();
        res = http.get(`${BASE_URL}/api/v1/sse/subscribe`, {
            headers: { ...hdrs, 'Accept': 'text/event-stream', 'Cache-Control': 'no-cache' },
            timeout: '2s', responseType: 'text',
            tags: { name: 'notif_sse_connect' },
        });
        recordCustom('notif_sse_connect', Date.now() - startTime, res.status !== 200 && res.status !== 0);
    }
}

function scenarioSearch(hdrs) {
    const keyword = KEYWORDS[rand(0, KEYWORDS.length - 1)];

    // 키워드 검색
    let res = http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}&page=0&size=20`, {
        headers: hdrs, tags: { name: 'search_keyword' },
    });
    recordResponse('search_keyword', res);
    sleep(0.2);

    // 카테고리 검색
    const category = CATEGORIES[rand(0, CATEGORIES.length - 1)];
    res = http.get(`${BASE_URL}/api/v1/search?keyword=${encodeURIComponent(keyword)}&category=${category}&page=0&size=20`, {
        headers: hdrs, tags: { name: 'search_category' },
    });
    recordResponse('search_category', res);
    sleep(0.2);

    // 자동완성
    res = http.get(`${BASE_URL}/api/v1/search/suggest?keyword=${encodeURIComponent(keyword.substring(0, 2))}`, {
        headers: hdrs, tags: { name: 'search_suggest' },
    });
    recordResponse('search_suggest', res);
}

function scenarioChat(hdrs) {
    const totalChatrooms = parseInt(__ENV.TOTAL_CHATROOMS || '2500');
    const clubId = MIN_CLUB + rand(0, parseInt(__ENV.TOTAL_CLUBS || '5000') - 1);
    const roomId = MIN_CHATROOM + rand(0, totalChatrooms - 1);

    // 채팅방 목록
    let res = http.get(`${BASE_URL}/api/v1/chat-rooms/club/${clubId}`, {
        headers: hdrs, tags: { name: 'chat_room_list' },
    });
    recordResponse('chat_room_list', res);
    sleep(0.2);

    // 메시지 목록
    res = http.get(`${BASE_URL}/api/v1/chat-rooms/${roomId}/messages?size=30`, {
        headers: hdrs, tags: { name: 'chat_messages' },
    });
    recordResponse('chat_messages', res);
    sleep(0.2);

    // 메시지 전송 (30%)
    if (Math.random() < 0.3) {
        res = http.post(
            `${BASE_URL}/api/v1/chat-rooms/${roomId}/messages`,
            JSON.stringify({ text: `통합테스트 메시지 ${Date.now()}` }),
            { headers: hdrs, tags: { name: 'chat_send_message' } }
        );
        recordResponse('chat_send_message', res);
    }
}

function scenarioClubSchedule(hdrs) {
    const clubId = MIN_CLUB + rand(0, parseInt(__ENV.TOTAL_CLUBS || '5000') - 1);
    const scheduleId = MIN_SCHEDULE + rand(0, parseInt(__ENV.TOTAL_SCHEDULES || '10000') - 1);

    // 클럽 상세
    let res = http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, {
        headers: hdrs, tags: { name: 'club_detail' },
    });
    recordResponse('club_detail', res);
    sleep(0.2);

    // 내 클럽
    res = http.get(`${BASE_URL}/api/v1/clubs/my`, {
        headers: hdrs, tags: { name: 'club_my_list' },
    });
    recordResponse('club_my_list', res);
    sleep(0.2);

    // 스케줄 목록
    res = http.get(`${BASE_URL}/api/v1/schedules/club/${clubId}?page=0&size=20`, {
        headers: hdrs, tags: { name: 'schedule_list' },
    });
    recordResponse('schedule_list', res);
    sleep(0.2);

    // 스케줄 상세
    res = http.get(`${BASE_URL}/api/v1/schedules/${scheduleId}`, {
        headers: hdrs, tags: { name: 'schedule_detail' },
    });
    recordResponse('schedule_detail', res);
}

function scenarioFinance(hdrs) {
    // 지갑 잔액
    let res = http.get(`${BASE_URL}/api/v1/wallets/balance`, {
        headers: hdrs, tags: { name: 'wallet_balance' },
    });
    recordResponse('wallet_balance', res);
    sleep(0.2);

    // 내 정산 목록
    res = http.get(`${BASE_URL}/api/v1/settlements/my?page=0&size=20`, {
        headers: hdrs, tags: { name: 'settlement_my_list' },
    });
    recordResponse('settlement_my_list', res);
}

// ═══════════════════════════════════════════
// 메인 실행: 가중치 기반 시나리오 선택
// ═══════════════════════════════════════════
export default function () {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);

    const roll = Math.random() * 100;

    if (roll < 35) {
        // 피드 35%
        group('피드 시나리오', () => scenarioFeed(hdrs));
    } else if (roll < 55) {
        // 알림 20%
        group('알림 시나리오', () => scenarioNotification(hdrs, token));
    } else if (roll < 70) {
        // 검색 15%
        group('검색 시나리오', () => scenarioSearch(hdrs));
    } else if (roll < 85) {
        // 채팅 15%
        group('채팅 시나리오', () => scenarioChat(hdrs));
    } else if (roll < 95) {
        // 클럽/스케줄 10%
        group('클럽/스케줄 시나리오', () => scenarioClubSchedule(hdrs));
    } else {
        // 결제/정산 5%
        group('결제/정산 시나리오', () => scenarioFinance(hdrs));
    }

    sleep(0.3 + Math.random() * 0.5);
}

// ═══════════════════════════════════════════
// handleSummary — 전체 도메인 병목 리포트
// ═══════════════════════════════════════════
export function handleSummary(data) {
    const line = '─'.repeat(65);
    let summary = `
╔═════════════════════════════════════════════════════════════════╗
║          전체 도메인 통합 병목 탐지 결과 (500 VU peak)            ║
╚═════════════════════════════════════════════════════════════════╝
`;

    const metrics = [
        ['피드 클럽목록',       'feed_club_list_duration'],
        ['피드 상세',           'feed_detail_duration'],
        ['피드 좋아요',         'feed_like_toggle_duration'],
        ['피드 댓글',           'feed_comments_duration'],
        ['피드 개인',           'feed_personal_duration'],
        ['피드 인기',           'feed_popular_duration'],
        ['알림 목록',           'notif_list_duration'],
        ['알림 안읽은수',       'notif_unread_count_duration'],
        ['알림 읽음처리',       'notif_read_single_duration'],
        ['알림 SSE 연결',       'notif_sse_connect_duration'],
        ['검색 키워드',         'search_keyword_duration'],
        ['검색 카테고리',       'search_category_duration'],
        ['검색 자동완성',       'search_suggest_duration'],
        ['채팅 방목록',         'chat_room_list_duration'],
        ['채팅 메시지',         'chat_messages_duration'],
        ['채팅 전송',           'chat_send_message_duration'],
        ['클럽 상세',           'club_detail_duration'],
        ['클럽 내목록',         'club_my_list_duration'],
        ['스케줄 목록',         'schedule_list_duration'],
        ['스케줄 상세',         'schedule_detail_duration'],
        ['지갑 잔액',           'wallet_balance_duration'],
        ['정산 내목록',         'settlement_my_list_duration'],
    ];

    summary += `\n${line}\n`;
    summary += `${'API'.padEnd(22)} ${'p50'.padStart(8)} ${'p95'.padStart(8)} ${'p99'.padStart(8)} ${'max'.padStart(8)}  ${'avg'.padStart(8)}\n`;
    summary += `${line}\n`;

    for (const [label, key] of metrics) {
        const m = data.metrics[key];
        if (m && m.values) {
            const v = m.values;
            summary += `${label.padEnd(22)} ${fmtMs(v['p(50)'])} ${fmtMs(v['p(95)'])} ${fmtMs(v['p(99)'])} ${fmtMs(v['max'])}  ${fmtMs(v['avg'])}\n`;
        }
    }
    summary += `${line}\n`;

    // 도메인별 에러
    const domainMetrics = [
        ['피드',     'domain_feed_errors'],
        ['알림',     'domain_notif_errors'],
        ['검색',     'domain_search_errors'],
        ['채팅',     'domain_chat_errors'],
        ['클럽',     'domain_club_errors'],
        ['결제/정산', 'domain_finance_errors'],
    ];

    summary += `\n도메인별 5xx 에러:\n`;
    for (const [label, key] of domainMetrics) {
        const m = data.metrics[key];
        const cnt = m && m.values ? m.values.count : 0;
        summary += `  ${label.padEnd(12)} ${cnt}\n`;
    }

    const total4xx = data.metrics['all_domains_4xx_errors'];
    const total5xx = data.metrics['all_domains_5xx_errors'];
    const totalTo  = data.metrics['all_domains_timeout_errors'];
    summary += `\n4xx: ${total4xx ? total4xx.values.count : 0}  |  5xx: ${total5xx ? total5xx.values.count : 0}  |  Timeout(>10s): ${totalTo ? totalTo.values.count : 0}\n`;

    let passCount = 0, failCount = 0;
    for (const [, val] of Object.entries(data.metrics)) {
        if (val.thresholds) {
            for (const [, th] of Object.entries(val.thresholds)) {
                if (th.ok) passCount++; else failCount++;
            }
        }
    }
    summary += `Thresholds: ${passCount} PASS / ${failCount} FAIL\n`;

    console.log(summary);
    return { 'stdout': summary };
}

function fmtMs(ms) {
    if (ms === undefined || ms === null) return 'N/A'.padStart(8);
    if (ms < 1000) return (ms.toFixed(0) + 'ms').padStart(8);
    return ((ms / 1000).toFixed(2) + 's').padStart(8);
}
