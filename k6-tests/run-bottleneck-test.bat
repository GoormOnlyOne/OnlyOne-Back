@echo off
chcp 65001 >nul 2>&1
setlocal

echo ============================================
echo   알림/SSE 병목점 탐지 부하 테스트
echo ============================================
echo.

:: 설정
set BASE_URL=http://localhost:8888
set JWT_SECRET=7e9eeb12d176a2d72f554c6b096522b4e1a34d799727e45a96f192bbff2a2a851ede29ed24b10b6e6b1835ac94380e2469df99ff9713477bf4d43eeaa9cd16a3

:: 결과 디렉터리
if not exist "..\k6-results" mkdir "..\k6-results"

echo [1/3] 서버 헬스체크...
curl -s -o /dev/null -w "%%{http_code}" %BASE_URL%/actuator/health | findstr "200" >nul 2>&1
if errorlevel 1 (
    echo   서버가 응답하지 않습니다: %BASE_URL%
    echo   서버를 먼저 실행해주세요.
    pause
    exit /b 1
)
echo   서버 정상

echo.
echo [2/3] 시드 데이터 확인...
echo   기존 seed-batch-saturation.sql (유저 1~1000) 시드를 사용합니다.
echo   시드가 없으면: mysql -u root -p onlyone ^< k6-tests\seed-batch-saturation.sql
echo.
echo   [TIP] MySQL 병목 모니터링을 별도 세션에서 실행하세요:
echo     mysql -u root -p onlyone ^< k6-tests\monitor-mysql-bottleneck.sql
echo.

echo [3/3] 부하 테스트 시작 (약 9분 소요)
echo   BASE_URL=%BASE_URL%
echo.

k6 run ^
  -e BASE_URL=%BASE_URL% ^
  -e JWT_SECRET=%JWT_SECRET% ^
  bottleneck-test.js

echo.
echo ============================================
echo   테스트 완료
echo   결과: k6-results\bottleneck-result.json
echo ============================================
pause
