@echo off
chcp 65001 >nul

:: .env 파일에서 환경변수 로드
for /f "usebackq tokens=1,2 delims==" %%a in (".env") do (
    if not "%%a"=="" if not "%%a:~0,1%"=="#" set "%%a=%%b"
)

echo === OnlyOne API Build ===
call gradlew.bat :onlyone-api:bootJar -x test
if %errorlevel% neq 0 (
    echo Build failed!
    exit /b 1
)

echo.
echo === OnlyOne API Start (local-perf) ===
echo JVM: ZGC, 1g-1.5g heap, enable-preview
echo Profile: local-perf
echo.

java --enable-preview ^
    -Xms1g -Xmx1536m ^
    -XX:+UseZGC ^
    -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./heapdump.hprof ^
    -Duser.timezone=Asia/Seoul ^
    -Dspring.profiles.active=local-perf ^
    -DJWT_SECRET=%JWT_SECRET% ^
    -DKAKAO_CLIENT_ID=%KAKAO_CLIENT_ID% ^
    -DTOSS_CLIENT_KEY=%TOSS_CLIENT_KEY% ^
    -DTOSS_SECRET_API_KEY=%TOSS_SECRET_API_KEY% ^
    -DTOSS_SECURITY_KEY=%TOSS_SECURITY_KEY% ^
    -DAWS_ACCESS_KEY_ID=%AWS_ACCESS_KEY_ID% ^
    -DAWS_SECRET_ACCESS_KEY=%AWS_SECRET_ACCESS_KEY% ^
    -jar onlyone-api/build/libs/onlyone-api-0.0.1-SNAPSHOT.jar
