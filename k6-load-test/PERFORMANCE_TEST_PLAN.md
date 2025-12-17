# 알림 시스템 성능 테스트 계획

## 📋 목차
1. [로컬 환경 한계점 분석](#1-로컬-환경-한계점-분석)
2. [테스트 시나리오 설계](#2-테스트-시나리오-설계)
3. [메트릭 및 목표 설정](#3-메트릭-및-목표-설정)
4. [테스트 실행 계획](#4-테스트-실행-계획)
5. [분석 및 튜닝 가이드](#5-분석-및-튜닝-가이드)

---

## 1. 로컬 환경 한계점 분석

### 1.1 하드웨어 제약사항
```
단일 로컬 머신의 물리적 한계:
├─ CPU: 코어 수에 따른 동시 처리 한계
├─ 메모리: JVM 힙 + SSE 연결 메모리 사용량
├─ 네트워크: localhost 루프백 (대역폭은 충분하나 컨텍스트 스위칭 부담)
└─ 파일 디스크립터: OS별 소켓 제한 (Windows: 약 16K, Linux: ulimit 설정)
```

### 1.2 소프트웨어 제약사항
| 구성 요소 | 한계점 | 권장 설정 |
|----------|--------|-----------|
| **JVM 힙 메모리** | SSE 연결당 약 50KB + 알림 객체 | `-Xmx4G` 이상 |
| **Thread Pool** | Virtual Thread 사용 시 제한 낮음 | notificationExecutor 크기 조정 |
| **DB 커넥션** | HikariCP 기본 10개 | 50-100으로 증가 |
| **Kafka** | 로컬 단일 브로커, 파티션 제한 | 파티션 3-5개 |
| **Redis** | 싱글 스레드, 메모리 기반 | maxmemory 설정 |

### 1.3 측정 가능한 한계
```bash
# 1. OS 파일 디스크립터 확인 (Linux/Mac)
ulimit -n

# 2. JVM 메모리 모니터링
jconsole 또는 VisualVM 사용

# 3. 네트워크 연결 수 확인 (Windows)
netstat -an | find "ESTABLISHED" | find ":8080"

# 4. CPU/메모리 사용률
작업 관리자 또는 Resource Monitor
```

---

## 2. 테스트 시나리오 설계

### 2.1 기본 부하 테스트 (Load Test)
**목표**: 정상 운영 부하에서 시스템 안정성 확인

**시나리오**: 일상적인 트래픽 패턴 시뮬레이션
- SSE 연결: 500명 동시 접속 유지
- 알림 생성: 100 TPS (초당 100개)
- API 조회: 50 RPS (초당 50회 요청)
- 지속 시간: 10분

```javascript
// k6-load-test/scenarios/01-load-test.js
export const options = {
  scenarios: {
    sse_connections: {
      executor: 'constant-vus',
      vus: 500,
      duration: '10m',
      exec: 'sseTest',
    },
    notification_create: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '10m',
      preAllocatedVUs: 50,
      exec: 'createNotification',
    },
    api_queries: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '10m',
      preAllocatedVUs: 20,
      exec: 'apiTest',
    },
  },
  thresholds: {
    'http_req_duration': ['p(95)<1000', 'p(99)<2000'],
    'http_req_failed': ['rate<0.01'],
    'sse_success_rate': ['rate>0.95'],
  },
};
```

---

### 2.2 스파이크 테스트 (Spike Test)
**목표**: 급격한 트래픽 증가 시 시스템 복구 능력 확인

**시나리오**: 갑작스러운 이벤트 (예: 공지사항 발송)
- 평상시: 100 TPS
- 스파이크: 1000 TPS (10배)
- 지속: 30초
- 반복: 3회

```javascript
// k6-load-test/scenarios/02-spike-test.js
export const options = {
  scenarios: {
    spike_test: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      stages: [
        { duration: '2m', target: 100 },   // 안정 상태
        { duration: '30s', target: 1000 }, // 급증
        { duration: '2m', target: 100 },   // 회복
        { duration: '30s', target: 1000 }, // 재급증
        { duration: '2m', target: 100 },   // 회복
      ],
      preAllocatedVUs: 100,
      maxVUs: 500,
    },
  },
  thresholds: {
    'http_req_duration': ['p(95)<3000'],  // 스파이크 시 느려질 수 있음
    'http_req_failed': ['rate<0.05'],     // 5% 실패 허용
  },
};
```

---

### 2.3 스트레스 테스트 (Stress Test)
**목표**: 시스템 한계점 도달 직전의 최대 처리량 측정

**시나리오**: 점진적 부하 증가로 임계점 찾기
- SSE 연결: 0 → 2000명 (단계적)
- 알림 생성: 100 → 2000 TPS (단계적)
- 목표: 오류율 5% 도달 시점 확인

```javascript
// k6-load-test/scenarios/03-stress-test.js
export const options = {
  scenarios: {
    sse_stress: {
      executor: 'ramping-vus',
      stages: [
        { duration: '2m', target: 500 },
        { duration: '2m', target: 1000 },
        { duration: '2m', target: 1500 },
        { duration: '2m', target: 2000 },
        { duration: '3m', target: 2000 },  // 유지
        { duration: '1m', target: 0 },
      ],
      exec: 'sseTest',
    },
    notification_stress: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      stages: [
        { duration: '2m', target: 500 },
        { duration: '2m', target: 1000 },
        { duration: '2m', target: 1500 },
        { duration: '2m', target: 2000 },
        { duration: '3m', target: 2000 },
      ],
      preAllocatedVUs: 200,
      maxVUs: 1000,
      exec: 'createNotification',
    },
  },
  thresholds: {
    'http_req_duration': ['p(95)<5000'],
    'http_req_failed': ['rate<0.10'],  // 10% 허용
  },
};
```

---

### 2.4 침수 테스트 (Soak Test)
**목표**: 장시간 운영 시 메모리 누수, 리소스 고갈 확인

**시나리오**: 중간 부하 장시간 유지
- SSE 연결: 1000명 유지
- 알림 생성: 200 TPS 유지
- 지속 시간: 1시간

```javascript
// k6-load-test/scenarios/04-soak-test.js
export const options = {
  scenarios: {
    soak_sse: {
      executor: 'constant-vus',
      vus: 1000,
      duration: '1h',
      exec: 'sseTest',
    },
    soak_notification: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '1h',
      preAllocatedVUs: 100,
      exec: 'createNotification',
    },
  },
  thresholds: {
    'http_req_duration': ['p(95)<2000', 'p(99)<5000'],
    'http_req_failed': ['rate<0.02'],
  },
};
```

**모니터링 항목**:
- JVM 힙 메모리 증가 추세
- DB 커넥션 풀 누수
- Thread 누적
- SSE 연결 좀비 프로세스

---

### 2.5 단계별 부하 증가 (Step Load Test)
**목표**: 각 부하 레벨에서 시스템 안정성 확인

```javascript
// k6-load-test/scenarios/05-step-load-test.js
export const options = {
  scenarios: {
    step_load: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      stages: [
        { duration: '5m', target: 100 },   // Level 1
        { duration: '5m', target: 300 },   // Level 2
        { duration: '5m', target: 500 },   // Level 3
        { duration: '5m', target: 800 },   // Level 4
        { duration: '5m', target: 1000 },  // Level 5
      ],
      preAllocatedVUs: 200,
      maxVUs: 500,
    },
  },
};
```

---

### 2.6 파괴 테스트 (Breakpoint Test)
**목표**: 시스템이 완전히 다운되는 임계점 찾기

**시나리오**: 한계를 넘어서까지 부하 증가
- 오류율 50% 도달 또는 시스템 다운까지

```javascript
// k6-load-test/scenarios/06-breakpoint-test.js
export const options = {
  scenarios: {
    breakpoint: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      stages: [
        { duration: '1m', target: 500 },
        { duration: '1m', target: 1000 },
        { duration: '1m', target: 2000 },
        { duration: '1m', target: 3000 },
        { duration: '1m', target: 5000 },
        { duration: '1m', target: 10000 },  // 극한 부하
      ],
      preAllocatedVUs: 500,
      maxVUs: 2000,
    },
  },
  thresholds: {
    // 임계값 없음 - 실패 관찰이 목적
  },
};
```

---

### 2.7 실제 사용 패턴 시뮬레이션 (Realistic Scenario)
**목표**: 실제 사용자 행동 패턴 재현

**시나리오**:
- 로그인 → SSE 연결
- 알림 조회 (랜덤 간격)
- 알림 읽음 처리
- 랜덤 체류 시간
- 연결 종료

```javascript
// k6-load-test/scenarios/07-realistic-scenario.js
export function userJourney(data) {
  const user = selectRandomUser(data.testUsers);
  const token = getAuthToken(user.userId);

  // 1. SSE 연결
  const sseClient = connectSSE(token);

  // 2. 30초 대기 중 랜덤 API 호출
  const duration = 30 + Math.random() * 60;  // 30-90초
  const startTime = Date.now();

  while ((Date.now() - startTime) / 1000 < duration) {
    // 랜덤하게 알림 조회
    if (Math.random() < 0.3) {
      http.get(`${BASE_URL}/notifications`, { headers: { Authorization: `Bearer ${token}` }});
    }

    // 랜덤하게 읽지 않은 개수 조회
    if (Math.random() < 0.2) {
      http.get(`${BASE_URL}/notifications/unread-count`, { headers: { Authorization: `Bearer ${token}` }});
    }

    sleep(5 + Math.random() * 10);  // 5-15초 대기
  }

  // 3. 연결 종료
  sseClient.close();
}
```

---

## 3. 메트릭 및 목표 설정

### 3.1 핵심 성능 지표 (KPI)

| 메트릭 | 목표 | 측정 방법 |
|-------|------|----------|
| **SSE 연결 성공률** | > 95% | `sse_success_rate` |
| **SSE 동시 연결 수** | 2000+ | `sse_connections_total` |
| **알림 생성 처리량** | 1000 TPS | `notifications_created` |
| **알림 전송 지연** | < 500ms (p95) | SSE event 수신 시각 - 생성 시각 |
| **API 응답 시간** | < 1000ms (p95) | `http_req_duration` |
| **API 오류율** | < 1% | `http_req_failed` |
| **DB 쿼리 시간** | < 100ms (p95) | 애플리케이션 로그 분석 |
| **메모리 사용량** | < 80% | JVM 모니터링 |
| **CPU 사용량** | < 80% | OS 모니터링 |

### 3.2 임계값 설정

```javascript
// 권장 임계값
thresholds: {
  // HTTP 요청
  'http_req_duration': [
    'p(50)<500',   // 중앙값 500ms 미만
    'p(95)<1000',  // 95%가 1초 미만
    'p(99)<2000',  // 99%가 2초 미만
  ],
  'http_req_failed': ['rate<0.01'],  // 1% 미만 실패

  // SSE
  'sse_success_rate': ['rate>0.95'],  // 95% 이상 연결 성공
  'sse_events_received': ['count>10000'],  // 10,000개 이상 이벤트 수신

  // 알림 생성
  'notification_create_rate': ['rate>0.95'],  // 95% 이상 생성 성공

  // API 응답 시간
  'api_response_time': [
    'p(95)<1000',
    'p(99)<2000',
  ],
}
```

---

## 4. 테스트 실행 계획

### 4.1 사전 준비

#### Step 1: 테스트 데이터 준비
```bash
# 1. 사용자 데이터 생성 (1000-1999)
cd k6-load-test
node token-generator.js --start 1000 --end 1999

# 2. DB에 테스트 사용자 Insert
# test-data-notification-10m.sql 실행
```

#### Step 2: 시스템 설정 최적화
```yaml
# application.yml 튜닝
spring:
  datasource:
    hikari:
      maximum-pool-size: 100  # 증가

  kafka:
    producer:
      batch-size: 32768
      linger-ms: 10

app:
  notification:
    cleanup-interval-minutes: 5

# JVM 옵션
java -Xms2G -Xmx4G -XX:+UseG1GC -jar app.jar
```

#### Step 3: 모니터링 도구 실행
```bash
# 1. Prometheus + Grafana (권장)
docker-compose up -d prometheus grafana

# 2. JVM 모니터링
jconsole 또는 VisualVM 연결

# 3. DB 모니터링
# MySQL/PostgreSQL 슬로우 쿼리 로그 활성화
```

### 4.2 테스트 실행 순서

```bash
# 1단계: 기본 부하 테스트 (10분)
k6 run scenarios/01-load-test.js

# 2단계: 스파이크 테스트 (10분)
k6 run scenarios/02-spike-test.js

# 3단계: 스트레스 테스트 (15분)
k6 run scenarios/03-stress-test.js

# 4단계: 침수 테스트 (1시간) - 야간 실행 권장
k6 run scenarios/04-soak-test.js

# 5단계: 단계별 부하 (25분)
k6 run scenarios/05-step-load-test.js

# 6단계: 파괴 테스트 (10분) - 마지막에 실행
k6 run scenarios/06-breakpoint-test.js

# 7단계: 실제 시나리오 (20분)
k6 run scenarios/07-realistic-scenario.js
```

### 4.3 결과 수집

```bash
# k6 결과를 JSON으로 저장
k6 run --out json=results/load-test-result.json scenarios/01-load-test.js

# Grafana Cloud로 전송 (선택)
k6 run --out cloud scenarios/01-load-test.js

# HTML 리포트 생성 (k6-reporter 사용)
k6 run --out json=results/result.json scenarios/01-load-test.js
npx k6-to-html results/result.json --output results/report.html
```

---

## 5. 분석 및 튜닝 가이드

### 5.1 병목 지점 식별

#### 시나리오 1: SSE 연결 실패율 높음
**증상**: `sse_success_rate < 85%`

**원인 분석**:
```bash
# 1. 파일 디스크립터 부족
ulimit -n  # Linux/Mac
netstat -an | find /c "8080"  # Windows

# 2. Thread Pool 고갈
# JVisualVM에서 Thread 수 확인

# 3. 메모리 부족
# Heap 사용률 확인
```

**해결책**:
```yaml
# application.yml
server:
  tomcat:
    threads:
      max: 500
    max-connections: 20000

# OS 레벨
ulimit -n 65535  # Linux
```

---

#### 시나리오 2: 알림 생성 TPS 저하
**증상**: `notifications_created < 목표치`

**원인 분석**:
```sql
-- DB 슬로우 쿼리 확인
SHOW FULL PROCESSLIST;

-- 인덱스 확인
EXPLAIN SELECT * FROM notifications WHERE user_id = 1000 ORDER BY created_at DESC;
```

**해결책**:
```sql
-- 인덱스 추가
CREATE INDEX idx_user_created ON notifications(user_id, created_at DESC);
CREATE INDEX idx_sse_sent ON notifications(sse_sent, created_at);

-- 파티셔닝 고려
ALTER TABLE notifications PARTITION BY RANGE (YEAR(created_at)) ...
```

---

#### 시나리오 3: 메모리 누수
**증상**: Soak Test 중 메모리 지속 증가

**원인 분석**:
```bash
# Heap Dump 수집
jmap -dump:live,format=b,file=heap.bin <PID>

# Eclipse MAT로 분석
```

**해결책**:
- SSE Emitter 정리 로직 확인
- Event Listener 해제 확인
- Cache 만료 정책 점검

---

#### 시나리오 4: DB 커넥션 부족
**증상**: `SQLTransientConnectionException`

**해결책**:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 100
      connection-timeout: 30000
      leak-detection-threshold: 60000
```

---

### 5.2 최적화 체크리스트

#### 애플리케이션 레벨
- [ ] DB 쿼리 N+1 문제 해결 (Fetch Join)
- [ ] Redis 캐시 적용 (unreadCount)
- [ ] Batch Update 활용 (SSE 상태 업데이트)
- [ ] 비동기 처리 확대 (Event Publishing)
- [ ] Connection Pool 튜닝

#### 인프라 레벨
- [ ] JVM 힙 메모리 증설
- [ ] DB 인덱스 최적화
- [ ] Kafka 파티션 증가
- [ ] Redis Cluster 구성 (선택)

#### 코드 레벨
- [ ] Stream API 대신 for-loop (성능 중요 구간)
- [ ] StringBuilder 사용
- [ ] ObjectMapper 재사용
- [ ] Virtual Thread 활용 (Java 21+)

---

### 5.3 보고서 템플릿

```markdown
# 성능 테스트 결과 보고서

## 테스트 정보
- 테스트 일시: 2025-12-10
- 테스트 시나리오: Load Test
- 지속 시간: 10분
- 대상 환경: 로컬 (Windows 11, i7-12700K, 32GB RAM)

## 결과 요약
| 메트릭 | 목표 | 실제 | 달성 여부 |
|-------|------|------|----------|
| SSE 연결 성공률 | >95% | 97.3% | ✅ PASS |
| 알림 생성 TPS | 1000 | 850 | ❌ FAIL |
| API 응답시간 (p95) | <1000ms | 1200ms | ❌ FAIL |

## 병목 지점
1. DB 쿼리 시간 초과 (notifications 테이블 Full Scan)
2. Kafka Producer 배치 처리 지연

## 개선 권장사항
1. notifications 테이블에 복합 인덱스 추가
2. Kafka batch-size 증가 (16KB → 32KB)
3. DB 커넥션 풀 50 → 100 증가

## 첨부
- [상세 메트릭 그래프]
- [슬로우 쿼리 로그]
```

---

## 6. 로컬 환경 권장 설정

### 6.1 최소 사양
- CPU: 8코어 이상
- RAM: 16GB 이상
- SSD: 필수

### 6.2 권장 설정
```bash
# Windows
# 1. Docker Desktop 리소스 할당
# Settings → Resources → Memory: 8GB, CPU: 6 cores

# 2. Java 옵션
java -Xms2G -Xmx4G -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -jar app.jar

# 3. k6 실행 시 리소스 제한
k6 run --vus 500 --duration 10m scenario.js
```

### 6.3 주의사항
- 로컬 테스트는 네트워크 레이턴시가 0에 가까움 (비현실적)
- 실제 프로덕션보다 높은 부하 시뮬레이션 가능
- 디스크 I/O 병목 발생 가능 (SSD 권장)
- Windows Defender 실시간 검사 비활성화 권장

---

## 7. 다음 단계

1. **분산 테스트** (여러 머신 사용)
   - k6 Cloud 또는 여러 k6 인스턴스 동시 실행

2. **프로덕션 환경 시뮬레이션**
   - AWS EC2에서 테스트
   - 네트워크 레이턴시 추가

3. **CI/CD 통합**
   - GitHub Actions에서 자동 성능 테스트
   - 성능 회귀 감지

---

## 참고 자료
- [k6 공식 문서](https://k6.io/docs/)
- [SSE 성능 최적화 가이드](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events)
- [Spring Boot 성능 튜닝](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html)
