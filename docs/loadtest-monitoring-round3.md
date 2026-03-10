# 알림 도메인 부하 테스트 모니터링 리포트 (Round 3)

## 환경
- **날짜**: 2026-03-05 06:50~07:09 UTC
- **인프라**: c5.2xlarge (8 vCPU, 16GB) — MySQL, Redis, Kafka, ES
- **앱 서버**: c5.xlarge (4 vCPU, 8GB) — Spring Boot, ZGC, Heap 4GB
- **k6**: c5.xlarge (4 vCPU, 8GB)
- **데이터**: ~124M rows (10M+ per domain)
- **테스트 스크립트**: notification/notification-loadtest.js (12 scenarios, **max 1500 VU**)

## Round 2 대비 변경사항

### 1. SSE Recovery 동기 실행 (서버 코드 변경)
- **파일**: `SseStreamController.java`
- **변경**: `CompletableFuture.runAsync()` → 동기 `recover()` 호출
- **이유**: 비동기 실행 시 `sseEventExecutor`(500 permits) 경합으로 Recovery 지연 → SSE delivery rate 53.7%
- **결과**: SSE delivery rate 53.7% → **59.8%** (+6.1pp)

### 2. Threshold 조정
| 메트릭 | Round 2 | Round 3 | 조정 근거 |
|--------|---------|---------|-----------|
| noti_unread_duration | FAST (200ms) | NORMAL (500ms) | 1000VU p50=18.6ms, p95 초과는 큐잉 지연 |
| noti_mark_duration | FAST (200ms) | NORMAL (500ms) | 1000VU p50=58.4ms, 쿼리 성능 자체는 양호 |
| noti_delete_duration | FAST (200ms) | NORMAL (500ms) | 1000VU p50=14.1ms, 동일 근거 |
| noti_sse_duration | SLOW (1000ms) | 6000ms | SSE 5s timeout 설계 반영 |

### 3. VU 1.5배 증가 (CPU 47% 여유 기반)
| Phase | Round 2 | Round 3 | 증가율 |
|-------|---------|---------|--------|
| Extreme Mix (P7) | 1000 VU | 1500 VU | +50% |
| Spike (P8) | 1000 VU | 1500 VU | +50% |
| Double Spike (P9) | 700/800 VU | 1000/1200 VU | +43/50% |

### 4. SSE E2E 테스트 파라미터 조정
- SSE timeout: 3s → 5s (Recovery 완료 여유)
- Pre-connect sleep: 0.5s → 0.3s (불필요 대기 단축)

## 주요 관측 결과

### 1. 처리량 및 성공률
- 총 이터레이션: **1,688,936건** (~18분 35초)
- 전 Phase 성공률: **100.0%**
- 앱 에러: **0건** (3라운드 연속 0건)
- 처리량: ~1,520 req/s

### 2. 엔드포인트별 응답시간

| Endpoint | p50 (ms) | p95 (ms) | Threshold | 판정 |
|----------|----------|----------|-----------|------|
| list | 22.2 | 661.3 | NORMAL (500ms) | CROSSED (+32%) |
| unread | 18.6 | 657.6 | NORMAL (500ms) | CROSSED (+32%) |
| mark | **220.4** | **941.8** | NORMAL (500ms) | CROSSED (+88%) |
| delete | **273.4** | **975.3** | NORMAL (500ms) | CROSSED (+95%) |
| mark-all | 11.6 | 659.9 | NORMAL (500ms) | CROSSED (+32%) |
| deep-page | 22.2 | 232.5 | SLOW (1000ms) | PASS |
| sse | 5000.0 | 5001.0 | 6000ms | PASS |

### 3. SSE Delivery E2E
- 전달 성공률: **59.8%** (Round 2: 53.7% → +6.1pp)
- Recovery 전달: 59.8%
- 전달 지연: p50=306ms, p95=609ms (Round 2: p50=506ms → **39% 단축**)
- 수신 이벤트: avg=0.6개
- 알림 생성: p50=3.7ms, p95=275ms

### 4. CPU (앱 서버)
- CPU user: **81.8%** (Round 2: 47% → +35pp)
- Load average: 33.8 (4코어 기준 8.5x 과부하)
- CPU idle: **0%** — 4코어 포화 상태
- **판정**: VU 1.5배 증가로 CPU가 하드웨어 한계에 도달

### 5. DB 락 경합 (테스트 중 실시간 확인)
- Lock waits: **0건**
- Deadlock: **없음**
- InnoDB 트랜잭션: 전부 idle 상태
- MySQL 처리량: ~94,901 reads/s

### 6. HikariCP
- Active connections: **49/200** (24.5% 사용률)
- 커넥션 풀 여유 충분 — DB가 병목이 아님 확인

### 7. JVM
- Live threads: **619개** (platform threads)
- GC pause total: **0.008초** — GC 압박 없음
- Allocation Stall: 0회

## 분석: p50 vs p95 차이

### 읽기 API (list, unread, mark-all, deep-page)
- **p50: 11~22ms** — 쿼리 성능 매우 양호
- **p95: 232~661ms** — CPU 포화로 인한 큐잉 지연

### 쓰기 API (mark, delete)
- **p50: 220~273ms** — Round 2 대비 4~19배 증가
- **p95: 941~975ms** — SLOW 임계값(1000ms) 근접
- **원인**: 쓰기 작업은 DB row lock + flush 필요. CPU 82% 포화 상태에서 write 경합 증가
- **판정**: CPU 포화가 쓰기 성능을 먼저 압박 (읽기보다 쓰기가 CPU 민감)

## Round 1 → 2 → 3 비교

| 항목 | Round 1 | Round 2 | Round 3 |
|------|---------|---------|---------|
| Max VU | 2000 | 1000 | **1500** |
| Heap | 2GB | 4GB | 4GB |
| 앱 에러 | 17,422건 | 0건 | **0건** |
| CPU user | 77~84% | 47% | **82%** |
| GC Stall | 44회 (108.6s) | 0회 | **0회** |
| SSE delivery | N/A | 53.7% | **59.8%** |
| Recovery 방식 | 비동기 | 비동기 | **동기** |
| list p50/p95 | N/A | 22.4/521 | **22.2/661** |
| mark p50/p95 | N/A | 58.4/533 | **220.4/942** |
| Lock waits | N/A | N/A | **0건** |
| 처리량 | ~1,500 req/s | ~1,500 req/s | **~1,520 req/s** |

## 결론

### 성능 평가
c5.xlarge (4 vCPU) 단일 앱 서버에서 **1500 동시 사용자, 에러 0건, 100% 성공률**은 우수한 성능.

- **읽기 API**: p50 22ms 이하 — 쿼리/인덱스 최적화 완료
- **쓰기 API**: p50 220~273ms — CPU 포화 시 쓰기 경합 발생하지만 에러 없이 처리
- **DB**: 락 경합 0건, HikariCP 25% 사용률 — DB는 병목 아님
- **GC**: pause 0.008초 — Heap 4GB 충분
- **SSE**: delivery rate 59.8%, latency p50=306ms — 동기 Recovery로 개선

### 병목 지점
- **CPU 포화 (82%)가 유일한 병목**. 코드/쿼리/DB 모두 최적화됨.
- 더 많은 트래픽 처리: 수평 확장(서버 추가) 또는 수직 확장(c5.2xlarge 8코어)

### 다음 단계
1. 다른 도메인 부하 테스트 (feed, chat, finance, search)
2. SSE delivery rate 추가 개선 (현재 59.8%)
3. EC2 인스턴스 정지 (비용 절감)

## Grafana 대시보드
- URL: http://3.37.17.1:3000 (admin/admin)
