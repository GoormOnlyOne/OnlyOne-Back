#!/bin/bash
# =============================================================
# EC2 앱 전용 서버 부트스트랩 (c5.xlarge: 4 vCPU, 8GB)
# k6는 별도 서버에서 실행 (ec2-setup-k6.sh)
# =============================================================
# 사용법:
#   scp -i ~/.ssh/onlyone-loadtest.pem scripts/ec2-setup-app.sh ubuntu@<APP_PUBLIC_IP>:~/
#   ssh -i ~/.ssh/onlyone-loadtest.pem ubuntu@<APP_PUBLIC_IP>
#   chmod +x ec2-setup-app.sh
#   INFRA_HOST=<인프라 Private IP> ./ec2-setup-app.sh
# =============================================================

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()  { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok()    { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

INFRA_HOST="${INFRA_HOST:?INFRA_HOST 환경변수를 설정하세요 (인프라 서버 Private IP)}"
log_info "인프라 서버: $INFRA_HOST"

# ── 1. 시스템 설정 ──
log_info "=== 1. 시스템 설정 ==="

sudo apt-get update -y
sudo apt-get install -y ca-certificates curl gnupg lsb-release jq

# JDK 21
if ! java -version 2>&1 | grep -q "21"; then
    log_info "OpenJDK 21 설치..."
    sudo apt-get install -y openjdk-21-jdk
    log_ok "JDK 21 설치 완료"
else
    log_ok "JDK 21 이미 설치됨"
fi

# Swap (2GB)
if [ ! -f /swapfile ]; then
    log_info "2GB Swap 생성..."
    sudo fallocate -l 2G /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile
    echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
    log_ok "Swap 2GB 활성화"
else
    log_ok "Swap 이미 존재"
fi

# sysctl
log_info "sysctl 튜닝..."
sudo tee /etc/sysctl.d/99-loadtest.conf > /dev/null <<'EOF'
vm.swappiness=10
net.core.somaxconn=65535
net.ipv4.tcp_max_syn_backlog=65535
net.ipv4.ip_local_port_range=1024 65535
net.core.netdev_max_backlog=65535
fs.file-max=2097152
EOF
sudo sysctl --system > /dev/null 2>&1
log_ok "sysctl 적용 완료"

# ulimit
log_info "ulimit 설정..."
sudo tee /etc/security/limits.d/99-loadtest.conf > /dev/null <<'EOF'
* soft nofile 65535
* hard nofile 65535
* soft nproc 65535
* hard nproc 65535
EOF
ulimit -n 65535 2>/dev/null || true
log_ok "ulimit 설정 완료"

# ── 2. 프로젝트 클론 + 빌드 ──
log_info "=== 2. 프로젝트 클론 + 빌드 ==="

REPO_URL="${REPO_URL:-https://github.com/choigpt/OnlyOne-Back.git}"
BRANCH="${BRANCH:-feat/notification/haechang}"

if [ -d ~/OnlyOne-Back ]; then
    log_info "기존 저장소 업데이트..."
    cd ~/OnlyOne-Back
    git fetch origin
    git checkout "$BRANCH"
    git pull origin "$BRANCH"
else
    log_info "저장소 클론 ($BRANCH)..."
    git clone -b "$BRANCH" "$REPO_URL" ~/OnlyOne-Back
    cd ~/OnlyOne-Back
fi
log_ok "프로젝트 준비 완료"

log_info "Gradle 빌드 (bootJar)..."
./gradlew clean :onlyone-api:bootJar -x test --no-daemon
log_ok "빌드 완료"

JAR_PATH=$(find ~/OnlyOne-Back/onlyone-api/build/libs -name "*.jar" ! -name "*-plain.jar" | head -1)
log_ok "JAR: $JAR_PATH"

# ── 3. 인프라 연결 확인 ──
log_info "=== 3. 인프라 연결 확인 ==="

check_connection() {
    local name="$1"
    local host="$2"
    local port="$3"
    if timeout 5 bash -c "echo >/dev/tcp/$host/$port" 2>/dev/null; then
        log_ok "$name ($host:$port) 연결 OK"
        return 0
    else
        log_error "$name ($host:$port) 연결 실패"
        return 1
    fi
}

INFRA_OK=true
check_connection "MySQL"         "$INFRA_HOST" 3306  || INFRA_OK=false
check_connection "Redis"         "$INFRA_HOST" 6379  || INFRA_OK=false
check_connection "Elasticsearch" "$INFRA_HOST" 9200  || INFRA_OK=false
check_connection "Kafka"         "$INFRA_HOST" 29092 || INFRA_OK=false

if [ "$INFRA_OK" = false ]; then
    log_error "일부 인프라 연결 실패. 인프라 서버 상태를 확인하세요."
    log_info "인프라 서버 SSH: ec2-setup-infra.sh 실행 후 docker compose ps 확인"
    exit 1
fi

echo ""

# ── 4. 앱 시작 방법 안내 ──
echo "============================================"
echo "   앱 서버 설정 완료!"
echo "============================================"
echo ""
echo "  인프라 서버: $INFRA_HOST"
echo "  JAR: $JAR_PATH"
echo ""
echo "  === 앱 시작 명령어 ==="
echo ""
echo "  export INFRA_HOST=$INFRA_HOST"
echo "  cd ~/OnlyOne-Back"
echo "  nohup java --enable-preview \\"
echo "    -Xms512m -Xmx2g -XX:+UseZGC \\"
echo "    -Dspring.profiles.active=ec2,loadtest \\"
echo "    -jar $JAR_PATH \\"
echo "    > app.log 2>&1 &"
echo ""
echo "  === 앱 상태 확인 ==="
echo ""
echo "  tail -f app.log"
echo "  curl http://localhost:8080/actuator/health"
echo ""
echo "  === 시딩 & 테스트는 k6 서버에서 실행 ==="
echo "  ec2-setup-k6.sh 참고"
echo ""
