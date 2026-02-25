@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

echo ============================================
echo   알림/SSE 상세 부하 테스트 (개별 실행)
echo ============================================
echo.
echo 사용법: k6-notification-advanced.bat [테스트명]
echo.
echo 테스트 목록:
echo   delivery   - SSE 알림 전달 검증 (~10분, 200 VU)
echo   reconnect  - SSE 재연결 복구 검증 (~13분, 200 VU)
echo   lifecycle  - SSE 연결 수명주기 (~15분, 1000 VU)
echo   batch      - 배치 프로세서 포화 (~18분, 500 VU)
echo   conflict   - 동시 쓰기 충돌 (~12분, 100 VU)
echo   mixed      - 운영 시뮬레이션 (~10분, 500 VU)
echo   limit      - SSE 연결 한계 (~19분, 7500 VU)
echo.

set TEST=%1
if "%TEST%"=="" (
    echo 테스트를 선택하세요.
    echo 예: k6-notification-advanced.bat delivery
    exit /b 1
)

if not exist k6-results mkdir k6-results

:: 테스트명 → 파일명 및 시드 매핑
set FILE=
set SEED=

if "%TEST%"=="delivery" (
    set FILE=sse-delivery-verification-test.js
    set SEED=seed-delivery-test.sql
)
if "%TEST%"=="reconnect" (
    set FILE=sse-reconnection-recovery-test.js
    set SEED=seed-delivery-test.sql
)
if "%TEST%"=="lifecycle" (
    set FILE=sse-connection-lifecycle-test.js
    set SEED=
)
if "%TEST%"=="batch" (
    set FILE=sse-batch-saturation-test.js
    set SEED=seed-batch-saturation.sql
)
if "%TEST%"=="conflict" (
    set FILE=notification-concurrent-conflict-test.js
    set SEED=seed-conflict-test.sql
)
if "%TEST%"=="mixed" (
    set FILE=notification-mixed-user-behavior-test.js
    set SEED=
)
if "%TEST%"=="limit" (
    set FILE=sse-connection-limit-test.js
    set SEED=
)

if "%FILE%"=="" (
    echo 알 수 없는 테스트: %TEST%
    echo.
    echo 사용 가능한 테스트: delivery, reconnect, lifecycle, batch, conflict, mixed, limit
    exit /b 1
)

:: 시드 데이터 실행 (필요한 경우)
if not "%SEED%"=="" (
    echo.
    echo [시드] %SEED% 실행 중...
    docker exec -i onlyone-mysql mysql -uroot -proot onlyone < k6-tests\%SEED%
    if errorlevel 1 (
        echo [시드] 경고: 시드 실행 실패. 계속 진행합니다.
    ) else (
        echo [시드] 완료
    )
    echo.
)

echo ============================================
echo [실행] %TEST% - %FILE%
echo [결과] k6-results/%TEST%-results.json
echo ============================================
echo.

docker run --rm ^
    --network onlyone-back_onlyone-network ^
    -v "%cd%":/k6 ^
    -e BASE_URL=http://app:8080 ^
    -e JWT_SECRET=7e9eeb12d176a2d72f554c6b096522b4e1a34d799727e45a96f192bbff2a2a851ede29ed24b10b6e6b1835ac94380e2469df99ff9713477bf4d43eeaa9cd16a3 ^
    grafana/k6 run ^
    --out json=/k6/k6-results/%TEST%-results.json ^
    /k6/k6-tests/%FILE%

echo.
echo ============================================
echo [완료] %TEST% 테스트 종료
echo [결과] k6-results/%TEST%-results.json
echo ============================================
echo.
echo 다음 단계:
echo   1. k6-results/%TEST%-results.json 확인
echo   2. Grafana 대시보드에서 해당 시간 범위 스냅샷
echo   3. 서버 에러 로그 확인
echo.

endlocal
