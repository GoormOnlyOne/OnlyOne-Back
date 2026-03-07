# 피드 도메인 성능 최적화 보고서

## 1. 개요

| 항목 | 내용 |
|------|------|
| 대상 도메인 | 피드 (개인 피드, 인기 피드, 클럽 피드, 상세, 댓글, 좋아요) |
| 테스트 환경 | AWS EC2 c5.xlarge (4 vCPU, 8GB) × 3대 |
| DB 규모 | Feed 10.6M, Club 69K, User 100K |
| JVM 설정 | ZGC, -Xmx4g |
| HikariCP | max 300 |
| 테스트 도구 | k6 (10 Phase, max 1,300 VUs, 19분 50초) |

## 2. 최적화 내용

### 2.1 복합 인덱스 추가

| 인덱스 | 컬럼 | 용도 |
|--------|------|------|
| `idx_feed_club_feedid` | `(club_id, feed_id)` | 커서 기반 개인 피드 쿼리 |
| `idx_feed_club_popularity` | `(club_id, popularity_score)` | 인기 피드 사전계산 스코어 정렬 |

기존 인덱스 `idx_feed_club_deleted_created (club_id, deleted, created_at)`는 `ORDER BY created_at DESC`에 사용되었으나, 커서 기반으로 전환하면서 `feed_id` 기반 인덱스 추가.

### 2.2 캐시 레이어 개선

**문제**: pass1 캐시 키와 result 캐시 키가 동일한 포맷(`pf:{userId}:{page}:{size}`)으로 충돌. pass1 Redis TTL 30초, result in-memory TTL 10초인데 같은 키라서 pass1 캐시가 독립적으로 히트되지 못함.

**수정**:
- pass1 키를 `pf:p1:{userId}:{size}`로 분리
- result 키를 `pf:{userId}:first:{size}`로 변경
- 첫 페이지(cursor=null, page=0)만 집중 캐싱 — 커서 페이지는 인덱스 스캔으로 충분히 빠름
- 인기 피드도 동일한 키 분리 적용

### 2.3 인기 피드 스코어 사전계산

**문제**: 매 쿼리마다 10.6M 행에 대해 실시간 스코어 계산
```sql
ORDER BY LN(GREATEST(like_count + comment_count*2, 1))
         - (TIMESTAMPDIFF(SECOND, created_at, NOW()) / 43200.0) DESC
```
→ 인덱스 활용 불가, filesort 강제 발생

**수정**:
- `popularity_score DOUBLE` 컬럼 추가 (Feed 엔티티)
- `FeedPopularityScheduler` — 5분 주기, 50,000건 배치 UPDATE
- `FeedPopularityBatchService` — 트랜잭션 분리 (self-invocation 프록시 문제 방지)
- 쿼리가 `ORDER BY popularity_score DESC`로 변경 → `idx_feed_club_popularity` 인덱스 활용
- 대상: 7일 이내 피드 (~57만 건)

### 2.4 커서 기반 페이지네이션

**문제**: `OFFSET 1000`은 1,000행을 스킵해야 하므로 깊은 페이지일수록 느림

**수정**:
- `GET /api/v1/feeds?cursor={lastFeedId}&limit=20` 파라미터 추가
- 쿼리: `WHERE feed_id < :cursor ORDER BY feed_id DESC LIMIT 20`
- `feed_id`는 auto_increment이므로 `created_at DESC`와 동일한 정렬 보장
- 인덱스 `(club_id, feed_id)`에서 즉시 시작점 탐색, 일정한 성능
- IN절 분할 청크도 커서 버전 구현 (`findFeedIdsByClubIdsCursorChunked`)

### 2.5 시드 데이터 정합성 수정

**문제**: Feed가 참조하는 club_id 중 19,149개가 Club 테이블에 부재
→ `FetchNotFoundException: Entity 'Club' with identifier value '47327' does not exist`
→ 개인 피드 API 500 에러, Phase 5 성공률 0%

**원인**: 시드 데이터 다중 실행으로 Club auto_increment 갭 발생. Feed의 `club_id = @min_club + (n % TOTAL_CLUBS)` 공식이 실제 존재하지 않는 ID를 참조.

**수정**: 빠진 19,149개 Club을 INSERT하여 FK 정합성 복구. orphan feed 0건 확인.

## 3. 테스트 시나리오

| Phase | VUs | 시간 | 시나리오 |
|-------|-----|------|----------|
| 1. Warmup | 50 | 30s | 전체 API 워밍업 |
| 2. Baseline | 300 | 2m | 혼합 부하 기준선 |
| 3. ClubList | 350 | 2m | 클럽 피드 목록 집중 |
| 4. Detail | 500 | 2m | 피드 상세 조회 집중 |
| 5. Personal | 500 | 2m | 개인/인기 피드 집중 (핵심 병목) |
| 6. Comments | 350 | 1.5m | 댓글 목록 집중 |
| 7. Like | 700 | 1.5m | 좋아요 토글 집중 |
| 8. WriteMix | 350 | 2m | 피드/댓글 생성 혼합 |
| 9. Extreme | 1,000 | 2m | 전체 API 극한 부하 |
| 10. Soak | 300 | 3m | 장기 안정성 |

## 4. 테스트 결과 비교

### 4.1 API 응답 시간 (p95)

| API | Threshold | 최적화 전 | 최적화 후 | 변화 |
|-----|-----------|-----------|-----------|------|
| 피드 상세 | 1,000ms | 1,395ms | **398ms** | **71% 개선, PASS** |
| 댓글 목록 | 1,000ms | 614ms | **15ms** | **97% 개선, PASS** |
| 좋아요 토글 | 200ms | 190ms | **247ms** | threshold 초과 |
| 클럽피드 목록 | 500ms | 440ms | **795ms** | threshold 초과 |
| 개인 피드 | 1,000ms | 1,674ms | **2,420ms** | 성공률 0→100%, 속도 미달 |
| 인기 피드 | 1,000ms | 1,604ms | **2,110ms** | 속도 미달 |
| 피드 생성 | 500ms | - | **1,050ms** | threshold 초과 |
| 댓글 생성 | 500ms | - | **5,110ms** | threshold 초과 |
| 리피드 | 500ms | - | **6ms** | **PASS** |

### 4.2 Phase별 성공률

| Phase | 최적화 전 | 1차 (정합성 수정 전) | **2차 (최종)** |
|-------|-----------|---------------------|---------------|
| Baseline (300 VUs) | 100% | 66.33% | **100%** |
| ClubList (350 VUs) | 100% | 100% | **100%** |
| Detail (500 VUs) | 100% | 100% | **100%** |
| Personal (500 VUs) | 0% (에러) | 0% (Club 부재) | **100%** |
| Comments (350 VUs) | 100% | 100% | **100%** |
| Like (700 VUs) | 100% | 99.99% | **100%** |
| WriteMix (350 VUs) | - | 97.46% | **97.34%** |
| Extreme (1,000 VUs) | - | 71.55% | **99.16%** |
| Soak (300 VUs) | - | 71.79% | **99.93%** |

### 4.3 전체 지표

| 지표 | 1차 | **2차 (최종)** |
|------|-----|---------------|
| 총 iterations | 1,358,277 | **1,458,284** |
| 총 에러 | 122,456 | **2,426** |
| 에러율 | 9.02% | **0.17%** |
| Thresholds | 8 PASS / 11 FAIL | **12 PASS / 7 FAIL** |
| 테스트 시간 | 19m 50s | 19m 50s |

### 4.4 서버 리소스 (최대 부하 시)

| 리소스 | G1GC (최적화 전) | ZGC (최적화 후) |
|--------|-----------------|----------------|
| CPU | **100%** (포화) | **3~30%** |
| JVM Heap | 3.5GB / 4GB | 1.4~3.7GB / 4GB |
| GC Pause | 수백ms | **<1ms** (ZGC) |
| HikariCP active | 높음 | **2~23** |
| HikariCP pending | **병목 발생** | **0** |
| Memory | 7.7GB 근접 | 1.1~2.1GB |

## 5. Threshold 결과 상세

### PASS (12개)
| Metric | 기준 | 결과 |
|--------|------|------|
| feed_detail_duration | p95 < 1,000ms | **398ms** |
| feed_comment_list_duration | p95 < 1,000ms | **15ms** |
| feed_refeed_duration | p95 < 500ms | **6ms** |
| feed_phase2_success | rate > 0.98 | **100%** |
| feed_phase3_success | rate > 0.98 | **100%** |
| feed_phase4_success | rate > 0.95 | **100%** |
| feed_phase5_success | rate > 0.95 | **100%** |
| feed_phase6_success | rate > 0.95 | **100%** |
| feed_phase7_success | rate > 0.95 | **100%** |
| feed_phase8_success | rate > 0.90 | **97.34%** |
| feed_phase9_success | rate > 0.85 | **99.16%** |
| feed_phase10_success | rate > 0.95 | **99.93%** |

### FAIL (7개)
| Metric | 기준 | 실측 | 원인 |
|--------|------|------|------|
| feed_personal_duration | p95 < 1,000ms | **2,420ms** | IN절 20+클럽 병목 |
| feed_popular_duration | p95 < 1,000ms | **2,110ms** | IN절 + 정렬 병목 |
| feed_club_list_duration | p95 < 500ms | **795ms** | 대량 부하 시 지연 |
| feed_like_duration | p95 < 200ms | **247ms** | Redis Lua + DB 동기화 |
| feed_create_duration | p95 < 500ms | **1,050ms** | 쓰기 트랜잭션 경합 |
| feed_comment_create_duration | p95 < 500ms | **5,110ms** | 댓글 + count 업데이트 경합 |
| http_req_failed | rate < 5% | - | 일부 에러 누적 |

## 6. 남은 병목 및 추가 최적화 방안

### 6.1 개인/인기 피드 IN절 병목 (p95 2s+)

**현재 쿼리 구조**:
```sql
SELECT feed_id, like_count, comment_count
FROM feed
WHERE club_id IN (1, 2, 3, ..., 20)  -- 유저가 속한 20+개 클럽
  AND deleted = false
ORDER BY feed_id DESC
LIMIT 20
```

**문제**: IN절에 20+개 값이 들어가면 MySQL이 각 club_id별 인덱스 범위를 merge sort해야 하므로 효율 저하.

**최적화 방안 A — UNION ALL**:
```sql
(SELECT ... FROM feed WHERE club_id = 1 ORDER BY feed_id DESC LIMIT 20)
UNION ALL
(SELECT ... FROM feed WHERE club_id = 2 ORDER BY feed_id DESC LIMIT 20)
...
ORDER BY feed_id DESC LIMIT 20
```
각 서브쿼리가 `(club_id, feed_id)` 인덱스를 완벽 활용. 예상 개선: p95 500ms 이하.

**최적화 방안 B — Redis Sorted Set 타임라인**:
- 피드 생성 시 해당 클럽 멤버의 Redis ZSET에 push (fan-out on write)
- 조회 시 `ZRANGEBYSCORE`로 O(log n) 조회, DB 우회
- 예상 개선: p95 50ms 이하

### 6.2 댓글 생성 경합 (p95 5.1s)

- 댓글 INSERT + `feed.comment_count` 원자적 UPDATE가 동일 트랜잭션
- 인기 피드에 댓글 집중 시 row lock 경합
- 방안: count 업데이트를 비동기 이벤트로 분리, 또는 Redis counter + 주기 동기화

## 7. 결론

### 달성 성과
- **피드 상세 p95**: 1,395ms → **398ms** (71% 개선)
- **댓글 목록 p95**: 614ms → **15ms** (97% 개선)
- **전체 에러율**: 9.02% → **0.17%** (98% 감소)
- **Phase 성공률**: Personal 0% → **100%**, Extreme 71% → **99.16%**
- **CPU 사용률**: 100% → **3~30%** (ZGC 전환 + 쿼리 최적화)
- **HikariCP 병목**: pending 발생 → **0** (완전 해소)
- **Threshold PASS율**: 42% (8/19) → **63% (12/19)**

### 미달 항목
- 개인/인기 피드 p95가 2s+ — IN절 근본 구조 개선 필요 (UNION ALL 또는 Redis ZSET)
- 댓글 생성 p95 5.1s — row lock 경합, 비동기 count 업데이트 필요

### 적용 기술 스택
- **JVM**: ZGC (G1GC 대비 CPU 70%p 절감)
- **DB 인덱스**: 복합 인덱스 2종 추가
- **캐시**: Redis pass1 + in-memory result 키 분리
- **사전계산**: popularity_score 5분 주기 배치 갱신
- **페이징**: 커서 기반 pagination (OFFSET 제거)
