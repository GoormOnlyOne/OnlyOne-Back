# k6 부하 테스트 가이드

알림 시스템 SSE + API 성능 테스트

## 📁 구조

```
k6-load-test/
├── notification-sse-test.js      # 부하 테스트 스크립트
├── token-generator.js             # JWT 토큰 생성기
├── tokens.json                    # 생성된 토큰 (자동 생성)
├── PERFORMANCE_TEST_PLAN.md       # 상세 성능 테스트 계획
└── README.md                      # 이 파일
```

---

## 🚀 빠른 시작

### 1. 토큰 생성

```bash
cd k6-load-test
node token-generator.js
```

생성된 `tokens.json` (1000개 사용자):
```json
{
  "1000": "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9...",
  "1001": "eyJhbGciOiJIUzUxMiIsInR5cCI6IkpXVCJ9...",
  ...
}
```

### 2. Grafana 모니터링 시작

#### InfluxDB + Grafana 실행 (이미 실행 중)
```bash
# 프로젝트 루트에서
docker-compose up -d influxdb grafana
```

#### Grafana 대시보드 Import
1. http://localhost:3000 접속 (admin/admin)
2. **Dashboards** → **Import** → **2587** 입력
3. InfluxDB 선택: **InfluxDB-k6**
4. Import 클릭

### 3. 테스트 실행

```bash
# InfluxDB로 메트릭 전송하며 실행
k6 run --out influxdb=http://localhost:8086/k6 notification-sse-test.js

# 또는 Docker로 실행
docker run --rm -i --network=onlyone-back_onlyone-network \
  -v "%cd%/k6-load-test:/scripts" \
  xk6-sse:local run \
  --out influxdb=http://onlyone-influxdb:8086/k6 \
  /scripts/notification-sse-test.js
```

### 4. Grafana에서 실시간 모니터링

http://localhost:3000 에서 실시간으로 확인:
- RPS (초당 요청 수)
- 응답 시간 (p50, p95, p99)
- 오류율
- SSE 연결 수
- 커스텀 메트릭

---

## 📊 현재 테스트 시나리오

### SSE 연결 부하 (2000명)
```
0 → 200명 (30초)
200 → 1000명 (1분)
1000 → 2000명 (2분)
2000명 유지 (5분)
2000 → 0명 (1분)
```

### 알림 생성 부하 (1000 TPS)
```
초당 1000개 알림 생성 (8분간)
100명에게 집중 전송 (부하 테스트)
```

### API 조회 부하 (200 RPS)
```
GET /notifications?size=20
GET /notifications/unread-count
```

---

## 🎯 성능 목표

| 메트릭 | 목표 | 현재 설정 |
|-------|------|-----------|
| **SSE 동시 연결** | > 2000 | 2000 |
| **알림 생성 TPS** | > 1000 | 1000 |
| **API 응답시간 (p95)** | < 1000ms | threshold 설정 |
| **오류율** | < 1% | < 10% |
| **SSE 성공률** | > 95% | > 85% |

---

## 🔧 주요 설정

### 환경 변수

```bash
# 서버 URL 변경
k6 run -e BASE_URL=http://your-server:8080 notification-sse-test.js
```

### JWT Secret 동기화

`token-generator.js`의 `JWT_SECRET`을 `application.yml`의 `jwt.secret`과 일치시키세요:

```javascript
// token-generator.js
const JWT_SECRET = 'f279f3c64384508bd003b8a8b95362ea...';
```

---

## 📋 체크리스트

테스트 전:
- [ ] Docker 컨테이너 실행 중 (MySQL, Redis, Kafka, InfluxDB, Grafana)
- [ ] Spring Boot 애플리케이션 실행 중
- [ ] `tokens.json` 생성 완료
- [ ] Grafana 대시보드 Import 완료
- [ ] JPA OSIV 비활성화 (`spring.jpa.open-in-view=false`)

---

## 🐛 트러블슈팅

### 토큰 생성 실패
```bash
# Node.js 설치 확인
node --version

# 다시 생성
node token-generator.js
```

### SSE 연결 실패
```bash
# 서버 상태 확인
curl http://localhost:8080/actuator/health

# 토큰 유효성 확인
curl -H "Authorization: Bearer YOUR_TOKEN" \
     http://localhost:8080/notifications
```

### InfluxDB 연결 실패
```bash
# InfluxDB 상태 확인
docker ps | grep influxdb
docker exec onlyone-influxdb influx -execute "SHOW DATABASES"
```

### Grafana에 데이터 안 보임
1. InfluxDB 데이터 확인:
   ```bash
   docker exec onlyone-influxdb influx -database k6 -execute "SHOW MEASUREMENTS"
   ```
2. Grafana 데이터소스 테스트: Settings → Data Sources → InfluxDB-k6 → Test
3. Time Range를 **Last 5 minutes**로 설정

---

## 📚 상세 문서

- **[PERFORMANCE_TEST_PLAN.md](./PERFORMANCE_TEST_PLAN.md)**: 7가지 성능 테스트 시나리오, 로컬 환경 한계, 병목 분석 가이드
- [k6 공식 문서](https://k6.io/docs/)
- [xk6-sse GitHub](https://github.com/phymbert/xk6-sse)
- [Grafana k6 대시보드](https://grafana.com/grafana/dashboards/2587)

---

## 💡 다음 단계

1. **베이스라인 측정**: 부하 없을 때 메트릭 기록
2. **Load Test**: 정상 운영 부하 시뮬레이션
3. **Spike Test**: 급격한 트래픽 증가 테스트
4. **Soak Test**: 1시간 장기 안정성 테스트
5. **병목 분석**: Grafana + 애플리케이션 로그 분석
6. **튜닝 및 재테스트**: 설정 변경 후 성능 비교

상세한 시나리오는 `PERFORMANCE_TEST_PLAN.md` 참조!
