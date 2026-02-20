@echo off
chcp 65001 >nul

echo ============================================
echo   알림 모듈 k6 부하 테스트
echo ============================================
echo.
echo 사용법: k6-notification.bat [테스트명]
echo.
echo 테스트 목록:
echo   list     - 알림 목록 조회 (~6분 30초)
echo   unread   - 읽지 않은 수 조회 (~7분)
echo   read     - 읽음 처리 (~7분)
echo   delete   - 알림 삭제 (~5분)
echo   all      - 전체 순차 실행
echo   light    - 기존 통합 테스트 (~24분)
echo.

set TEST=%1
if "%TEST%"=="" (
    echo 테스트를 선택하세요.
    echo 예: k6-notification.bat list
    exit /b 1
)

if not exist k6-results mkdir k6-results

:: 테스트명 → 파일명 매핑
if "%TEST%"=="list"   set FILE=notification-list-test.js
if "%TEST%"=="unread" set FILE=notification-unread-test.js
if "%TEST%"=="read"   set FILE=notification-read-test.js
if "%TEST%"=="delete" set FILE=notification-delete-test.js
if "%TEST%"=="light"  set FILE=notification-load-test-light.js
if "%TEST%"=="all"    goto :run_all

if "%FILE%"=="" (
    echo 알 수 없는 테스트: %TEST%
    exit /b 1
)

:: 쓰기 테스트는 시드 리셋 실행
if "%TEST%"=="read"   call :seed_reset
if "%TEST%"=="delete" call :seed_reset

echo.
echo [실행] %FILE%
echo ============================================
docker run --rm --network onlyone-back_onlyone-network -v "%cd%":/k6 -e BASE_URL=http://app:8080 -e JWT_SECRET=7e9eeb12d176a2d72f554c6b096522b4e1a34d799727e45a96f192bbff2a2a851ede29ed24b10b6e6b1835ac94380e2469df99ff9713477bf4d43eeaa9cd16a3 grafana/k6 run --out json=/k6/k6-results/%TEST%-results.json /k6/k6-tests/%FILE%
goto :eof

:run_all
echo [전체 테스트 순차 실행]
echo.

for %%T in (list unread read delete) do (
    echo ============================================
    echo [%%T] 시작
    echo ============================================

    if "%%T"=="read" call :seed_reset
    if "%%T"=="delete" call :seed_reset

    if "%%T"=="list"   set F=notification-list-test.js
    if "%%T"=="unread" set F=notification-unread-test.js
    if "%%T"=="read"   set F=notification-read-test.js
    if "%%T"=="delete" set F=notification-delete-test.js

    docker run --rm --network onlyone-back_onlyone-network -v "%cd%":/k6 -e BASE_URL=http://app:8080 -e JWT_SECRET=7e9eeb12d176a2d72f554c6b096522b4e1a34d799727e45a96f192bbff2a2a851ede29ed24b10b6e6b1835ac94380e2469df99ff9713477bf4d43eeaa9cd16a3 grafana/k6 run --out json=/k6/k6-results/%%T-results.json /k6/k6-tests/!F!

    echo [%%T] 완료
    echo.
)
echo ============================================
echo 전체 테스트 완료!
goto :eof

:seed_reset
echo [시드] 테스트 데이터 리셋 중...
docker exec -i onlyone-mysql mysql -uroot -proot onlyone < k6-tests\seed-reset.sql
echo [시드] 리셋 완료
goto :eof
