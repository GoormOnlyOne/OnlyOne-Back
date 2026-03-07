#!/bin/bash
# =============================================================
# EC2 k6 전용 서버 부트스트랩 (c5.xlarge: 4 vCPU, 8GB)
# =============================================================
# 사용법:
#   INFRA_HOST=<인프라 Private IP> APP_HOST=<앱 Private IP> ./ec2-setup-k6.sh
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
APP_HOST="${APP_HOST:?APP_HOST 환경변수를 설정하세요 (앱 서버 Private IP)}"

log_info "인프라 서버: $INFRA_HOST"
log_info "앱 서버: $APP_HOST"

# ── 1. 시스템 설정 ──
log_info "=== 1. 시스템 설정 ==="

sudo apt-get update -y
sudo apt-get install -y ca-certificates curl gnupg lsb-release jq mysql-client

# k6 네이티브 설치
if ! command -v k6 &>/dev/null; then
    log_info "k6 설치..."
    sudo gpg -k
    sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \
        --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
    echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | \
        sudo tee /etc/apt/sources.list.d/k6.list
    sudo apt-get update -y
    sudo apt-get install -y k6
    log_ok "k6 설치 완료: $(k6 version)"
else
    log_ok "k6 이미 설치됨: $(k6 version)"
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
sudo tee /etc/security/limits.d/99-loadtest.conf > /dev/null <<'EOF'
* soft nofile 65535
* hard nofile 65535
* soft nproc 65535
* hard nproc 65535
EOF
ulimit -n 65535 2>/dev/null || true
log_ok "ulimit 설정 완료"

# ── 2. 프로젝트 클론 (k6 스크립트 + 시드 데이터용) ──
log_info "=== 2. 프로젝트 클론 ==="

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

# ── 3. 연결 확인 ──
log_info "=== 3. 연결 확인 ==="

check_connection() {
    local name="$1"
    local host="$2"
    local port="$3"
    if timeout 5 bash -c "echo >/dev/tcp/$host/$port" 2>/dev/null; then
        log_ok "$name ($host:$port)"
        return 0
    else
        log_error "$name ($host:$port) 연결 실패"
        return 1
    fi
}

check_connection "앱 서버"       "$APP_HOST"   8080 || log_warn "앱 서버 아직 미실행 — 나중에 시작하세요"
check_connection "MySQL"         "$INFRA_HOST" 3306  || log_warn "MySQL 연결 실패 — 인프라 서버 확인 필요"
check_connection "Redis"         "$INFRA_HOST" 6379  || log_warn "Redis 연결 실패 — 인프라 서버 확인 필요"
check_connection "Elasticsearch" "$INFRA_HOST" 9200  || log_warn "Elasticsearch 연결 실패 — 선택적 서비스"

echo ""
echo "============================================"
echo "   k6 서버 설정 완료!"
echo "============================================"
echo ""
echo "  인프라: $INFRA_HOST"
echo "  앱:     $APP_HOST"
echo ""
echo "  === 시딩 ==="
echo "  INFRA_HOST=$INFRA_HOST BASE_URL=http://$APP_HOST:8080 ./scripts/ec2-seed-data.sh"
echo ""
echo "  === 부하 테스트 ==="
echo "  INFRA_HOST=$INFRA_HOST BASE_URL=http://$APP_HOST:8080 ./scripts/ec2-loadtest.sh each"
echo ""
echo "  === 결과 수집 ==="
echo "  INFRA_HOST=$INFRA_HOST ./scripts/ec2-collect-results.sh"
echo ""
