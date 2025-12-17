# 개별 부하 테스트 실행 가이드

각 테스트를 독립적으로 실행하여 성능 문제를 정확히 파악할 수 있습니다.

## 📋 분리된 테스트 목록

| 파일 | 테스트 내용 | 부하 | 소요 시간 |
|------|------------|------|-----------|
| `1-sse-connection-test.js` | SSE 연결 안정성 | 최대 1000명 동시 연결 | 7.5분 |
| `2-notification-create-test.js` | 알림 생성 성능 | 500개/초 | 5분 |
| `3-api-query-test.js` | API 조회 성능 | 100 RPS | 5분 |

---

## 🚀 실행 방법

### ✅ Windows

```cmd
cd k6-load-test

REM 개별 테스트
run-tests.bat 1              # SSE 연결 테스트
run-tests.bat 2              # 알림 생성 테스트
run-tests.bat 3              # API 조회 테스트
run-tests.bat notification   # 알림 생성 테스트 (별칭)
run-tests.bat api            # API 조회 테스트 (별칭)

REM 전체 순차 실행
run-tests.bat all
```

### ✅ Linux/Mac

```bash
cd k6-load-test
chmod +x run-tests.sh  # 최초 1회

# 개별 테스트
./run-tests.sh 1              # SSE 연결 테스트
./run-tests.sh 2              # 알림 생성 테스트
./run-tests.sh 3              # API 조회 테스트
./run-tests.sh notification   # 알림 생성 테스트 (별칭)
./run-tests.sh api            # API 조회 테스트 (별칭)

# 전체 순차 실행
./run-tests.sh all
```

---

## 📊 각 테스트 상세

### 1️⃣ SSE 연결 테스트

**목적**: SSE 연결 안정성 및 동시 연결 처리

**시나리오**:
```
0초 → 30초: 0명 → 200명
30초 → 1분30초: 200명 → 500명
1분30초 → 3분30초: 500명 → 1000명
3분30초 → 6분30초: 1000명 유지
6분30초 → 7분30초: 1000명 → 0명
```

**측정 지표**:
- ✅ `sse_success_rate > 90%` - 연결 성공률
- ✅ `sse_connections_total > 800` - 총 연결 수
- SSE 이벤트 수신 건수
- 에러 발생 건수

**Docker 직접 실행**:
```bash
docker run --rm \
  --network onlyone-network \
  -v "$(pwd)":/scripts \
  -e BASE_URL=http://host.docker.internal:8080 \
  xk6-sse:local run \
  --out influxdb=http://onlyone-influxdb:8086/k6 \
  /scripts/1-sse-connection-test.js
```

---

### 2️⃣ 알림 생성 테스트

**목적**: 알림 생성 API 및 배치 처리 성능

**시나리오**:
```
5분간 초당 500개 알림 생성
총 150,000개 알림 생성 예상
```

**측정 지표**:
- ✅ `notification_create_rate > 95%` - 생성 성공률
- ✅ `P95 < 1초, P99 < 3초` - 응답 시간
- ✅ `notifications_created > 2000` - 최소 생성 수
- 실패 건수 및 원인

**Docker 직접 실행**:
```bash
docker run --rm \
  --network onlyone-network \
  -v "$(pwd)":/scripts \
  -e BASE_URL=http://host.docker.internal:8080 \
  xk6-sse:local run \
  --out influxdb=http://onlyone-influxdb:8086/k6 \
  /scripts/2-notification-create-test.js
```

---

### 3️⃣ API 조회 테스트

**목적**: 알림 조회 API 성능 및 DB 쿼리 최적화

**시나리오**:
```
5분간 초당 100개 API 요청
- 알림 목록 조회 (GET /notifications?size=20)
- 읽지 않은 개수 조회 (GET /notifications/unread-count)
```

**측정 지표**:
- ✅ `api_success_rate > 98%` - API 성공률
- ✅ `P95 < 500ms, P99 < 1초` - 응답 시간
- ✅ `http_req_failed < 2%` - 실패율
- 목록 조회 vs 개수 조회 성능 비교

**Docker 직접 실행**:
```bash
docker run --rm \
  --network onlyone-network \
  -v "$(pwd)":/scripts \
  -e BASE_URL=http://host.docker.internal:8080 \
  xk6-sse:local run \
  --out influxdb=http://onlyone-influxdb:8086/k6 \
  /scripts/3-api-query-test.js
```

---

## 🔍 성능 분석 워크플로우

### 1. 테스트 실행
```bash
# 예: 알림 생성 테스트
cd k6-load-test
run-tests.bat notification  # Windows
./run-tests.sh notification # Linux/Mac
```

### 2. 결과 확인
```
콘솔 출력에서 핵심 지표 확인:
- http_req_duration........: p(95)=xxx ms
- notification_create_rate: xx.xx%
- notifications_created...: xxxx
```

### 3. Jaeger 트레이싱으로 병목 찾기
```
http://localhost:16686

1. Service: onlyone-api 선택
2. Operation: POST /notifications 선택
3. Duration 기준 정렬
4. 가장 느린 Span 클릭
5. 어느 부분이 시간을 많이 소비하는지 확인
   - DB 쿼리?
   - 외부 API 호출?
   - 비즈니스 로직?
```

### 4. Grafana 대시보드
```
http://localhost:3333

확인 사항:
- HikariCP 커넥션 사용량
- JVM 메모리 사용량
- API 응답 시간 추이
- 에러율 추이
```

### 5. 시스템 리소스
```bash
# DB 커넥션 확인
docker exec onlyone-mysql mysql -uonlyone -ppassword \
  -e "SHOW STATUS LIKE 'Threads%';"

# 컨테이너 리소스
docker stats onlyone-mysql onlyone-redis
```

---

## ⚠️ 주의사항

### 테스트 간 간격
- 각 테스트 사이 최소 10초 대기
- DB 및 캐시 정리 필요 시 재시작

### 리소스 모니터링
```bash
# 실시간 모니터링
watch -n 1 'docker stats --no-stream | head -3'
```

### 문제 발생 시
1. 애플리케이션 로그 확인
2. Jaeger에서 에러 트레이스 확인
3. DB 커넥션 풀 상태 확인
4. 시스템 리소스 확인

---

## 🎯 권장 테스트 순서

### 첫 테스트 (베이스라인)
```bash
# 1. 가장 가벼운 테스트부터
run-tests.bat api

# 2. Jaeger에서 평균 응답 시간 확인
# 3. 베이스라인 기록
```

### 문제 파악
```bash
# 2. 쓰기 성능 테스트
run-tests.bat notification

# 3. 문제가 발견되면 Jaeger 분석
# 4. 병목 지점 특정
```

### 최종 검증
```bash
# 3. SSE 연결 안정성
run-tests.bat sse

# 4. 전체 통합 테스트
run-tests.bat all
```

---

## 📈 성능 개선 체크리스트

### DB 최적화
- [ ] 인덱스 확인 (`SHOW INDEX FROM notification`)
- [ ] 쿼리 실행 계획 확인 (`EXPLAIN SELECT ...`)
- [ ] 슬로우 쿼리 로그 분석
- [ ] HikariCP 설정 최적화

### 애플리케이션 최적화
- [ ] N+1 쿼리 제거
- [ ] 배치 처리 최적화
- [ ] 캐시 전략 개선
- [ ] 트랜잭션 범위 최소화

### 인프라 최적화
- [ ] MySQL 최대 연결 수 증가
- [ ] JVM 힙 메모리 조정
- [ ] Redis 메모리 최적화
- [ ] 네트워크 타임아웃 조정
