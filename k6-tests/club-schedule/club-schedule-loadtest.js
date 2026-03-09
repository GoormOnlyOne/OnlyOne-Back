// =============================================================
// 클럽/스케줄 도메인 통합 부하 테스트
// =============================================================
// 실행 (로컬):
//   MSYS_NO_PATHCONV=1 docker run --rm -i \
//     -v "$(pwd)/k6-tests:/scripts" grafana/k6 run \
//     -e BASE_URL=http://host.docker.internal:8080 \
//     /scripts/club-schedule/club-schedule-loadtest.js
//
// EC2 (VU_SCALE=5로 원래 부하 수준):
//   docker run ... -e VU_SCALE=5 -e USER_COUNT=100000 ...
//
// Phase 구성 (~18분, 기본 최대 150 VUs / VU_SCALE=5 시 750):
// ┌────────┬──────────────────────────────────┬──────┬───────┐
// │ Phase  │ 시나리오                          │ VU   │ 시간  │
// ├────────┼──────────────────────────────────┼──────┼───────┤
// │ 1      │ Warmup                           │ 10   │ 30s   │
// │ 2      │ Baseline — 전 API 혼합            │ 50   │ 2m    │
// │ 3      │ 일정 생성 + 참가 동시성            │ 80   │ 2m    │
// │ 4      │ 클럽 가입/탈퇴 경합               │ 60   │ 2m    │
// │ 5      │ 일정 목록 + 상세 조회              │ 80   │ 2m    │
// │ 6      │ 일정 참가/취소 토글               │ 60   │ 1.5m  │
// │ 7      │ 클럽 목록 + 멤버 조회              │ 60   │ 1.5m  │
// │ 8      │ Spike 전 API 혼합                 │ 150  │ 1.5m  │
// │ 9      │ Double Spike                     │ 120  │ 2m    │
// │ 10     │ Soak                             │ 50   │ 3m    │
// │ 11     │ Cooldown                         │ 5    │ 30s   │
// └────────┴──────────────────────────────────┴──────┴───────┘
// =============================================================

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import {
    generateJWT, headers, BASE_URL, makeUser,
    getUserClubs, getRandomUserClub,
    getScheduleClub, getRandomClubSchedule,
    vu, dur, startAfter, TOTAL_USERS,
    MIN_CLUB, MIN_SCHEDULE, TOTAL_CLUBS, TOTAL_SCHEDULES,
} from '../lib/common.js';
import { THRESHOLDS } from '../lib/bottleneck.js';

// ============================================
// 테스트 데이터
// ============================================
const USER_COUNT = parseInt(__ENV.USER_COUNT) || TOTAL_USERS;

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
// Phase 타이밍 (초 단위, dur()/startAfter()로 스케일링)
// ============================================
const CP1 = 30, CP2 = 120, CP3 = 120, CP4 = 120, CP5 = 120, CP6 = 90;
const CP7 = 90, CP8 = 90, CP9 = 120, CP10 = 180, CP11 = 30;

// ============================================
// 시나리오 설정
// ============================================
export const options = {
    scenarios: {
        // Phase 1: Warmup
        warmup: {
            executor: 'constant-vus',
            vus: vu(10),
            duration: dur(CP1),
            exec: 'warmup',
            tags: { phase: '1_warmup' },
        },

        // Phase 2: Baseline — 전 API 혼합 (bottleneck 임계값 포함)
        baseline: {
            executor: 'constant-vus',
            vus: vu(50),
            duration: dur(CP2),
            startTime: startAfter([CP1]),
            exec: 'baseline',
            tags: { phase: '2_baseline' },
        },

        // Phase 3: 일정 생성 + 참가 동시성
        schedule_concurrency: {
            executor: 'ramping-vus',
            startVUs: vu(3),
            stages: [
                { duration: dur(20), target: vu(80) },
                { duration: dur(80), target: vu(80) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([CP1, CP2]),
            exec: 'scheduleConcurrency',
            tags: { phase: '3_schedule_concurrency' },
        },

        // Phase 4: 클럽 가입/탈퇴 경합
        club_join_leave: {
            executor: 'ramping-vus',
            startVUs: vu(3),
            stages: [
                { duration: dur(20), target: vu(60) },
                { duration: dur(80), target: vu(60) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([CP1, CP2, CP3]),
            exec: 'clubJoinLeave',
            tags: { phase: '4_club_join_leave' },
        },

        // Phase 5: 일정 목록 + 상세 조회
        schedule_read: {
            executor: 'ramping-vus',
            startVUs: vu(3),
            stages: [
                { duration: dur(20), target: vu(80) },
                { duration: dur(80), target: vu(80) },
                { duration: dur(20), target: 0 },
            ],
            startTime: startAfter([CP1, CP2, CP3, CP4]),
            exec: 'scheduleRead',
            tags: { phase: '5_schedule_read' },
        },

        // Phase 6: 일정 참가/취소 토글
        schedule_rejoin: {
            executor: 'ramping-vus',
            startVUs: vu(3),
            stages: [
                { duration: dur(15), target: vu(60) },
                { duration: dur(60), target: vu(60) },
                { duration: dur(15), target: 0 },
            ],
            startTime: startAfter([CP1, CP2, CP3, CP4, CP5]),
            exec: 'scheduleRejoin',
            tags: { phase: '6_schedule_rejoin' },
        },

        // Phase 7: 클럽 목록 + 멤버 조회
        club_read: {
            executor: 'ramping-vus',
            startVUs: vu(3),
            stages: [
                { duration: dur(15), target: vu(60) },
                { duration: dur(60), target: vu(60) },
                { duration: dur(15), target: 0 },
            ],
            startTime: startAfter([CP1, CP2, CP3, CP4, CP5, CP6]),
            exec: 'clubRead',
            tags: { phase: '7_club_read' },
        },

        // Phase 8: Spike 전 API 혼합
        spike: {
            executor: 'ramping-vus',
            startVUs: vu(3),
            stages: [
                { duration: dur(10), target: vu(150) },
                { duration: dur(50), target: vu(150) },
                { duration: dur(20), target: vu(3) },
                { duration: dur(10), target: vu(3) },
            ],
            startTime: startAfter([CP1, CP2, CP3, CP4, CP5, CP6, CP7]),
            exec: 'spikeTest',
            tags: { phase: '8_spike' },
        },

        // Phase 9: Double Spike — 회복 후 재폭증
        double_spike: {
            executor: 'ramping-vus',
            startVUs: vu(3),
            stages: [
                { duration: dur(10), target: vu(100) },
                { duration: dur(20), target: vu(100) },
                { duration: dur(10), target: vu(3) },
                { duration: dur(15), target: vu(3) },
                { duration: dur(10), target: vu(120) },
                { duration: dur(25), target: vu(120) },
                { duration: dur(15), target: vu(3) },
                { duration: dur(15), target: vu(3) },
            ],
            startTime: startAfter([CP1, CP2, CP3, CP4, CP5, CP6, CP7, CP8]),
            exec: 'doubleSpikeTest',
            tags: { phase: '9_double_spike' },
        },

        // Phase 10: Soak — 중간 부하 장시간
        soak: {
            executor: 'constant-vus',
            vus: vu(50),
            duration: dur(CP10),
            startTime: startAfter([CP1, CP2, CP3, CP4, CP5, CP6, CP7, CP8, CP9]),
            exec: 'soakTest',
            tags: { phase: '10_soak' },
        },

        // Phase 11: Cooldown
        cooldown: {
            executor: 'constant-vus',
            vus: vu(5),
            duration: dur(CP11),
            startTime: startAfter([CP1, CP2, CP3, CP4, CP5, CP6, CP7, CP8, CP9, CP10]),
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
        'schedule_create_duration':       [`p(95)<${THRESHOLDS.SLOW}`],      // 1000ms
        'schedule_participate_duration':  [`p(95)<${THRESHOLDS.NORMAL}`],    // 500ms
        'schedule_cancel_duration':       [`p(95)<${THRESHOLDS.NORMAL}`],    // 500ms

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

/**
 * 클럽의 LEADER userId 반환 (seed 공식: Batch 1에서 가장 먼저 INSERT된 유저)
 * clubId = MIN_CLUB + c → leaderUserId = (c == 0 ? TOTAL_CLUBS : c)
 */
function getClubLeader(clubId) {
    const c = clubId - MIN_CLUB;
    return c === 0 ? TOTAL_CLUBS : c;
}

/** LEADER 유저로 JWT 생성 (일정 생성용) */
function leaderOf(clubId) {
    return makeUser(getClubLeader(clubId));
}

/** 쓰기 작업용: 4xx 비즈니스 에러(403 권한없음, 409 중복)는 서버 실패가 아님 */
function isWriteOk(status) {
    return status >= 200 && status < 500;
}

function isServerError(status) {
    return status >= 500;
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
    const scheduleId = getRandomClubSchedule(clubId);

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
    const schedDetailRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}/schedules/${scheduleId}`, {
        headers: hdrs, tags: { name: 'bl_schedule_detail' },
    });
    scheduleDetailDur.add(schedDetailRes.timings.duration);
    phase2Success.add(isSuccess(schedDetailRes.status));
    if (!isSuccess(schedDetailRes.status)) totalErrors.add(1);
    sleep(0.2);

    // ── 일정 참가 (20%) — PATCH .../users ──
    if (Math.random() < 0.2) {
        const partRes = http.patch(`${BASE_URL}/api/v1/clubs/${clubId}/schedules/${scheduleId}/users`, null, {
            headers: hdrs, tags: { name: 'bl_schedule_participate' },
        });
        schedulePartDur.add(partRes.timings.duration);
        phase2Success.add(isWriteOk(partRes.status));
        if (isServerError(partRes.status)) totalErrors.add(1);
    }
    sleep(0.2);

    // ── 클럽 가입 (5%) ──
    if (Math.random() < 0.05) {
        const joinClubId = randomClubId();
        const joinRes = http.post(`${BASE_URL}/api/v1/clubs/${joinClubId}/join`, null, {
            headers: hdrs, tags: { name: 'bl_club_join' },
        });
        clubJoinDur.add(joinRes.timings.duration);
        phase2Success.add(isWriteOk(joinRes.status));
        if (isServerError(joinRes.status)) totalErrors.add(1);

        // 가입 성공 시 바로 탈퇴
        if (joinRes.status === 200) {
            sleep(0.1);
            const leaveRes = http.del(`${BASE_URL}/api/v1/clubs/${joinClubId}/leave`, null, {
                headers: hdrs, tags: { name: 'bl_club_leave' },
            });
            clubLeaveDur.add(leaveRes.timings.duration);
            phase2Success.add(isWriteOk(leaveRes.status));
        }
    }

    // ── 일정 생성 (3%) — LEADER 유저로 전환하여 권한 보장 ──
    if (Math.random() < 0.03) {
        const leader = leaderOf(clubId);
        const leaderToken = generateJWT(leader);
        const leaderHdrs = headers(leaderToken);
        const createRes = http.post(
            `${BASE_URL}/api/v1/clubs/${clubId}/schedules`,
            JSON.stringify({
                name: `k6bl${Date.now() % 100000}`,
                location: '테스트 장소',
                cost: 5000,
                userLimit: 10,
                scheduleTime: futureDate(),
            }),
            { headers: leaderHdrs, tags: { name: 'bl_schedule_create' } }
        );
        scheduleCreateDur.add(createRes.timings.duration);
        phase2Success.add(isWriteOk(createRes.status));
        if (isServerError(createRes.status)) totalErrors.add(1);
    }

    sleep(0.3);
}

// ============================================
// Phase 3: 일정 생성 + 참가 동시성 — 500 VUs
// ============================================
export function scheduleConcurrency() {
    const user = vuUser(__VU);
    const clubId = getRandomUserClub(user.userId);
    const roll = Math.random();

    if (roll < 0.35) {
        // 35%: 일정 생성 — LEADER 유저로 전환하여 권한 보장
        const leader = leaderOf(clubId);
        const leaderToken = generateJWT(leader);
        const leaderHdrs = headers(leaderToken);

        const createRes = http.post(
            `${BASE_URL}/api/v1/clubs/${clubId}/schedules`,
            JSON.stringify({
                name: `k6cc${__VU}_${__ITER}`,
                location: '동시성 테스트 장소',
                cost: 3000,
                userLimit: 20,
                scheduleTime: futureDate(),
            }),
            { headers: leaderHdrs, tags: { name: 'p3_schedule_create' } }
        );
        scheduleCreateDur.add(createRes.timings.duration);
        phase3Success.add(isWriteOk(createRes.status));
        if (isServerError(createRes.status)) totalErrors.add(1);

        // 생성된 일정에 바로 참가 시도 (원래 유저로)
        if (createRes.status === 200 || createRes.status === 201) {
            try {
                const body = JSON.parse(createRes.body);
                const data = body.data || body;
                const newScheduleId = data.scheduleId || data.id;
                if (newScheduleId) {
                    sleep(0.05);
                    const token = generateJWT(user);
                    const hdrs = headers(token);
                    const partRes = http.patch(`${BASE_URL}/api/v1/clubs/${clubId}/schedules/${newScheduleId}/users`, null, {
                        headers: hdrs, tags: { name: 'p3_schedule_participate_new' },
                    });
                    schedulePartDur.add(partRes.timings.duration);
                    phase3Success.add(isWriteOk(partRes.status));
                }
            } catch (e) { /* ignore */ }
        }
    } else {
        // 65%: 기존 일정에 참가 (409 이미 참가중은 예상됨)
        const token = generateJWT(user);
        const hdrs = headers(token);
        const partClubId = getRandomUserClub(user.userId);
        const partScheduleId = getRandomClubSchedule(partClubId);
        const partRes = http.patch(`${BASE_URL}/api/v1/clubs/${partClubId}/schedules/${partScheduleId}/users`, null, {
            headers: hdrs, tags: { name: 'p3_schedule_participate' },
        });
        schedulePartDur.add(partRes.timings.duration);
        phase3Success.add(isWriteOk(partRes.status));
        if (isServerError(partRes.status)) totalErrors.add(1);
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
        // 50%: 가입 시도 (409 이미 가입은 예상됨)
        const joinRes = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/join`, null, {
            headers: hdrs, tags: { name: 'p4_club_join' },
        });
        clubJoinDur.add(joinRes.timings.duration);
        phase4Success.add(isWriteOk(joinRes.status));
        if (isServerError(joinRes.status)) totalErrors.add(1);

        // 가입 성공 시 짧은 대기 후 탈퇴 (경합 유발)
        if (joinRes.status === 200) {
            sleep(0.1 + Math.random() * 0.2);
            const leaveRes = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, {
                headers: hdrs, tags: { name: 'p4_club_leave_after_join' },
            });
            clubLeaveDur.add(leaveRes.timings.duration);
            phase4Success.add(isWriteOk(leaveRes.status));
        }
    } else if (roll < 0.80) {
        // 30%: 탈퇴 시도 (400 미가입은 예상됨)
        const leaveRes = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, {
            headers: hdrs, tags: { name: 'p4_club_leave' },
        });
        clubLeaveDur.add(leaveRes.timings.duration);
        phase4Success.add(isWriteOk(leaveRes.status));
        if (isServerError(leaveRes.status)) totalErrors.add(1);
    } else {
        // 20%: 클럽 상세 조회 (읽기와 쓰기 혼합)
        const detailRes = http.get(`${BASE_URL}/api/v1/clubs/${clubId}`, {
            headers: hdrs, tags: { name: 'p4_club_detail' },
        });
        clubDetailDur.add(detailRes.timings.duration);
        phase4Success.add(isSuccess(detailRes.status));
        if (isServerError(detailRes.status)) totalErrors.add(1);
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
// Phase 6: 일정 참가/취소 토글 — 경합 테스트
// ============================================
export function scheduleRejoin() {
    const user = vuUser(__VU);
    const token = generateJWT(user);
    const hdrs = headers(token);

    const clubId = getRandomUserClub(user.userId);
    const scheduleId = getRandomClubSchedule(clubId);

    // 참가/취소 토글: 결과와 무관하게 양쪽 모두 시도 (경합 유발 목적)
    // PATCH → 참가 시도 (이미 참가 시 409 — 예상됨)
    const partRes = http.patch(`${BASE_URL}/api/v1/clubs/${clubId}/schedules/${scheduleId}/users`, null, {
        headers: hdrs, tags: { name: 'p6_schedule_participate' },
    });
    schedulePartDur.add(partRes.timings.duration);
    phase6Success.add(isWriteOk(partRes.status));
    if (isServerError(partRes.status)) totalErrors.add(1);

    sleep(0.05 + Math.random() * 0.1);

    // DELETE → 취소 시도 (미참가 시 4xx — 예상됨)
    const cancelRes = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/schedules/${scheduleId}/users`, null, {
        headers: hdrs, tags: { name: 'p6_schedule_cancel' },
    });
    scheduleCancelDur.add(cancelRes.timings.duration);
    phase6Success.add(isWriteOk(cancelRes.status));
    if (isServerError(cancelRes.status)) totalErrors.add(1);

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
        ok = isWriteOk(res.status);
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
        const spClubId = getRandomUserClub(user.userId);
        const sid = getRandomClubSchedule(spClubId);
        const res = http.get(`${BASE_URL}/api/v1/clubs/${spClubId}/schedules/${sid}`, {
            headers: hdrs, tags: { name: 'sp_schedule_detail' },
        });
        scheduleDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (op === 'schedule_create') {
        const cid = getRandomUserClub(user.userId);
        const ldr = leaderOf(cid);
        const ldrHdrs = headers(generateJWT(ldr));
        const res = http.post(
            `${BASE_URL}/api/v1/clubs/${cid}/schedules`,
            JSON.stringify({
                name: `k6sp${Date.now() % 100000}`,
                location: 'spike 테스트',
                cost: 5000,
                userLimit: 15,
                scheduleTime: futureDate(),
            }),
            { headers: ldrHdrs, tags: { name: 'sp_schedule_create' } }
        );
        scheduleCreateDur.add(res.timings.duration);
        ok = isWriteOk(res.status);
    } else if (op === 'schedule_participate') {
        const spClubId = getRandomUserClub(user.userId);
        const sid = getRandomClubSchedule(spClubId);
        const res = http.patch(`${BASE_URL}/api/v1/clubs/${spClubId}/schedules/${sid}/users`, null, {
            headers: hdrs, tags: { name: 'sp_schedule_participate' },
        });
        schedulePartDur.add(res.timings.duration);
        ok = isWriteOk(res.status);
    } else if (op === 'schedule_cancel') {
        const spClubId = getRandomUserClub(user.userId);
        const sid = getRandomClubSchedule(spClubId);
        const res = http.del(`${BASE_URL}/api/v1/clubs/${spClubId}/schedules/${sid}/users`, null, {
            headers: hdrs, tags: { name: 'sp_schedule_cancel' },
        });
        scheduleCancelDur.add(res.timings.duration);
        ok = isWriteOk(res.status);
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
        const dsClubId = getRandomUserClub(user.userId);
        const sid = getRandomClubSchedule(dsClubId);
        const res = http.get(`${BASE_URL}/api/v1/clubs/${dsClubId}/schedules/${sid}`, {
            headers: hdrs, tags: { name: 'ds_schedule_detail' },
        });
        scheduleDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.75) {
        // 15%: 클럽 상세 + 일정 목록
        const cid = getRandomUserClub(user.userId);
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
        const dsClubId2 = getRandomUserClub(user.userId);
        const sid = getRandomClubSchedule(dsClubId2);
        const res = http.patch(`${BASE_URL}/api/v1/clubs/${dsClubId2}/schedules/${sid}/users`, null, {
            headers: hdrs, tags: { name: 'ds_schedule_participate' },
        });
        schedulePartDur.add(res.timings.duration);
        ok = isWriteOk(res.status);
    } else if (roll < 0.93) {
        // 8%: 클럽 가입
        const clubId = randomClubId();
        const res = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/join`, null, {
            headers: hdrs, tags: { name: 'ds_club_join' },
        });
        clubJoinDur.add(res.timings.duration);
        ok = isWriteOk(res.status);
        if (res.status === 200) {
            const leaveRes = http.del(`${BASE_URL}/api/v1/clubs/${clubId}/leave`, null, {
                headers: hdrs, tags: { name: 'ds_club_leave' },
            });
            clubLeaveDur.add(leaveRes.timings.duration);
        }
    } else {
        // 7%: 일정 생성 — LEADER 유저로 전환
        const cid = getRandomUserClub(user.userId);
        const ldr = leaderOf(cid);
        const ldrHdrs = headers(generateJWT(ldr));
        const res = http.post(
            `${BASE_URL}/api/v1/clubs/${cid}/schedules`,
            JSON.stringify({
                name: `k6ds${Date.now() % 100000}`,
                location: 'double spike',
                cost: 5000,
                userLimit: 10,
                scheduleTime: futureDate(),
            }),
            { headers: ldrHdrs, tags: { name: 'ds_schedule_create' } }
        );
        scheduleCreateDur.add(res.timings.duration);
        ok = isWriteOk(res.status);
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
        const res = http.get(`${BASE_URL}/api/v1/clubs/${getRandomUserClub(user.userId)}`, {
            headers: hdrs, tags: { name: 'soak_club_detail' },
        });
        clubDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.45) {
        // 20%: 일정 목록
        const res = http.get(`${BASE_URL}/api/v1/clubs/${getRandomUserClub(user.userId)}/schedules`, {
            headers: hdrs, tags: { name: 'soak_schedule_list' },
        });
        scheduleListDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.60) {
        // 15%: 일정 상세
        const soakClubId = getRandomUserClub(user.userId);
        const sid = getRandomClubSchedule(soakClubId);
        const res = http.get(`${BASE_URL}/api/v1/clubs/${soakClubId}/schedules/${sid}`, {
            headers: hdrs, tags: { name: 'soak_schedule_detail' },
        });
        scheduleDetailDur.add(res.timings.duration);
        ok = isSuccess(res.status);
    } else if (roll < 0.75) {
        // 15%: 클럽 상세 + 일정 목록
        const cid = getRandomUserClub(user.userId);
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
        const soakClubId2 = getRandomUserClub(user.userId);
        const sid = getRandomClubSchedule(soakClubId2);
        const res = http.patch(`${BASE_URL}/api/v1/clubs/${soakClubId2}/schedules/${sid}/users`, null, {
            headers: hdrs, tags: { name: 'soak_schedule_participate' },
        });
        schedulePartDur.add(res.timings.duration);
        ok = isWriteOk(res.status);
    } else if (roll < 0.93) {
        // 8%: 참가 취소
        const soakClubId3 = getRandomUserClub(user.userId);
        const sid = getRandomClubSchedule(soakClubId3);
        const res = http.del(`${BASE_URL}/api/v1/clubs/${soakClubId3}/schedules/${sid}/users`, null, {
            headers: hdrs, tags: { name: 'soak_schedule_cancel' },
        });
        scheduleCancelDur.add(res.timings.duration);
        ok = isWriteOk(res.status);
    } else {
        // 7%: 클럽 가입 → 탈퇴
        const clubId = randomClubId();
        const joinRes = http.post(`${BASE_URL}/api/v1/clubs/${clubId}/join`, null, {
            headers: hdrs, tags: { name: 'soak_club_join' },
        });
        clubJoinDur.add(joinRes.timings.duration);
        ok = isWriteOk(joinRes.status);
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

// ============================================
// handleSummary — 클럽/스케줄 부하 테스트 결과 리포트
// ============================================
export function handleSummary(data) {
    const line = '─'.repeat(60);

    let summary = `
╔════════════════════════════════════════════════════════════╗
║           클럽/스케줄 도메인 부하 테스트 결과                ║
╚════════════════════════════════════════════════════════════╝
`;

    const metrics = [
        ['클럽 상세',           'club_detail_duration'],
        ['클럽 가입',           'club_join_duration'],
        ['클럽 탈퇴',           'club_leave_duration'],
        ['일정 목록',           'schedule_list_duration'],
        ['일정 상세',           'schedule_detail_duration'],
        ['일정 생성',           'schedule_create_duration'],
        ['일정 참가',           'schedule_participate_duration'],
        ['일정 참가 취소',      'schedule_cancel_duration'],
    ];

    summary += `\n${line}\n`;
    summary += `${'API'.padEnd(22)} ${'p50'.padStart(8)} ${'p95'.padStart(8)} ${'p99'.padStart(8)} ${'max'.padStart(8)}  ${'avg'.padStart(8)}\n`;
    summary += `${line}\n`;

    for (const [label, key] of metrics) {
        const m = data.metrics[key];
        if (m && m.values) {
            const v = m.values;
            summary += `${label.padEnd(22)} ${fmt(v['p(50)'])} ${fmt(v['p(95)'])} ${fmt(v['p(99)'])} ${fmt(v['max'])}  ${fmt(v['avg'])}\n`;
        }
    }
    summary += `${line}\n`;

    // Phase별 성공률
    const phases = [
        ['Phase 2 Baseline',    'cs_phase2_success'],
        ['Phase 3 SchedConc',   'cs_phase3_success'],
        ['Phase 4 JoinLeave',   'cs_phase4_success'],
        ['Phase 5 SchedRead',   'cs_phase5_success'],
        ['Phase 6 Rejoin',      'cs_phase6_success'],
        ['Phase 7 ClubRead',    'cs_phase7_success'],
        ['Phase 8 Spike',       'cs_phase8_success'],
        ['Phase 9 DblSpike',    'cs_phase9_success'],
        ['Phase 10 Soak',       'cs_phase10_success'],
    ];

    summary += `\n${'Phase'.padEnd(22)} ${'성공률'.padStart(10)}\n`;
    summary += `${line}\n`;
    for (const [label, key] of phases) {
        const m = data.metrics[key];
        if (m && m.values) {
            const rate = (m.values['rate'] * 100).toFixed(2) + '%';
            summary += `${label.padEnd(22)} ${rate.padStart(10)}\n`;
        }
    }
    summary += `${line}\n`;

    // 에러 카운트
    const errMetric = data.metrics['cs_total_errors'];
    summary += `\n총 에러: ${errMetric ? errMetric.values.count : 0}\n`;

    // Thresholds PASS/FAIL
    let passCount = 0, failCount = 0;
    if (data.metrics) {
        for (const [, val] of Object.entries(data.metrics)) {
            if (val.thresholds) {
                for (const [, th] of Object.entries(val.thresholds)) {
                    if (th.ok) passCount++;
                    else failCount++;
                }
            }
        }
    }
    summary += `Thresholds: ${passCount} PASS / ${failCount} FAIL\n`;

    console.log(summary);

    return { 'stdout': summary };
}

function fmt(ms) {
    if (ms === undefined || ms === null) return 'N/A'.padStart(8);
    if (ms < 1000) return (ms.toFixed(0) + 'ms').padStart(8);
    return ((ms / 1000).toFixed(2) + 's').padStart(8);
}
