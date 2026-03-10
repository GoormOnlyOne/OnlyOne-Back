# Code Review Fixes — 2026-03-05

## Summary

This document lists all issues identified during the comprehensive code review and the fixes applied.

---

## 1. NotificationService CQRS Split (Critical)

**Problem:** `NotificationService` was a monolithic class handling both queries (list, unread count) and commands (create, markAsRead, delete, markAllAsRead) with `@Transactional` event publishing inside the same transaction boundary.

**Fix:**
- Split into `NotificationQueryService` (read-only queries) and `NotificationCommandService` (state mutations)
- `NotificationService` retained as a thin facade delegating to both, preserving backward compatibility
- `NotificationController` updated to inject `NotificationQueryService` and `NotificationCommandService` directly
- Test files split: `NotificationQueryServiceTest`, `NotificationCommandServiceTest`, and `NotificationServiceTest` (facade delegation tests)

**Files changed:**
- `NotificationService.java` — rewritten as facade
- `NotificationCommandService.java` — NEW
- `NotificationQueryService.java` — NEW
- `NotificationController.java` — uses Command/Query directly
- `NotificationServiceTest.java` — rewritten for facade
- `NotificationQueryServiceTest.java` — NEW
- `NotificationCommandServiceTest.java` — NEW

---

## 2. Event Publishing Inside @Transactional (Critical)

**Problem:** `createNotification()` published a Spring ApplicationEvent inside `@Transactional`. If the transaction rolled back, the event listener (`NotificationBatchProcessor`) would still have received the event and attempted SSE delivery for non-existent data.

**Fix:** Already mitigated — `NotificationBatchProcessor` already uses `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`, which means the event is only processed after successful commit. The event is published inside the transaction but only delivered after commit. No code change needed.

---

## 3. Pagination Bounds Validation (High)

**Problem:** `SearchController` and `NotificationController` accepted unbounded `page` and `size` parameters. Negative pages or excessively large sizes could cause unexpected behavior or resource exhaustion.

**Fix:**
- Added `@Validated` at class level on both controllers
- Added `@Min(0)` on all `page` parameters
- Added `@Min(1) @Max(100)` on `size` parameters for search endpoints
- Added `@Min(1) @Max(30)` on `size` parameter for notification endpoint

**Files changed:**
- `SearchController.java` — `@Validated`, `@Min`/`@Max` annotations
- `NotificationController.java` — `@Validated`, `@Min`/`@Max` annotations

---

## 4. DataIntegrityViolationException Handler (High)

**Problem:** `GlobalExceptionHandler` had no handler for `DataIntegrityViolationException`. Unique constraint violations or FK violations would fall through to the generic `Exception` handler, returning a 500 instead of a meaningful 409 response.

**Fix:** Added dedicated `@ExceptionHandler` for `DataIntegrityViolationException` returning HTTP 409 with code `DATA_INTEGRITY_VIOLATION`.

**File changed:**
- `GlobalExceptionHandler.java`

---

## 5. Redundant userId Field in Notification Entity (Critical)

**Problem:** `Notification` entity had both a `@ManyToOne User user` field (mapped to `user_id` column) AND a separate `@Column(name = "user_id") Long userId` field. This redundancy:
- Violates JPA mapping best practices (two fields for one column)
- Can cause confusion about which field to use
- The `userId` field was `insertable = false, updatable = false` making it read-only anyway

**Fix:** Removed the redundant `Long userId` field. Access user ID via `notification.getUser().getUserId()`.

**File changed:**
- `Notification.java`

---

## 6. Redundant Null Checks on RedisTemplate (Medium)

**Problem:** `PaymentService.confirm()` had `if (redisTemplate != null)` guards around Redis operations. Since `redisTemplate` is always injected by Spring (Redis is a mandatory dependency), these null checks were dead code that obscured the actual logic.

**Fix:** Removed the null checks, calling `redisTemplate` directly.

**File changed:**
- `PaymentService.java`

---

## 7. MySQL-Specific Native Queries → JPQL (High)

**Problem:** `NotificationRepositoryImpl` used native SQL queries (`createNativeQuery`) for operations that could be expressed in JPQL. This tied the implementation to MySQL and reduced portability.

**Fix:** Converted 3 queries from native SQL to JPQL:
- `markAsReadByIdAndUserId` — `UPDATE Notification n SET n.isRead = true WHERE ...`
- `deleteByIdAndUserId` — `DELETE FROM Notification n WHERE ...`
- `markDeliveredByIds` — `UPDATE Notification n SET n.sseSent = true WHERE n.id IN :ids`

**Not converted (MySQL-specific by necessity):**
- `markAllAsReadByUserId` — Uses `ON DUPLICATE KEY UPDATE` for the watermark upsert pattern. Added comment explaining the MySQL dependency.

**File changed:**
- `NotificationRepositoryImpl.java`

---

## 8. k6 Load Test: SSE Delivery E2E Verification (Enhancement)

**Problem:** The notification load test (`notification-loadtest.js`) Phase 6 (SSE Flood) only tested **connection success** — whether the HTTP request to `/api/v1/sse/subscribe` returns 200. It never verified that actual notification data was delivered through SSE.

**Fix:** Added Phase 6b `sse_delivery_e2e` that verifies end-to-end SSE data delivery:

**Flow:**
1. Create notification via `/test/notifications/create` (SSE not connected → `sse_sent=false`)
2. Wait 0.5s for transaction commit
3. Connect SSE via HTTP GET with 3s timeout
4. `MissedNotificationRecovery` triggers and delivers `sse_sent=false` notifications
5. Parse SSE response body for `notification` events
6. Verify the created `notificationId` appears in received events

**New metrics:**
| Metric | Description |
|--------|-------------|
| `sse_delivery_rate` | Created notifications actually delivered via SSE |
| `sse_delivery_latency` | Time from creation to SSE delivery (ms) |
| `sse_delivery_event_count` | Number of notification events per SSE connection |
| `sse_recovery_delivery_rate` | Recovery path delivery success rate |
| `sse_create_duration` | Notification creation API latency |
| `sse_create_success` | Notification creation success rate |

**New thresholds:**
- `sse_delivery_rate > 50%`
- `sse_delivery_latency p95 < 5000ms`
- `sse_recovery_delivery_rate > 50%`
- `sse_create_success > 95%`

**Report section added:**
```
│ SSE Delivery E2E (알림 생성 → SSE 실제 전달 검증)     │
│ 전달 성공률:    XX.X%                                 │
│ Recovery 전달:  XX.X%                                 │
│ 전달 지연:      p50=XXms    p95=XXms                  │
│ 수신 이벤트:    avg=X.X                               │
```

**File changed:**
- `k6-tests/notification/notification-loadtest.js`

**Note:** Uses standard k6 HTTP client (no xk6-sse extension required). Works via the Recovery path — notification is created while SSE is disconnected, then SSE connection triggers `MissedNotificationRecovery`.

---

## 9. Seed Data: Finance pending_out Inconsistency (Critical)

**Problem:** Seed SQL (`seed-all-domains.sql`, `seed-finance.sql`, `seed-all-domains-10x.sql`) created `user_settlement` with `HOLD_ACTIVE` status but set wallet `pending_out = 0` for participants (users 2-11).

In the application, `SettlementEventProcessor.batchCaptureHold()` requires `pending_out >= amount`. With `pending_out = 0`, all settlement captures fail silently — the UPDATE matches 0 rows, causing `WALLET_HOLD_CAPTURE_FAILED` errors.

**Root cause:** The seed bypasses the normal schedule join flow where `ScheduleCommandService.joinSchedule()` → `WalletHoldService.holdOrThrow()` → `holdBalanceIfEnough()` increments `pending_out`.

**Fix:**
- Users 2-11 (settlement participants): `posted_balance = pending_out = costPerUser × settlement_count`
  - 100x: `2,500,000` (100 × 25,000)
  - 10x: `250,000` (100 × 2,500)
- Other users: unchanged (`posted_balance = 100,000`, `pending_out = 0`)

**Files changed:**
- `k6-tests/seed/seed-all-domains.sql` — wallet section
- `k6-tests/seed/seed-all-domains-10x.sql` — wallet section
- `k6-tests/finance/seed-finance.sql` — wallet section

---

## 10. Seed Data: MIN_CLUB AUTO_INCREMENT Drift (High)

**Problem:** Seed SQL uses `@min_club = SELECT MIN(club_id) FROM club` for club references. After re-seeding (DELETE + INSERT), MySQL's `AUTO_INCREMENT` doesn't reset, causing `club_id` to start higher. k6 tests default `MIN_CLUB=1`, causing mismatches.

**Fix:**
- Added `resolve_db_offsets()` function to `run-loadtest.sh` that queries actual DB values
- Auto-detects: `MIN_CLUB`, `MIN_CHATROOM`, `MIN_SCHEDULE`, `TOTAL_*`, `USER_COUNT`, `SETTLEMENT_COUNT`
- Passes all values as environment variables to k6 (both Docker and local runs)
- Added verification/export section at end of `seed-all-domains.sql` showing exact env var values needed

**Files changed:**
- `k6-tests/run-loadtest.sh` — `resolve_db_offsets()`, `seed_data_10x()`, auto-detect before tests
- `k6-tests/seed/seed-all-domains.sql` — verification + env var guide section
- `k6-tests/lib/common.js` — updated documentation for env vars

---

## 11. k6 Load Test Infrastructure: Cleanup & Organization (Enhancement)

**Problem:** Orphaned files, stale logs, and PostgreSQL-only seeds cluttered the k6-tests directory.

**Fix:** Removed 7 irrelevant files:
- `k6-tests/seed/seed-postgres.sql` — PostgreSQL-only, project uses MySQL
- `k6-tests/common/rdbms-lock-comparison-test.js` — Old MySQL vs PostgreSQL benchmark
- `k6-tests/seed/verify-seed-mini.sql` — Mini-scale validation, not in production flow
- `k6-tests/seed/verify-mongo-mini.js` — Replaced by domain-specific seeders
- `k6-tests/seed/check-data.js` — One-off exploratory utility
- `k6-tests/results/monitor_*.log` — Stale monitoring logs

---

## Issues Identified But Not Fixed (Out of Scope)

These were noted during review but not addressed in this batch:

| # | Issue | Reason |
|---|-------|--------|
| 1 | Circuit breaker for Toss payment API | Requires Resilience4j dependency addition and architecture discussion |
| 2 | Redis Lua script not in DB transaction boundary (FeedLikeService) | By design — Lua provides atomic Redis ops, DB sync is eventual |
| 3 | No `@WebMvcTest` controller tests | Significant effort to mock security context; recommended for next sprint |
| 4 | `onlyone-common` has 0 tests | Utility classes are simple; add tests when logic grows |
| 5 | Club module only has 2 tests | Recommended to add more, but existing coverage is via integration tests |

---

## Build Verification

- `./gradlew compileJava` — **BUILD SUCCESSFUL**
- All notification service unit tests — **PASS** (25 tests, only 2 pre-existing TestContainers failures unrelated to changes)
