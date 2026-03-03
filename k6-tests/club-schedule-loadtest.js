// =============================================================
// 클럽/스케줄 도메인 통합 부하 테스트
// =============================================================
// 실행: MSYS_NO_PATHCONV=1 docker run --rm -i --network=host \
//   -v "$(pwd)/k6-tests:/scripts" grafana/k6 run /scripts/club-schedule-loadtest.js
//
// 사전 준비:
//   1. seed-postgres.sql 실행 (1000 유저, 1000 클럽, 2000 스케줄)
//   2. application-local.yml 설정 확인
//
// 흡수된 테스트:
//   - club-schedule-bottleneck-test.js → Phase 2 (Baseline 임계값)
//
// Phase 구성 (~18분, 최대 1000 VUs):
// ┌────────┬──────────────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                          │ VU   │ 시간  │
// ├────────┼──────────────────────────────────┼──────┼───────┤
// │ 1      │ Warmup                           │ 50   │ 30s   │
// │ 2      │ Baseline — 전 API 혼합            │ 300  │ 2m    │
// │ 3      │ 일정 생성 + 참가 동시성            │ 500  │ 2m    │
// │ 4      │ 클럽 가입/탈퇴 경합               │ 350  │ 2m    │
// │ 5      │ 일정 목록 + 상세 조회              │ 500  │ 2m    │
// │ 6      │ 일정 참가 취소 + 재참가 경합       │ 350  │ 1.5m  │
// │ 7      │ 클럽 목록 + 멤버 조회              │ 350  │ 1.5m  │
// │ 8      │ Spike 전 API 혼합                 │ 1000 │ 1.5m  │
// │ 9      │ Double Spike                     │ 800  │ 2m    │
// │ 10     │ Soak                             │ 300  │ 3m    │
// │ 11     │ Cooldown                         │ 5    │ 30s   │
// └────────┴──────────────────────────────────┴──────┴───────┘
//
// 인프라 튜닝 탐지 포인트:
//   - HikariCP: Phase 3,4에서 일정 생성/참가, 클럽 가입/탈퇴 동시성 커넥션 풀 고갈
//   - InnoDB:   Phase 4에서 member_count UPDATE X-lock 경합
//   - Tomcat:   Phase 8에서 1000 VU spike 시 스레드 풀 포화
//   - JVM:      Phase 10에서 GC pause 누적, 메모리 안정성
// =============================================================

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
    generateJWT, headers, BASE_URL, makeUser,
    getUserClubs, getRandomUserClub,
    getUserSchedules, getScheduleClub,
    MIN_CLUB, MIN_SCHEDULE, TOTAL_CLUBS, TOTAL_SCHEDULES,
} from './lib/common.js';
import { THRESHOLDS } from './lib/bottleneck.js';

// ============================================
// 테스트 데이터
// ============================================
const USER_COUNT   = parseInt(__ENV.USER_COUNT || '100000');
const MAX_CLUB     = parseInt(__ENV.MAX_CLUB_ID || '50000');
const MAX_SCHEDULE = parseInt(__ENV.MAX_SCHEDULE_ID || '100000');

// ============================================
// 커스텀 메트릭 — 엔드포인트별
// ============================================
const clubDetailDur       = new Trend('club_detail_duration', true);
const clubJoinDur         = new Trend('club_join_duration', true);
const clubLeaveDur        = new Trend('club_leave_duration', true);
const scheduleListDur     = new Trend('schedule_list_duration', true);
const scheduleDetailDur   = new Trend('schedule_detail_duration', true);
const scheduleCreateDur   = new Trend('schedule_create_duration', true);
const schedulePartDur     = new Trend('schedule_participate_duration', true);
const scheduleCancelDur   = new Trend('schedule_cancel_duration', true);

// ============================================
// 커스텀 메트릭 — Phase별 성공률
// ============================================
const phase2Success  = new Rate('cs_phase2_success');
const phase3Success  = new Rate('cs_phase3_success');
const phase4Success  = new Rate('cs_phase4_success');
const phase5Success  = new Rate('cs_phase5_success');
const phase6Success  = new Rate('cs_phase6_success');
const phase7Success  = new Rate('cs_phase7_success');
const phase8Success  = new Rate('cs_phase8_success');
const phase9Success  = new Rate('cs_phase9_success');
const phase10Success = new Rate('cs_phase10_success');

const totalErrors = new Counter('cs_total_errors');

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

        // Phase 2: Baseline — 전 API 혼합 (bottleneck 임계값 포함)
        baseline: {
            executor: 'constant-vus',
            vus: 300,
            duration: '120s',
            startTime: '35s',
            exec: 'baseline',
            tags: { phase: '2_baseline' },
        },

        // Phase 3: 일정 생성 + 참가 동시성 — 500 VUs
        schedule_concurrency: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 500 },
                { duration: '80s', target: 500 },
                { duration: '20s', target: 0 },
            ],
            startTime: '160s',
            exec: 'scheduleConcurrency',
            tags: { phase: '3_schedule_concurrency' },
        },

        // Phase 4: 클럽 가입/탈퇴 경합 — 350 VUs
        club_join_leave: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 350 },
                { duration: '80s', target: 350 },
                { duration: '20s', target: 0 },
            ],
            startTime: '285s',
            exec: 'clubJoinLeave',
            tags: { phase: '4_club_join_leave' },
        },

        // Phase 5: 일정 목록 + 상세 조회 — 500 VUs
        schedule_read: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '20s', target: 500 },
                { duration: '80s', target: 500 },
                { duration: '20s', target: 0 },
            ],
            startTime: '410s',
            exec: 'scheduleRead',
            tags: { phase: '5_schedule_read' },
        },

        // Phase 6: 일정 참가 취소 + 재참가 경합 — 350 VUs
        schedule_rejoin: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 350 },
                { duration: '60s', target: 350 },
                { duration: '15s', target: 0 },
            ],
            startTime: '535s',
            exec: 'scheduleRejoin',
            tags: { phase: '6_schedule_rejoin' },
        },

        // Phase 7: 클럽 목록 + 멤버 조회 — 350 VUs
        club_read: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '15s', target: 350 },
                { duration: '60s', target: 350 },
                { duration: '15s', target: 0 },
            ],
            startTime: '630s',
            exec: 'clubRead',
            tags: { phase: '7_club_read' },
        },

        // Phase 8: Spike 전 API 혼합 — 1000 VUs
        spike: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '10s', target: 1000 },
                { duration: '50s', target: 1000 },
                { duration: '20s', target: 5 },
                { duration: '10s', target: 5 },
            ],
            startTime: '725s',
            exec: 'spikeTest',
            tags: { phase: '8_spike' },
        },

        // Phase 9: Double Spike — 회복 후 재폭증
        double_spike: {
            executor: 'ramping-vus',
            startVUs: 5,
            stages: [
                { duration: '10s', target: 700 },
                { duration: '20s', target: 700 },
                { duration: '10s', target: 10 },
                { duration: '15s', target: 10 },
                { duration: '10s', target: 800 },
                { duration: '25s', target: 800 },
                { duration: '15s', target: 5 },
                { duration: '15s', target: 5 },
            ],
            startTime: '820s',
            exec: 'doubleSpikeTest',
            tags: { phase: '9_double_spike' },
        },

        // Phase 10: Soak — 중간 부하 장시간
        soak: {
            executor: 'constant-vus',
            vus: 300,
            duration: '180s',
            startTime: '945s',
            exec: 'soakTest',
            tags: { phase: '10_soak' },
        },

        // Phase 11: Cooldown
        cooldown: {
            executor: 'constant-vus',
            vus: 5,
            duration: '30s',
            startTime: '1130s',
            exec: 'warmup',
            tags: { phase: '11_cooldown' },
        },
    },

    thresholds: {
        // ── 글로벌 ──
        http_req_failed: ['rate<0.05'],

        // ── 엔드포인트별 (bottleneck 임계값) ──
        'club_detail_duration':           [`p(95)<${THRESHOLDS.NORMAL}`],    // 500ms
        'club_join_duration':             [`p(95)<${THRESHOLDS.NORMAL}`],    // 500ms
        'club_leave_duration':            [`p(95)<${THRESHOLDS.NORMAL}`],    // 500ms
        'schedule_list_duration':         [`p(95)<${THRESHOLDS.NORMAL}`],    // 500ms
        'schedule_detail_duration':       [`p(95)<${THRESHOLDS.NORMAL}`],    // 500ms
        'schedule_create_duration':       [`p(95)<${THRESHOLDS.NORMAL}`],    // 500ms
        'schedule_participate_duration':  [`p(95)<${THRESHOLDS.FAST}`],      // 200ms
        'schedule_cancel_duration':       [`p(95)<${THRESHOLDS.FAST}`],      // 200ms

        // ── Phase별 성공률 ──
        'cs_phase2_success':  ['rate>0.98'],
        'cs_phase3_success':  ['rate>0.95'],
        'cs_phase4_success':  ['rate>0.95'],
        'cs_phase5_success':  ['rate>0.98'],
        'cs_phase6_success':  ['rate>0.95'],
        'cs_phase7_success':  ['rate>0.98'],
        'cs_phase8_success':  ['rate>0.85'],
        'cs_phase9_success':  ['rate>0.85'],
        'cs_phase10_success': ['rate>0.98'],
    },
};

// ============================================
// 유저 / 데이터 유틸
// ============================================
function randomUser() {
    const userId = Math.floor(Math.random() * USER_COUNT) + 1;
    return makeUser(userId);
}

function vuUser(vuId) {
    const userId = ((vuId - 1) % USER_COUNT) + 1;
    return makeUser(userId);
}

function randomClubId() {
    return MIN_CLUB + Math.floor(Math.random() * TOTAL_CLUBS);
}

function randomScheduleId() {
    return MIN_SCHEDULE + Math.floor(Math.random() * TOTAL_SCHEDULES);
}

function futureDate() {
    const d = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
    return d.toISOString().replace('Z', '').split('.')[0]; // "2026-03-11T12:00:00"
}

function isSuccess(status) {
    return status >= 200 && status < 400;
}

// ============================================
// Phase 1 & 11: Warmup / Cooldown
// ============================================
export function warmup() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const clubId = randomClubId();

    http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, {
        headers: hdrs, tags: { name: 'warmup_club_detail' },
    });
    http.get(`${BASE_URL}/api/v1/clubs/${clubId}/schedules`, {
        headers: hdrs, tags: { name: 'warmup_schedule_list' },
    });
    sleep(0.3);
}

// ============================================
// Phase 2: Baseline — 전 API 혼합 + bottleneck 임계값
// ============================================
export function baseline() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const clubId = getRandomUserClub(user.userId);
    const scheduleId = randomScheduleId();
    const scheduleClubId = getScheduleClub(scheduleId);

    // ── 클럽 상세 ──
    const clubDetailRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, {
        headers: hdrs, tags: { name: 'bl_club_detail' },
    });
    clubDetailDur.add(clubDetailRes.timings.duration);
    phase2Success.add(isSuccess(clubDetailRes.status));
    if (!isSuccess(clubDetailRes.status)) totalErrors.add(1);
    sleep(0.2);

    // ── 일정 목록 ──
    const schedListRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/schedules`, {
        headers: hdrs, tags: { name: 'bl_schedule_list' },
    });
    scheduleListDur.add(schedListRes.timings.duration);
    phase2Success.add(isSuccess(schedListRes.status));
    if (!isSuccess(schedListRes.status)) totalErrors.add(1);
    sleep(0.2);

    // ── 일정 상세 ──
    const schedDetailRes = http.get(`${BASE_URL}/api/v1/clubs/${scheduleClubId}/schedules/${scheduleId}`, {
        headers: hdrs, tags: { name: 'bl_schedule_detail' },
    });
    scheduleDetailDur.add(schedDetailRes.timings.duration);
    phase2Success.add(isSuccess(schedDetailRes.status));
    if (!isSuccess(schedDetailRes.status)) totalErrors.add(1);
    sleep(0.2);

    // ── 일정 참가 (20%) — PATCH .../users ──
    if (Math.random() < 0.2) {
        const partRes = http.patch(`${BASE_URL}/api/v1/clubs/${scheduleClubId}/schedules/${scheduleId}/users`, null, {
            headers: hdrs, tags: { name: 'bl_schedule_participate' },
        });
        schedulePartDur.add(partRes.timings.duration);
        phase2Success.add(isSuccess(partRes.status));
    }
    sleep(0.2);

    // ── 클럽 가입 (5%) ──
    if (Math.random() < 0.05) {
        const joinClubId = randomClubId();
        const joinRes = http.post(`${BASE_URL}/api/v1/clubs/${joinClubId}/join`, null, {
            headers: hdrs, tags: { name: 'bl_club_join' },
        });
        clubJoinDur.add(joinRes.timings.duration);
        phase2Success.add(isSuccess(joinRes.status));

        // 가입 성공 시 바로 탈퇴
        if (joinRes.status === 200) {
            sleep(0.1);
            const leaveRes = http.del(`${BASE_URL}/api/v1/clubs/${joinClubId}/leave`, null, {
                headers: hdrs, tags: { name: 'bl_club_leave' },
            });
            clubLeaveDur.add(leaveRes.timings.duration);
            phase2Success.add(isSuccess(leaveRes.status));
        }
    }

    // ── 일정 생성 (3%) — clubId in path, no clubId in body ──
    if (Math.random() < 0.03) {
        const createRes = http.post(
            `${BASE_URL}/api/v1/clubs/${clubId}/schedules`,
            JSON.stringify({
                name: `k6bl${Date.now() % 100000}`,
                location: '테스트 장소',
                cost: 5000,
                userLimit: 10,
                scheduleTime: futureDate(),
            }),
            { headers: hdrs, tags: { name: 'bl_schedule_create' } }
        );
        scheduleCreateDur.add(createRes.timings.duration);
        phase2Success.add(isSuccess(createRes.status));
    }

    sleep(0.3);
}

// ============================================
// Phase 3: 일정 생성 + 참가 동시성 — 500 VUs
// ============================================
export function scheduleConcurrency() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const clubId = getRandomUserClub(user.userId);
    const roll = Math.random();

    if (roll < 0.35) {
        // 35%: 일정 생성 (user must be LEADER; non-leaders get 403 — realistic)
        const createRes = http.post(
            `${BASE_URL}/api/v1/clubs/${clubId}/schedules`,
            JSON.stringify({
                name: `k6cc${__VU}_${__ITER}`,
                location: '동시성 테스트 장소',
                cost: 3000,
                userLimit: 20,
                scheduleTime: futureDate(),
            }),
            { headers: hdrs, tags: { name: 'p3_schedule_create' } }
        );
        scheduleCreateDur.add(createRes.timings.duration);
        phase3Success.add(isSuccess(createRes.status));
        if (!isSuccess(createRes.status)) totalErrors.add(1);

        // 생성된 일정에 바로 참가 시도
        if (createRes.status === 200 || createRes.status === 201) {
            try {
                const body = JSON.parse(createRes.body);
                const data = body.data || body;
                const newScheduleId = data.scheduleId || data.id;
                if (newScheduleId) {
                    sleep(0.05);
                    const partRes = http.patch(`${BASE_URL}/api/v1/clubs/${clubId}/schedules/${newScheduleId}/users`, null, {
                        headers: hdrs, tags: { name: 'p3_schedule_participate_new' },
                    });
                    schedulePartDur.add(partRes.timings.duration);
                    phase3Success.add(isSuccess(partRes.status));
                }
            } catch (e) { /* ignore */ }
        }
    } else {
        // 65%: 기존 일정에 참가 — use user's own club schedule
        const scheduleId = randomScheduleId();
        const scheduleClubId = getScheduleClub(scheduleId);
        const partRes = http.patch(`${BASE_URL}/api/v1/clubs/${scheduleClubId}/schedules/${scheduleId}/users`, null, {
            headers: hdrs, tags: { name: 'p3_schedule_participate' },
        });
        schedulePartDur.add(partRes.timings.duration);
        phase3Success.add(isSuccess(partRes.status));
        if (!isSuccess(partRes.status)) totalErrors.add(1);
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 4: 클럽 가입/탈퇴 경합 — 350 VUs
// ============================================
export function clubJoinLeave() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    // 동일 클럽에 다수 VU가 동시 가입/탈퇴 → member_count UPDATE X-lock 경합
    const clubId = randomClubId();
    const roll = Math.random();

    if (roll < 0.50) {
        // 50%: 가입 시도
        const joinRes = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/join`, null, {
            headers: hdrs, tags: { name: 'p4_club_join' },
        });
        clubJoinDur.add(joinRes.timings.duration);
        phase4Success.add(isSuccess(joinRes.status));
        if (!isSuccess(joinRes.status)) totalErrors.add(1);

        // 가입 성공 시 짧은 대기 후 탈퇴 (경합 유발)
        if (joinRes.status === 200) {
            sleep(0.1 + Math.random() * 0.2);
            const leaveRes = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, {
                headers: hdrs, tags: { name: 'p4_club_leave_after_join' },
            });
            clubLeaveDur.add(leaveRes.timings.duration);
            phase4Success.add(isSuccess(leaveRes.status));
        }
    } else if (roll < 0.80) {
        // 30%: 탈퇴 시도 (이미 가입된 상태 가정)
        const leaveRes = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, {
            headers: hdrs, tags: { name: 'p4_club_leave' },
        });
        clubLeaveDur.add(leaveRes.timings.duration);
        phase4Success.add(isSuccess(leaveRes.status));
        if (!isSuccess(leaveRes.status)) totalErrors.add(1);
    } else {
        // 20%: 클럽 상세 조회 (읽기와 쓰기 혼합)
        const detailRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, {
            headers: hdrs, tags: { name: 'p4_club_detail' },
        });
        clubDetailDur.add(detailRes.timings.duration);
        phase4Success.add(isSuccess(detailRes.status));
        if (!isSuccess(detailRes.status)) totalErrors.add(1);
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 5: 일정 목록 + 상세 조회 — 500 VUs
// ============================================
export function scheduleRead() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const clubId = randomClubId();
    const roll = Math.random();

    if (roll < 0.40) {
        // 40%: 일정 목록
        const listRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/schedules`, {
            headers: hdrs, tags: { name: 'p5_schedule_list' },
        });
        scheduleListDur.add(listRes.timings.duration);
        phase5Success.add(isSuccess(listRes.status));
        if (!isSuccess(listRes.status)) totalErrors.add(1);

        // 목록에서 상세 조회 체인
        if (listRes.status === 200) {
            try {
                const body = JSON.parse(listRes.body);
                const schedules = body.data; // direct array from CommonResponse.success(List<>)
                if (schedules && schedules.length > 0) {
                    const picked = schedules[Math.floor(Math.random() * schedules.length)];
                    const sid = picked.scheduleId || picked.id;
                    if (sid) {
                        const detailRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/schedules/${sid}`, {
                            headers: hdrs, tags: { name: 'p5_schedule_detail_chain' },
                        });
                        scheduleDetailDur.add(detailRes.timings.duration);
                        phase5Success.add(isSuccess(detailRes.status));
                    }
                }
            } catch (e) { /* ignore */ }
        }
    } else if (roll < 0.70) {
        // 30%: 일정 상세 직접 조회
        const scheduleId = randomScheduleId();
        const scheduleClubId = getScheduleClub(scheduleId);
        const detailRes = http.get(`${BASE_URL}/api/v1/clubs/${scheduleClubId}/schedules/${scheduleId}`, {
            headers: hdrs, tags: { name: 'p5_schedule_detail' },
        });
        scheduleDetailDur.add(detailRes.timings.duration);
        phase5Success.add(isSuccess(detailRes.status));
        if (!isSuccess(detailRes.status)) totalErrors.add(1);
    } else {
        // 30%: 클럽 상세 + 일정 목록 연쇄
        const detailRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, {
            headers: hdrs, tags: { name: 'p5_club_detail' },
        });
        clubDetailDur.add(detailRes.timings.duration);
        phase5Success.add(isSuccess(detailRes.status));
        if (!isSuccess(detailRes.status)) totalErrors.add(1);

        sleep(0.05);

        const listRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/schedules`, {
            headers: hdrs, tags: { name: 'p5_schedule_list_chain' },
        });
        scheduleListDur.add(listRes.timings.duration);
        phase5Success.add(isSuccess(listRes.status));
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 6: 일정 참가 취소 + 재참가 경합 — 350 VUs
// ============================================
export function scheduleRejoin() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    // Use a schedule the user is already participating in (from seed data)
    const userSchedules = getUserSchedules(user.userId);
    let scheduleId, clubId;
    if (userSchedules.length > 0) {
        scheduleId = userSchedules[Math.floor(Math.random() * userSchedules.length)];
        clubId = getScheduleClub(scheduleId);
    } else {
        // Fallback: random schedule (may get 4xx — acceptable for load test)
        scheduleId = randomScheduleId();
        clubId = getScheduleClub(scheduleId);
    }

    // 참가 → 취소 → 재참가 패턴 (동일 일정에 경합)
    // PATCH /api/v1/clubs/{clubId}/schedules/{scheduleId}/users
    const partRes = http.patch(`${BASE_URL}/api/v1/clubs/${clubId}/schedules/${scheduleId}/users`, null, {
        headers: hdrs, tags: { name: 'p6_schedule_participate' },
    });
    schedulePartDur.add(partRes.timings.duration);
    phase6Success.add(isSuccess(partRes.status));
    if (!isSuccess(partRes.status)) totalErrors.add(1);

    if (isSuccess(partRes.status)) {
        sleep(0.05 + Math.random() * 0.1);

        // 참가 취소 — DELETE /api/v1/clubs/{clubId}/schedules/{scheduleId}/users
        const cancelRes = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/schedules/${scheduleId}/users`, null, {
            headers: hdrs, tags: { name: 'p6_schedule_cancel' },
        });
        scheduleCancelDur.add(cancelRes.timings.duration);
        phase6Success.add(isSuccess(cancelRes.status));
        if (!isSuccess(cancelRes.status)) totalErrors.add(1);

        if (isSuccess(cancelRes.status)) {
            sleep(0.05 + Math.random() * 0.1);

            // 재참가 — PATCH
            const rejoinRes = http.patch(`${BASE_URL}/api/v1/clubs/${clubId}/schedules/${scheduleId}/users`, null, {
                headers: hdrs, tags: { name: 'p6_schedule_rejoin' },
            });
            schedulePartDur.add(rejoinRes.timings.duration);
            phase6Success.add(isSuccess(rejoinRes.status));
        }
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 7: 클럽 목록 + 상세 조회 — 350 VUs
// ============================================
export function clubRead() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();

    if (roll < 0.40) {
        // 40%: 클럽 상세 → 일정 목록 연쇄
        const clubId = randomClubId();
        const detailRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, {
            headers: hdrs, tags: { name: 'p7_club_detail' },
        });
        clubDetailDur.add(detailRes.timings.duration);
        phase7Success.add(isSuccess(detailRes.status));
        if (!isSuccess(detailRes.status)) totalErrors.add(1);

        sleep(0.05);

        const schedListRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/schedules`, {
            headers: hdrs, tags: { name: 'p7_schedule_list' },
        });
        scheduleListDur.add(schedListRes.timings.duration);
        phase7Success.add(isSuccess(schedListRes.status));
        if (!isSuccess(schedListRes.status)) totalErrors.add(1);
    } else if (roll < 0.70) {
        // 30%: 여러 클럽 상세 조회 (순차) + 각 클럽의 일정 목록
        const clubId = randomClubId();
        for (let page = 0; page < 3; page++) {
            const cid = MIN_CLUB + ((clubId - MIN_CLUB + page) % TOTAL_CLUBS);
            const res = http.get(`${BASE_URL}/api/v1/clubs/${cid}`, {
                headers: hdrs, tags: { name: `p7_club_detail_page${page}` },
            });
            clubDetailDur.add(res.timings.duration);
            phase7Success.add(isSuccess(res.status));
            if (!isSuccess(res.status)) { totalErrors.add(1); break; }
            sleep(0.02);
        }
    } else {
        // 30%: 여러 클럽 상세 조회 (순차)
        for (let i = 0; i < 3; i++) {
            const cid = randomClubId();
            const res = http.get(`${BASE_URL}/api/v1/clubs/${cid}`, {
                headers: hdrs, tags: { name: 'p7_club_detail_multi' },
            });
            clubDetailDur.add(res.timings.duration);
            phase7Success.add(isSuccess(res.status));
            if (!isSuccess(res.status)) { totalErrors.add(1); break; }
            sleep(0.02);
        }
    }

    sleep(0.05 + Math.random() * 0.1);
}

// ============================================
// Phase 8: Spike 전 API 혼합 — 1000 VUs 순간 폭증
// ============================================
export function spikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const ops = [
        'club_detail', 'schedule_list_detail', 'club_join',
        'schedule_list', 'schedule_detail', 'schedule_create',
        'schedule_participate', 'schedule_cancel',
    ];
    const op = ops[Math.floor(Math.random() * ops.length)];
    let ok = false;

    if (op === 'club_detail') {
        const res = http.get(`${BASE_URL}/api/v1/clubs/${randomClubId()}`, {
            headers: hdrs, tags: { name: 'sp_club_detail' },
        });
        clubDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (op === 'schedule_list_detail') {
        // Club detail + schedule list (replaces old club_members)
        const cid = randomClubId();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${cid}`, {
            headers: hdrs, tags: { name: 'sp_club_detail_chain' },
        });
        clubDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
        if (ok) {
            const listRes = http.get(`${BASE_URL}/api/v1/clubs/${cid}/schedules`, {
                headers: hdrs, tags: { name: 'sp_schedule_list_chain' },
            });
            scheduleListDur.add(listRes.timings.duration);
            ok = isSuccess(listRes.status);
        }
    } else if (op === 'club_join') {
        const clubId = randomClubId();
        const res = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/join`, null, {
            headers: hdrs, tags: { name: 'sp_club_join' },
        });
        clubJoinDur.add(res.timings.duration);
        ok = isSuccess(res.status);
        // 가입 성공 시 바로 탈퇴
        if (res.status === 200) {
            const leaveRes = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, {
                headers: hdrs, tags: { name: 'sp_club_leave' },
            });
            clubLeaveDur.add(leaveRes.timings.duration);
        }
    } else if (op === 'schedule_list') {
        const res = http.get(`${BASE_URL}/api/v1/clubs/${randomClubId()}/schedules`, {
            headers: hdrs, tags: { name: 'sp_schedule_list' },
        });
        scheduleListDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (op === 'schedule_detail') {
        const sid = randomScheduleId();
        const scid = getScheduleClub(sid);
        const res = http.get(`${BASE_URL}/api/v1/clubs/${scid}/schedules/${sid}`, {
            headers: hdrs, tags: { name: 'sp_schedule_detail' },
        });
        scheduleDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (op === 'schedule_create') {
        const cid = getRandomUserClub(user.userId);
        const res = http.post(
            `${BASE_URL}/api/v1/clubs/${cid}/schedules`,
            JSON.stringify({
                name: `k6sp${Date.now() % 100000}`,
                location: 'spike 테스트',
                cost: 5000,
                userLimit: 15,
                scheduleTime: futureDate(),
            }),
            { headers: hdrs, tags: { name: 'sp_schedule_create' } }
        );
        scheduleCreateDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (op === 'schedule_participate') {
        const sid = randomScheduleId();
        const scid = getScheduleClub(sid);
        const res = http.patch(`${BASE_URL}/api/v1/clubs/${scid}/schedules/${sid}/users`, null, {
            headers: hdrs, tags: { name: 'sp_schedule_participate' },
        });
        schedulePartDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (op === 'schedule_cancel') {
        // Use user's known schedules for cancel to have a higher chance of success
        const userSchedules = getUserSchedules(user.userId);
        let sid, scid;
        if (userSchedules.length > 0) {
            sid = userSchedules[Math.floor(Math.random() * userSchedules.length)];
            scid = getScheduleClub(sid);
        } else {
            sid = randomScheduleId();
            scid = getScheduleClub(sid);
        }
        const res = http.del(`${BASE_URL}/api/v1/clubs/${scid}/schedules/${sid}/users`, null, {
            headers: hdrs, tags: { name: 'sp_schedule_cancel' },
        });
        scheduleCancelDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    }

    phase8Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.02);
}

// ============================================
// Phase 9: Double Spike — 이중 스파이크
// ============================================
export function doubleSpikeTest() {
    const user = randomUser();
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let ok = false;

    if (roll < 0.25) {
        // 25%: 클럽 상세
        const res = http.get(`${BASE_URL}/api/v1/clubs/${randomClubId()}`, {
            headers: hdrs, tags: { name: 'ds_club_detail' },
        });
        clubDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.45) {
        // 20%: 일정 목록
        const res = http.get(`${BASE_URL}/api/v1/clubs/${randomClubId()}/schedules`, {
            headers: hdrs, tags: { name: 'ds_schedule_list' },
        });
        scheduleListDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.60) {
        // 15%: 일정 상세
        const sid = randomScheduleId();
        const scid = getScheduleClub(sid);
        const res = http.get(`${BASE_URL}/api/v1/clubs/${scid}/schedules/${sid}`, {
            headers: hdrs, tags: { name: 'ds_schedule_detail' },
        });
        scheduleDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.75) {
        // 15%: 클럽 상세 + 일정 목록 (replaces old club_members)
        const cid = randomClubId();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${cid}`, {
            headers: hdrs, tags: { name: 'ds_club_detail_chain' },
        });
        clubDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
        if (ok) {
            const listRes = http.get(`${BASE_URL}/api/v1/clubs/${cid}/schedules`, {
                headers: hdrs, tags: { name: 'ds_schedule_list_chain' },
            });
            scheduleListDur.add(listRes.timings.duration);
        }
    } else if (roll < 0.85) {
        // 10%: 일정 참가
        const sid = randomScheduleId();
        const scid = getScheduleClub(sid);
        const res = http.patch(`${BASE_URL}/api/v1/clubs/${scid}/schedules/${sid}/users`, null, {
            headers: hdrs, tags: { name: 'ds_schedule_participate' },
        });
        schedulePartDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.93) {
        // 8%: 클럽 가입
        const clubId = randomClubId();
        const res = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/join`, null, {
            headers: hdrs, tags: { name: 'ds_club_join' },
        });
        clubJoinDur.add(res.timings.duration);
        ok = isSuccess(res.status);
        if (res.status === 200) {
            const leaveRes = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, {
                headers: hdrs, tags: { name: 'ds_club_leave' },
            });
            clubLeaveDur.add(leaveRes.timings.duration);
        }
    } else {
        // 7%: 일정 생성
        const cid = getRandomUserClub(user.userId);
        const res = http.post(
            `${BASE_URL}/api/v1/clubs/${cid}/schedules`,
            JSON.stringify({
                name: `k6ds${Date.now() % 100000}`,
                location: 'double spike',
                cost: 5000,
                userLimit: 10,
                scheduleTime: futureDate(),
            }),
            { headers: hdrs, tags: { name: 'ds_schedule_create' } }
        );
        scheduleCreateDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    }

    phase9Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.02 + Math.random() * 0.03);
}

// ============================================
// Phase 10: Soak — 300 VUs 3분 안정성
// ============================================
export function soakTest() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);
    const roll = Math.random();
    let ok = false;

    if (roll < 0.25) {
        // 25%: 클럽 상세
        const res = http.get(`${BASE_URL}/api/v1/clubs/${randomClubId()}`, {
            headers: hdrs, tags: { name: 'soak_club_detail' },
        });
        clubDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.45) {
        // 20%: 일정 목록
        const res = http.get(`${BASE_URL}/api/v1/clubs/${randomClubId()}/schedules`, {
            headers: hdrs, tags: { name: 'soak_schedule_list' },
        });
        scheduleListDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.60) {
        // 15%: 일정 상세
        const sid = randomScheduleId();
        const scid = getScheduleClub(sid);
        const res = http.get(`${BASE_URL}/api/v1/clubs/${scid}/schedules/${sid}`, {
            headers: hdrs, tags: { name: 'soak_schedule_detail' },
        });
        scheduleDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.75) {
        // 15%: 클럽 상세 + 일정 목록 (replaces old club_members)
        const cid = randomClubId();
        const res = http.get(`${BASE_URL}/api/v1/clubs/${cid}`, {
            headers: hdrs, tags: { name: 'soak_club_detail_chain' },
        });
        clubDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
        if (ok) {
            const listRes = http.get(`${BASE_URL}/api/v1/clubs/${cid}/schedules`, {
                headers: hdrs, tags: { name: 'soak_schedule_list_chain' },
            });
            scheduleListDur.add(listRes.timings.duration);
        }
    } else if (roll < 0.85) {
        // 10%: 일정 참가
        const sid = randomScheduleId();
        const scid = getScheduleClub(sid);
        const res = http.patch(`${BASE_URL}/api/v1/clubs/${scid}/schedules/${sid}/users`, null, {
            headers: hdrs, tags: { name: 'soak_schedule_participate' },
        });
        schedulePartDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.93) {
        // 8%: 참가 취소
        const userSchedules = getUserSchedules(user.userId);
        let sid, scid;
        if (userSchedules.length > 0) {
            sid = userSchedules[Math.floor(Math.random() * userSchedules.length)];
            scid = getScheduleClub(sid);
        } else {
            sid = randomScheduleId();
            scid = getScheduleClub(sid);
        }
        const res = http.del(`${BASE_URL}/api/v1/clubs/${scid}/schedules/${sid}/users`, null, {
            headers: hdrs, tags: { name: 'soak_schedule_cancel' },
        });
        scheduleCancelDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else {
        // 7%: 클럽 가입 → 탈퇴
        const clubId = randomClubId();
        const joinRes = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/join`, null, {
            headers: hdrs, tags: { name: 'soak_club_join' },
        });
        clubJoinDur.add(joinRes.timings.duration);
        ok = isSuccess(joinRes.status);
        if (joinRes.status === 200) {
            sleep(0.1);
            const leaveRes = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, {
                headers: hdrs, tags: { name: 'soak_club_leave' },
            });
            clubLeaveDur.add(leaveRes.timings.duration);
        }
    }

    phase10Success.add(ok);
    if (!ok) totalErrors.add(1);
    sleep(0.05 + Math.random() * 0.15);
}

// ============================================
// default function (fallback)
// ============================================
export default function () {
    warmup();
}
