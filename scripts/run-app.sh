#!/bin/bash
# =============================================================
# 앱 서버 시작/재시작 스크립트
# 사용법: ./scripts/run-app.sh [build]
#   build 인자 없으면 기존 JAR로 재시작
#   build 인자 있으면 pull + 빌드 후 시작
# =============================================================
set -euo pipefail

GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok()   { echo -e "${GREEN}[OK]${NC} $1"; }

cd ~/OnlyOne-Back

# 환경변수 로드
if [ -f ~/.env-onlyone ]; then
    source ~/.env-onlyone
    log_ok "환경변수 로드 완료"
else
    echo "ERROR: ~/.env-onlyone 파일이 없습니다"
    exit 1
fi

# 빌드 모드
if [ "${1:-}" = "build" ]; then
    log_info "코드 pull + 빌드..."
    git pull origin feat/notification/haechang
    ./gradlew :onlyone-api:bootJar -x test --no-daemon
    log_ok "빌드 완료"
fi

JAR_PATH=$(find ~/OnlyOne-Back/onlyone-api/build/libs -name "*.jar" ! -name "*-plain.jar" | head -1)
if [ -z "$JAR_PATH" ]; then
    echo "ERROR: JAR 파일을 찾을 수 없습니다. 'build' 인자로 실행하세요."
    exit 1
fi

# 기존 프로세스 종료
if pgrep -f "java.*onlyone" > /dev/null 2>&1; then
    log_info "기존 앱 프로세스 종료..."
    pkill -f "java.*onlyone" || true
    sleep 3
fi

# 앱 시작
log_info "앱 시작: $JAR_PATH"
nohup java -Xmx4g -XX:+UseZGC \
    -jar "$JAR_PATH" \
    --spring.profiles.active=ec2 \
    > ~/app.log 2>&1 &

APP_PID=$!
log_ok "앱 시작됨 (PID: $APP_PID)"

# Health check 대기
log_info "Health check 대기 (최대 60초)..."
for i in $(seq 1 12); do
    sleep 5
    HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
        log_ok "앱 정상 기동 (Health: 200)"
        exit 0
    fi
    echo "  ... 대기 중 (${i}/12, HTTP: $HTTP_CODE)"
done

echo "WARNING: 60초 내 Health check 실패. 로그 확인: tail -f ~/app.log"
exit 1
