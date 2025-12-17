@echo off
REM K6 부하 테스트 실행 스크립트 (Windows)

set BASE_URL=http://host.docker.internal:8080
set INFLUXDB_URL=http://onlyone-influxdb:8086/k6

echo ==========================================
echo K6 부하 테스트 실행
echo Base URL: %BASE_URL%
echo InfluxDB: %INFLUXDB_URL%
echo ==========================================
echo.

if "%1"=="1" goto sse
if "%1"=="sse" goto sse
if "%1"=="2" goto notification
if "%1"=="notification" goto notification
if "%1"=="3" goto api
if "%1"=="api" goto api
if "%1"=="all" goto all
goto usage

:sse
echo 1️⃣  SSE 연결 테스트 실행...
docker run --rm ^
  --network onlyone-network ^
  -v "%cd%":/scripts ^
  -e BASE_URL=%BASE_URL% ^
  xk6-sse:local run ^
  --out "influxdb=%INFLUXDB_URL%" ^
  /scripts/1-sse-connection-test.js
goto end

:notification
echo 2️⃣  알림 생성 테스트 실행...
docker run --rm ^
  --network onlyone-network ^
  -v "%cd%":/scripts ^
  -e BASE_URL=%BASE_URL% ^
  xk6-sse:local run ^
  --out "influxdb=%INFLUXDB_URL%" ^
  /scripts/2-notification-create-test.js
goto end

:api
echo 3️⃣  API 조회 테스트 실행...
docker run --rm ^
  --network onlyone-network ^
  -v "%cd%":/scripts ^
  -e BASE_URL=%BASE_URL% ^
  xk6-sse:local run ^
  --out "influxdb=%INFLUXDB_URL%" ^
  /scripts/3-api-query-test.js
goto end

:all
echo 🔄 전체 테스트 순차 실행...
echo.
echo 1️⃣  SSE 연결 테스트...
call %0 sse
echo.
timeout /t 10 /nobreak
echo 2️⃣  알림 생성 테스트...
call %0 notification
echo.
timeout /t 10 /nobreak
echo 3️⃣  API 조회 테스트...
call %0 api
echo.
echo ✅ 전체 테스트 완료!
goto end

:usage
echo 사용법: %0 {1^|sse^|2^|notification^|3^|api^|all}
echo.
echo 개별 테스트:
echo   %0 1 또는 %0 sse          - SSE 연결 테스트만 실행
echo   %0 2 또는 %0 notification - 알림 생성 테스트만 실행
echo   %0 3 또는 %0 api          - API 조회 테스트만 실행
echo.
echo 전체 테스트:
echo   %0 all                    - 모든 테스트 순차 실행
echo.
exit /b 1

:end
echo.
echo ==========================================
echo 테스트 완료!
echo Grafana: http://localhost:3333
echo Jaeger: http://localhost:16686
echo ==========================================
