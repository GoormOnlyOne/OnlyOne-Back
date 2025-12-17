@echo off
setlocal enabledelayedexpansion

echo ===== Nginx 부하 분산 분석 =====
echo.

echo 📊 최근 1000개의 요청 분석 중...
echo.

REM Docker 로그 가져오기
docker logs onlyone-nginx 2>&1 | findstr "upstream:" > %TEMP%\nginx_analysis.log

if not exist %TEMP%\nginx_analysis.log (
    echo ❌ Nginx 로그를 찾을 수 없습니다.
    echo    먼저 'docker-compose --profile scale up -d' 명령으로 서비스를 시작하세요.
    exit /b 1
)

REM 파일 크기 확인
for %%A in (%TEMP%\nginx_analysis.log) do set size=%%~zA
if %size% equ 0 (
    echo ❌ 분석할 요청이 없습니다.
    echo    부하 테스트를 실행하세요: docker-compose --profile load-test up k6
    del %TEMP%\nginx_analysis.log
    exit /b 1
)

echo 📈 Upstream 서버별 요청 분산:
echo.

REM 각 서버로의 요청 수 계산
set app1_count=0
set app2_count=0
set app3_count=0

for /f "delims=" %%i in ('findstr /c:"app1:8080" %TEMP%\nginx_analysis.log ^| find /c /v ""') do set app1_count=%%i
for /f "delims=" %%i in ('findstr /c:"app2:8080" %TEMP%\nginx_analysis.log ^| find /c /v ""') do set app2_count=%%i
for /f "delims=" %%i in ('findstr /c:"app3:8080" %TEMP%\nginx_analysis.log ^| find /c /v ""') do set app3_count=%%i

set /a total=app1_count+app2_count+app3_count

if %total% equ 0 (
    echo ❌ 분석할 요청이 없습니다.
    echo    부하 테스트를 실행하세요: docker-compose --profile load-test up k6
    del %TEMP%\nginx_analysis.log
    exit /b 1
)

echo   app1: %app1_count% 요청
echo   app2: %app2_count% 요청
echo   app3: %app3_count% 요청
echo   총합: %total% 요청
echo.

REM 상태 코드 확인
echo 📋 최근 요청 샘플 (마지막 10개):
echo.
powershell -Command "Get-Content '%TEMP%\nginx_analysis.log' | Select-Object -Last 10 | ForEach-Object { if ($_ -match 'upstream: ([^ ]+)') { $server = $matches[1]; if ($_ -match '\"(GET|POST|PUT|DELETE) ([^ ]+)') { $method = $matches[1]; $path = $matches[2]; Write-Host \"  $method $path -> $server\" } } }"
echo.

echo ✅ 분산 균형도 평가:
echo.

REM 간단한 균형도 평가 (각 서버가 최소 20%% 이상 요청을 받았는지)
set /a min_threshold=total*20/100
if %app1_count% geq %min_threshold% if %app2_count% geq %min_threshold% if %app3_count% geq %min_threshold% (
    echo   🟢 양호 - 모든 서버가 적절히 요청을 분산받고 있습니다.
) else (
    echo   🟡 주의 - 일부 서버의 요청이 적습니다.
    echo   ⚠️  ip_hash 알고리즘은 클라이언트 IP 기반으로 분산하므로
    echo       클라이언트가 적을 경우 불균형할 수 있습니다.
)

echo.

REM 정리
del %TEMP%\nginx_analysis.log

echo ===== 분석 완료 =====
