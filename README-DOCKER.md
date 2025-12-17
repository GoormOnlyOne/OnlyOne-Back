# Docker Compose 사용 가이드

## 개요

이 프로젝트는 단일 파일 `docker-compose.yml`로 통합되었으며, profiles를 사용하여 다양한 모드로 실행할 수 있습니다.

## 실행 모드

### 1. 인프라만 실행 (기본)

데이터베이스, Redis, Kafka, Elasticsearch 등 인프라 서비스만 실행합니다.

```bash
docker-compose up -d
```

**실행되는 서비스:**
- MySQL (port: 3306)
- Redis (port: 7379)
- Kafka (port: 10092, 29092)
- Zookeeper (port: 2181)
- Elasticsearch (port: 10200, 10300)
- Kafka UI (port: 10989)
- Prometheus (port: 9500)
- InfluxDB (port: 8086)
- Grafana (port: 3333)
- Jaeger (port: 16686)

### 2. 단일 애플리케이션 서버 모드

인프라 + 단일 앱 서버를 실행합니다.

```bash
docker-compose --profile single up -d
```

**추가 서비스:**
- app (port: 8080) - 단일 Spring Boot 애플리케이션

### 3. 스케일 모드 (3개 서버 + Nginx 로드밸런서)

인프라 + 3개 앱 서버 + Nginx 로드밸런서를 실행합니다.

```bash
docker-compose --profile scale up -d
```

**추가 서비스:**
- app1 (내부 포트: 8080)
- app2 (내부 포트: 8080)
- app3 (내부 포트: 8080)
- nginx (port: 8080) - 3개 서버로 부하 분산

**Nginx 로드밸런싱:**
- 알고리즘: `ip_hash` (같은 클라이언트는 같은 서버로 라우팅)
- SSE 연결 유지를 위한 특별한 설정 포함
- `/api/sse` 엔드포인트는 버퍼링 완전 비활성화

### 4. 부하 테스트 모드

스케일 모드 + K6 부하 테스트를 실행합니다.

```bash
# 스케일 모드 먼저 실행
docker-compose --profile scale up -d

# 부하 테스트 실행
docker-compose --profile load-test up k6
```

**K6 테스트 스크립트 변경:**

`docker-compose.yml` 파일에서 k6 서비스의 `command` 부분을 수정하여 다른 테스트를 실행할 수 있습니다:

```yaml
# 부하 분산 확인 테스트 (1분)
command: run --out influxdb=http://influxdb:8086/k6 /scripts/0-load-balance-test.js

# SSE 연결 테스트 (약 7분)
command: run --out influxdb=http://influxdb:8086/k6 /scripts/1-sse-connection-test.js

# 알림 생성 테스트 (5분)
command: run --out influxdb=http://influxdb:8086/k6 /scripts/2-notification-create-test.js

# API 조회 테스트 (5분)
command: run --out influxdb=http://influxdb:8086/k6 /scripts/3-api-query-test.js
```

## 서비스 확인

### 로그 확인

```bash
# 모든 서비스 로그
docker-compose logs -f

# 특정 서비스 로그
docker-compose logs -f app1
docker-compose logs -f nginx
docker-compose logs -f k6
```

### Nginx 부하 분산 확인

Nginx 로그를 확인하여 요청이 app1, app2, app3에 분산되는지 확인:

```bash
docker-compose logs -f nginx | grep "upstream:"
```

로그 출력 예시:
```
nginx | 172.20.0.1 - - [17/Dec/2025:12:00:00 +0000] "GET /actuator/health HTTP/1.1" 200 156 "-" "k6/0.45.0" upstream: 172.20.0.10:8080 upstream_response_time: 0.023
nginx | 172.20.0.1 - - [17/Dec/2025:12:00:01 +0000] "GET /actuator/health HTTP/1.1" 200 156 "-" "k6/0.45.0" upstream: 172.20.0.11:8080 upstream_response_time: 0.019
nginx | 172.20.0.1 - - [17/Dec/2025:12:00:02 +0000] "GET /actuator/health HTTP/1.1" 200 156 "-" "k6/0.45.0" upstream: 172.20.0.12:8080 upstream_response_time: 0.021
```

### 서비스 상태 확인

```bash
docker-compose ps
```

### 헬스체크 확인

```bash
# 단일 모드
curl http://localhost:8080/actuator/health

# 스케일 모드 (Nginx 통해 접근)
curl http://localhost:8080/actuator/health

# Nginx 자체 헬스체크
curl http://localhost:8080/nginx-health
```

## 모니터링

### Grafana

- URL: http://localhost:3333
- 기본 계정: admin / admin
- K6 테스트 결과는 InfluxDB 데이터소스에서 확인 가능

### Prometheus

- URL: http://localhost:9500
- 애플리케이션 메트릭 수집

### Jaeger

- URL: http://localhost:16686
- 분산 트레이싱

### Kafka UI

- URL: http://localhost:10989
- Kafka 토픽 및 메시지 모니터링

## 종료 및 정리

### 서비스 중지 (데이터 유지)

```bash
# 단일 모드
docker-compose --profile single down

# 스케일 모드
docker-compose --profile scale down
```

### 완전 삭제 (볼륨 포함)

```bash
docker-compose --profile scale down -v
```

### 빌드 캐시 삭제

```bash
docker-compose build --no-cache
```

## 트러블슈팅

### 포트 충돌

Windows에서 Redis 기본 포트(6379)가 충돌할 수 있습니다. 이 경우 7379 포트를 사용합니다.

```bash
# Windows에서 포트 사용 확인
netstat -ano | findstr :6379
```

### 컨테이너가 시작되지 않을 때

1. 로그 확인
```bash
docker-compose logs <service-name>
```

2. 헬스체크 상태 확인
```bash
docker inspect <container-name> | grep -A 10 Health
```

3. 재시작
```bash
docker-compose restart <service-name>
```

### Nginx가 upstream 서버를 찾지 못할 때

app1, app2, app3이 모두 healthy 상태가 될 때까지 기다립니다 (약 3분 소요).

```bash
# 앱 서버 헬스체크 상태 확인
docker-compose ps
```

## 개발 워크플로우

### 로컬 개발

1. 인프라만 실행하여 IDE에서 애플리케이션 실행
```bash
docker-compose up -d
```

2. IDE에서 Spring Boot 애플리케이션 실행 (port: 8080)

### 부하 테스트

1. 스케일 모드로 실행
```bash
docker-compose --profile scale up -d --build
```

2. 모든 서버가 healthy 상태가 될 때까지 대기 (약 3분)

3. K6 부하 테스트 실행
```bash
docker-compose --profile load-test up k6
```

4. Grafana에서 결과 확인 (http://localhost:3333)

5. Nginx 로그로 부하 분산 확인
```bash
docker-compose logs nginx | grep "upstream:" | tail -100
```

## 참고사항

- **docker-compose.scale.yml은 삭제 가능**: 모든 기능이 `docker-compose.yml`로 통합되었습니다.
- **K6 이미지**: `xk6-sse:local` 이미지가 사전에 빌드되어 있어야 합니다.
- **토큰 파일**: K6 테스트를 위해서는 `k6-load-test/tokens.json` 파일이 필요합니다.
