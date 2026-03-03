#!/bin/bash
# =============================================================
# EC2 인프라 서버 부트스트랩 (c5.2xlarge: 8 vCPU, 16GB)
# =============================================================
# 사용법:
#   scp -i ~/.ssh/onlyone-loadtest.pem scripts/ec2-setup-infra.sh ubuntu@<INFRA_PUBLIC_IP>:~/
#   ssh -i ~/.ssh/onlyone-loadtest.pem ubuntu@<INFRA_PUBLIC_IP>
#   chmod +x ec2-setup-infra.sh
#   APP_PRIVATE_IP=<앱 서버 Private IP> ./ec2-setup-infra.sh
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

INFRA_PRIVATE_IP=$(hostname -I | awk '{print $1}')
log_info "인프라 서버 Private IP: $INFRA_PRIVATE_IP"

# ── 1. 시스템 설정 ──
log_info "=== 1. 시스템 설정 ==="

sudo apt-get update -y
sudo apt-get install -y ca-certificates curl gnupg lsb-release jq

# Docker CE
if ! command -v docker &>/dev/null; then
    log_info "Docker CE 설치..."
    sudo install -m 0755 -d /etc/apt/keyrings
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
    sudo chmod a+r /etc/apt/keyrings/docker.gpg
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | \
        sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
    sudo apt-get update -y
    sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
    sudo usermod -aG docker "$USER"
    log_ok "Docker CE 설치 완료"
else
    log_ok "Docker 이미 설치됨: $(docker --version)"
fi

# Swap (4GB)
if [ ! -f /swapfile ]; then
    log_info "4GB Swap 생성..."
    sudo fallocate -l 4G /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile
    echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
    log_ok "Swap 4GB 활성화"
else
    log_ok "Swap 이미 존재"
fi

# sysctl 튜닝
log_info "sysctl 튜닝..."
sudo tee /etc/sysctl.d/99-loadtest.conf > /dev/null <<'EOF'
vm.swappiness=10
vm.overcommit_memory=1
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

# ── 2. 프로젝트 클론 ──
log_info "=== 2. 프로젝트 클론 ==="

REPO_URL="${REPO_URL:-https://github.com/JoHB94/OnlyOne-Back.git}"
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
log_ok "프로젝트 준비 완료: ~/OnlyOne-Back"

# ── 3. 인프라 시작 ──
log_info "=== 3. Docker 인프라 시작 ==="

cd ~/OnlyOne-Back

# .env 파일 생성
cat > .env <<EOF
INFRA_PRIVATE_IP=${INFRA_PRIVATE_IP}
DB_PASSWORD=root
ELASTICSEARCH_PASSWORD=changeme
EOF
log_ok ".env 파일 생성 (INFRA_PRIVATE_IP=$INFRA_PRIVATE_IP)"

# Prometheus EC2 설정: APP_PRIVATE_IP 치환
APP_PRIVATE_IP="${APP_PRIVATE_IP:-$INFRA_PRIVATE_IP}"
if [ -f monitoring/prometheus/prometheus-ec2.yml ]; then
    sed -i "s/__APP_PRIVATE_IP__/$APP_PRIVATE_IP/g" monitoring/prometheus/prometheus-ec2.yml
    log_ok "Prometheus config: APP_PRIVATE_IP=$APP_PRIVATE_IP"
fi

# newgrp docker를 사용하지 않고 sudo로 실행 (첫 실행 시 그룹 반영 안됨)
if groups | grep -q docker; then
    docker compose -f docker-compose-ec2-infra.yml up -d
else
    log_warn "docker 그룹 미반영 — sudo로 실행합니다."
    sudo docker compose -f docker-compose-ec2-infra.yml up -d
fi
log_ok "Docker Compose 시작됨"

# ── 4. 헬스체크 대기 ──
log_info "=== 4. 헬스체크 대기 ==="

DOCKER_CMD="docker"
if ! groups | grep -q docker; then
    DOCKER_CMD="sudo docker"
fi

wait_for_service() {
    local name="$1"
    local container="$2"
    local max_wait="${3:-120}"
    local elapsed=0

    while [ $elapsed -lt $max_wait ]; do
        if $DOCKER_CMD inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null | grep -q "healthy"; then
            log_ok "$name healthy"
            return 0
        fi
        sleep 5
        elapsed=$((elapsed + 5))
        echo -ne "  $name 대기 중... ${elapsed}s / ${max_wait}s\r"
    done

    log_error "$name 헬스체크 타임아웃 (${max_wait}s)"
    return 1
}

wait_for_service "MySQL"         "onlyone-mysql"         120
wait_for_service "Redis"         "onlyone-redis"         60
wait_for_service "Elasticsearch" "onlyone-elasticsearch" 120
wait_for_service "MongoDB"       "onlyone-mongodb"       60
wait_for_service "Kafka"         "onlyone-kafka"         90
wait_for_service "RabbitMQ"      "onlyone-rabbitmq"      60

echo ""

# ── 5. ES nori 플러그인 ──
log_info "=== 5. Elasticsearch nori 플러그인 ==="

NORI_INSTALLED=$($DOCKER_CMD exec onlyone-elasticsearch elasticsearch-plugin list 2>/dev/null | grep -c "analysis-nori" || true)
if [ "$NORI_INSTALLED" -eq 0 ]; then
    log_info "nori 플러그인 설치 중..."
    $DOCKER_CMD exec onlyone-elasticsearch elasticsearch-plugin install analysis-nori -b
    log_info "Elasticsearch 재시작..."
    $DOCKER_CMD restart onlyone-elasticsearch
    sleep 20
    wait_for_service "Elasticsearch" "onlyone-elasticsearch" 120
    log_ok "nori 플러그인 설치 완료"
else
    log_ok "nori 플러그인 이미 설치됨"
fi

# ── 완료 ──
echo ""
echo "============================================"
echo "   인프라 서버 설정 완료!"
echo "============================================"
echo ""
echo "  Private IP: $INFRA_PRIVATE_IP"
echo "  MySQL:      $INFRA_PRIVATE_IP:3306"
echo "  Redis:      $INFRA_PRIVATE_IP:6379"
echo "  MongoDB:    $INFRA_PRIVATE_IP:27017"
echo "  ES:         $INFRA_PRIVATE_IP:9200"
echo "  Kafka:      $INFRA_PRIVATE_IP:29092"
echo "  Grafana:    http://<PUBLIC_IP>:3000 (admin/admin)"
echo "  Prometheus: http://<PUBLIC_IP>:9090"
echo ""
echo "  다음 단계: 앱 서버에서 ec2-setup-app.sh 실행"
echo "    INFRA_HOST=$INFRA_PRIVATE_IP 를 앱 서버에 전달하세요."
echo ""
