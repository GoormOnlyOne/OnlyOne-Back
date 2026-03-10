# 알림 도메인 부하 테스트 모니터링 리포트 (Round 1)

## 환경
- **날짜**: 2026-03-05 05:49~06:10 UTC
- **인프라**: c5.2xlarge (8 vCPU, 16GB) — MySQL, Redis, Kafka, ES
- **앱 서버**: c5.xlarge (4 vCPU, 8GB) — Spring Boot, ZGC, Heap 2GB
- **k6**: c5.xlarge (4 vCPU, 8GB)
- **데이터**: ~124M rows (10M+ per domain)
- **테스트 스크립트**: notification/notification-loadtest.js (12 scenarios, max 2000 VU)

## 주요 관측 결과

### 1. CPU (앱 서버)
- Baseline(300 VU): **77~84% user**
- Load average: 31~41 (4코어 기준 과부하, virtual threads 영향)
- idle: 2.6~21%

### 2. HikariCP
- Active connections: **46~62 / 200 max** (23~31% 사용률)
- 커넥션 풀 자체는 여유, DB 쿼리 처리 속도 양호

### 3. JVM Heap (ZGC, 2GB)
- Heap 사용률: 50~100% (2GB 한계 도달)
- **Allocation Stall GC**: 44회, 총 108.6초, max 3.4초
- **Allocation Rate GC**: 카운트 있으나 pause 0초 (정상)
- Proactive GC: 0회
- **결론**: 2GB 부족 → Round 2에서 4GB로 증설

### 4. 에러 분석
| 에러 유형 | 건수 | 원인 |
|-----------|------|------|
| `NoResourceFoundException: /test/notifications/create` | 14,778 | TestNotificationController가 ec2 프로필 미활성화 |
| `AsyncRequestNotUsableException` | 2,644 | SSE 클라이언트 측 연결 끊김 (정상 동작) |

### 5. MySQL Slow Queries
- 총 2,684건 — **전부 시드 프로시저 관련** (sync_feed_counts, notification INSERT 등)
- 앱 API에서 발생한 slow query: **0건**
- 시드 완료 후 performance_schema reset 필요

### 6. 처리량
- 총 HTTP 요청: **1,731,498건** (~19분)
- 평균 처리량: ~1,500 req/s
- Max response time: 4.5초

## 조치사항 (Round 2 적용)

| # | 조치 | 상태 |
|---|------|------|
| 1 | Heap 2G → 4G 증설 | 완료 |
| 2 | TestNotificationController ec2 프로필 추가 | 완료 |
| 3 | MockTossPaymentClient ec2 프로필 추가 | 완료 |
| 4 | application.yml ec2 프로필에서 RabbitMQ/MongoDB 제거 | 완료 |

## Grafana 대시보드
- URL: http://3.37.17.1:3000 (admin/admin)
