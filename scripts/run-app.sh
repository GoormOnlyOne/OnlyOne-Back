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
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok()   { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

cd ~/OnlyOne-Back

# 환경변수 로드
if [ -f ~/.env-onlyone ]; then
    source ~/.env-onlyone
    log_ok "환경변수 로드 완료"
else
    echo "ERROR: ~/.env-onlyone 파일이 없습니다"
    exit 1
fi

# ── 커널/네트워크 튜닝 (재부팅 시 초기화되므로 매 시작 시 적용) ──
log_info "커널/네트워크 튜닝 적용..."
sudo sysctl -w net.core.rmem_max=16777216 > /dev/null 2>&1 || true
sudo sysctl -w net.core.wmem_max=16777216 > /dev/null 2>&1 || true
sudo sysctl -w net.ipv4.tcp_keepalive_time=60 > /dev/null 2>&1 || true
sudo sysctl -w net.ipv4.tcp_slow_start_after_idle=0 > /dev/null 2>&1 || true
sudo sysctl -w net.ipv4.tcp_fin_timeout=15 > /dev/null 2>&1 || true
sudo sysctl -w net.core.somaxconn=65535 > /dev/null 2>&1 || true
sudo sysctl -w net.ipv4.tcp_max_syn_backlog=65535 > /dev/null 2>&1 || true
sudo sysctl -w net.core.netdev_max_backlog=65535 > /dev/null 2>&1 || true
log_ok "커널 튜닝 적용 완료"

# 빌드 모드
if [ "${1:-}" = "build" ]; then
    log_info "코드 pull + 빌드..."
    git pull origin feat/notification/haechang
    ./gradlew :onlyone-api:bootJar -x test --no-daemon
    log_ok "빌드 완료"
fi

JAR_PATH=$(find ~/OnlyOne-Back/onlyone-api/build/libs -name "*.jar" ! -name "*-plain.jar" 2>/dev/null | head -1)
# app.jar 심볼릭 링크도 확인
if [ -z "$JAR_PATH" ] && [ -f ~/app.jar ]; then
    JAR_PATH=~/app.jar
fi
if [ -z "$JAR_PATH" ]; then
    echo "ERROR: JAR 파일을 찾을 수 없습니다. 'build' 인자로 실행하세요."
    exit 1
fi

# 기존 프로세스 종료
if ps aux | grep -v grep | grep -q "java.*onlyone\|java.*app.jar"; then
    log_info "기존 앱 프로세스 종료..."
    kill $(ps aux | grep -E "java.*(onlyone|app\.jar)" | grep -v grep | awk '{print $2}') 2>/dev/null || true
    sleep 3
    # 강제 종료
    kill -9 $(ps aux | grep -E "java.*(onlyone|app\.jar)" | grep -v grep | awk '{print $2}') 2>/dev/null || true
    sleep 1
fi

# ── 진단 디렉토리 생성 ──
DIAG_DIR=~/diagnostics
mkdir -p "$DIAG_DIR"/{threaddumps,heapdumps,gclog,jfr,tcpdump}

# ── JVM 옵션 구성 ──
JVM_OPTS=(
    # 메모리
    -Xms3g -Xmx3g
    -XX:MaxDirectMemorySize=1g
    -XX:+AlwaysPreTouch

    # GC (ZGC Generational)
    -XX:+UseZGC -XX:+ZGenerational

    # Virtual Threads
    -Djdk.virtualThreadScheduler.parallelism=8

    # GC 로그 — 로테이션 포함
    "-Xlog:gc*,gc+phases=debug:file=${DIAG_DIR}/gclog/gc_%t.log:time,uptime,level,tags:filecount=10,filesize=50m"

    # 힙 덤프 — OOM 시 자동 생성
    -XX:+HeapDumpOnOutOfMemoryError
    "-XX:HeapDumpPath=${DIAG_DIR}/heapdumps/"

    # JFR — 항상 켜짐 (오버헤드 <2%), 수동 dump 가능
    -XX:StartFlightRecording=name=continuous,settings=default,maxsize=500m,maxage=1h,dumponexit=true,filename=${DIAG_DIR}/jfr/exit_recording.jfr

    # JMX (jcmd/jstack 원격 접근용)
    -Dcom.sun.management.jmxremote
    -Dcom.sun.management.jmxremote.port=9010
    -Dcom.sun.management.jmxremote.authenticate=false
    -Dcom.sun.management.jmxremote.ssl=false
)

# 앱 시작
log_info "앱 시작: $JAR_PATH"
log_info "JVM: Xms3g Xmx3g ZGC GC-log JFR HeapDump"
log_info "진단 디렉토리: $DIAG_DIR"

nohup java "${JVM_OPTS[@]}" \
    -jar "$JAR_PATH" \
    --spring.profiles.active=ec2 \
    > ~/app.log 2>&1 &

APP_PID=$!
log_ok "앱 시작됨 (PID: $APP_PID)"

# PID 기록 (진단 스크립트에서 참조)
echo "$APP_PID" > ~/app.pid

# Health check 대기
log_info "Health check 대기 (최대 90초)..."
for i in $(seq 1 18); do
    sleep 5
    HTTP_CODE=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
        log_ok "앱 정상 기동 (Health: 200, PID: $APP_PID)"
        echo ""
        echo "  === 진단 명령어 ==="
        echo "  스레드 덤프:  jstack $APP_PID > $DIAG_DIR/threaddumps/td_\$(date +%H%M%S).txt"
        echo "  힙 덤프:     jmap -dump:format=b,file=$DIAG_DIR/heapdumps/heap_\$(date +%H%M%S).hprof $APP_PID"
        echo "  JFR 덤프:    jcmd $APP_PID JFR.dump name=continuous filename=$DIAG_DIR/jfr/dump_\$(date +%H%M%S).jfr"
        echo "  GC 로그:     ls $DIAG_DIR/gclog/"
        echo "  tcpdump:     sudo tcpdump -i eth0 -w $DIAG_DIR/tcpdump/capture.pcap -c 50000 port 8080"
        echo ""
        exit 0
    fi
    echo "  ... 대기 중 (${i}/18, HTTP: $HTTP_CODE)"
done

echo "WARNING: 90초 내 Health check 실패. 로그 확인: tail -f ~/app.log"
exit 1
