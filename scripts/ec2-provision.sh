#!/bin/bash
# =============================================================
# AWS CLI로 EC2 부하 테스트 인프라 프로비저닝
# =============================================================
# 사전조건: aws configure 완료 (ap-northeast-2)
#
# 사용법:
#   ./scripts/ec2-provision.sh          # 생성
#   ./scripts/ec2-provision.sh teardown  # 전체 삭제
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

REGION="${AWS_REGION:-ap-northeast-2}"
KEY_NAME="onlyone-loadtest"
SG_NAME="sg-onlyone-loadtest"
AMI_ID=""  # Ubuntu 22.04 — 아래에서 자동 조회

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STATE_FILE="$SCRIPT_DIR/.ec2-provision-state"

ACTION="${1:-create}"

# ── Teardown ──
if [ "$ACTION" = "teardown" ] || [ "$ACTION" = "destroy" ]; then
    log_info "=== EC2 리소스 정리 ==="

    if [ ! -f "$STATE_FILE" ]; then
        log_error "상태 파일 없음: $STATE_FILE"
        exit 1
    fi
    source "$STATE_FILE"

    # 인스턴스 종료
    if [ -n "${INFRA_INSTANCE_ID:-}" ]; then
        log_info "인프라 인스턴스 종료: $INFRA_INSTANCE_ID"
        aws ec2 terminate-instances --region "$REGION" --instance-ids "$INFRA_INSTANCE_ID" > /dev/null 2>&1 || true
    fi
    if [ -n "${APP_INSTANCE_ID:-}" ]; then
        log_info "앱 인스턴스 종료: $APP_INSTANCE_ID"
        aws ec2 terminate-instances --region "$REGION" --instance-ids "$APP_INSTANCE_ID" > /dev/null 2>&1 || true
    fi

    # 인스턴스 종료 대기
    if [ -n "${INFRA_INSTANCE_ID:-}" ] || [ -n "${APP_INSTANCE_ID:-}" ]; then
        log_info "인스턴스 종료 대기..."
        INSTANCE_IDS=""
        [ -n "${INFRA_INSTANCE_ID:-}" ] && INSTANCE_IDS="$INFRA_INSTANCE_ID"
        [ -n "${APP_INSTANCE_ID:-}" ] && INSTANCE_IDS="$INSTANCE_IDS $APP_INSTANCE_ID"
        aws ec2 wait instance-terminated --region "$REGION" --instance-ids $INSTANCE_IDS 2>/dev/null || true
        log_ok "인스턴스 종료 완료"
    fi

    # Security Group 삭제
    if [ -n "${SG_ID:-}" ]; then
        log_info "Security Group 삭제: $SG_ID"
        aws ec2 delete-security-group --region "$REGION" --group-id "$SG_ID" 2>/dev/null || log_warn "SG 삭제 실패 (이미 삭제됨?)"
    fi

    # Key Pair 삭제
    log_info "Key Pair 삭제: $KEY_NAME"
    aws ec2 delete-key-pair --region "$REGION" --key-name "$KEY_NAME" 2>/dev/null || true
    rm -f "$SCRIPT_DIR/${KEY_NAME}.pem"

    rm -f "$STATE_FILE"
    log_ok "전체 정리 완료!"
    exit 0
fi

# ── Create ──
log_info "=== EC2 부하 테스트 환경 프로비저닝 ==="
log_info "리전: $REGION"
echo ""

# AWS CLI 확인
if ! command -v aws &>/dev/null; then
    log_error "AWS CLI가 설치되어 있지 않습니다."
    log_info "설치: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html"
    exit 1
fi

# 자격증명 확인
if ! aws sts get-caller-identity --region "$REGION" &>/dev/null; then
    log_error "AWS 자격증명이 설정되지 않았습니다. aws configure를 실행하세요."
    exit 1
fi
ACCOUNT_ID=$(aws sts get-caller-identity --region "$REGION" --query 'Account' --output text)
log_ok "AWS 계정: $ACCOUNT_ID"

# ── 1. Ubuntu 22.04 AMI 조회 ──
log_info "=== 1. Ubuntu 22.04 AMI 조회 ==="

AMI_ID=$(aws ec2 describe-images --region "$REGION" \
    --owners 099720109477 \
    --filters "Name=name,Values=ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*" \
              "Name=state,Values=available" \
    --query 'sort_by(Images, &CreationDate)[-1].ImageId' \
    --output text)

if [ -z "$AMI_ID" ] || [ "$AMI_ID" = "None" ]; then
    log_error "Ubuntu 22.04 AMI를 찾을 수 없습니다."
    exit 1
fi
log_ok "AMI: $AMI_ID"

# ── 2. Key Pair 생성 ──
log_info "=== 2. Key Pair 생성 ==="

KEY_FILE="$SCRIPT_DIR/${KEY_NAME}.pem"

if aws ec2 describe-key-pairs --region "$REGION" --key-names "$KEY_NAME" &>/dev/null; then
    log_warn "Key Pair '$KEY_NAME' 이미 존재 — 재사용"
else
    aws ec2 create-key-pair --region "$REGION" \
        --key-name "$KEY_NAME" \
        --key-type rsa \
        --query 'KeyMaterial' \
        --output text > "$KEY_FILE"
    chmod 400 "$KEY_FILE"
    log_ok "Key Pair 생성 → $KEY_FILE"
fi

# ── 3. VPC / 서브넷 조회 ──
log_info "=== 3. VPC / 서브넷 조회 ==="

VPC_ID=$(aws ec2 describe-vpcs --region "$REGION" \
    --filters "Name=isDefault,Values=true" \
    --query 'Vpcs[0].VpcId' --output text)

if [ -z "$VPC_ID" ] || [ "$VPC_ID" = "None" ]; then
    log_error "Default VPC가 없습니다."
    exit 1
fi
log_ok "VPC: $VPC_ID"

SUBNET_ID=$(aws ec2 describe-subnets --region "$REGION" \
    --filters "Name=vpc-id,Values=$VPC_ID" "Name=default-for-az,Values=true" \
    --query 'Subnets[0].SubnetId' --output text)
log_ok "서브넷: $SUBNET_ID"

# ── 4. Security Group 생성 ──
log_info "=== 4. Security Group 생성 ==="

SG_ID=$(aws ec2 describe-security-groups --region "$REGION" \
    --filters "Name=group-name,Values=$SG_NAME" "Name=vpc-id,Values=$VPC_ID" \
    --query 'SecurityGroups[0].GroupId' --output text 2>/dev/null || echo "None")

if [ "$SG_ID" != "None" ] && [ -n "$SG_ID" ]; then
    log_warn "Security Group '$SG_NAME' 이미 존재: $SG_ID — 재사용"
else
    SG_ID=$(aws ec2 create-security-group --region "$REGION" \
        --group-name "$SG_NAME" \
        --description "OnlyOne Load Test - EC2 instances" \
        --vpc-id "$VPC_ID" \
        --query 'GroupId' --output text)
    log_ok "Security Group 생성: $SG_ID"

    # My IP 조회
    MY_IP=$(curl -sf https://checkip.amazonaws.com)
    log_info "내 IP: $MY_IP"

    # Inbound 규칙
    aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG_ID" \
        --ip-permissions \
        "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=${MY_IP}/32,Description=SSH}]" \
        "IpProtocol=tcp,FromPort=3000,ToPort=3000,IpRanges=[{CidrIp=${MY_IP}/32,Description=Grafana}]" \
        "IpProtocol=tcp,FromPort=9090,ToPort=9090,IpRanges=[{CidrIp=${MY_IP}/32,Description=Prometheus}]" \
        "IpProtocol=tcp,FromPort=8080,ToPort=8080,IpRanges=[{CidrIp=${MY_IP}/32,Description=App}]" \
        > /dev/null
    log_ok "Inbound: SSH, Grafana, Prometheus, App (내 IP)"

    # 자기참조 (인스턴스 간 전체 통신)
    aws ec2 authorize-security-group-ingress --region "$REGION" --group-id "$SG_ID" \
        --ip-permissions \
        "IpProtocol=-1,UserIdGroupPairs=[{GroupId=$SG_ID,Description=Self-reference}]" \
        > /dev/null
    log_ok "Inbound: 자기참조 (인스턴스 간 All Traffic)"
fi

# ── 5. EC2 인스턴스 Launch ──
log_info "=== 5. EC2 인스턴스 Launch ==="

# 인프라 서버 (c5.2xlarge, 150GB gp3)
log_info "인프라 서버 (c5.2xlarge, 150GB) 시작..."
INFRA_INSTANCE_ID=$(aws ec2 run-instances --region "$REGION" \
    --image-id "$AMI_ID" \
    --instance-type c5.2xlarge \
    --key-name "$KEY_NAME" \
    --security-group-ids "$SG_ID" \
    --subnet-id "$SUBNET_ID" \
    --associate-public-ip-address \
    --block-device-mappings "DeviceName=/dev/sda1,Ebs={VolumeSize=150,VolumeType=gp3,Iops=3000,Throughput=125}" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=onlyone-infra}]" \
    --query 'Instances[0].InstanceId' --output text)
log_ok "인프라 인스턴스: $INFRA_INSTANCE_ID"

# 앱 서버 (c5.xlarge, 50GB gp3)
log_info "앱 서버 (c5.xlarge, 50GB) 시작..."
APP_INSTANCE_ID=$(aws ec2 run-instances --region "$REGION" \
    --image-id "$AMI_ID" \
    --instance-type c5.xlarge \
    --key-name "$KEY_NAME" \
    --security-group-ids "$SG_ID" \
    --subnet-id "$SUBNET_ID" \
    --associate-public-ip-address \
    --block-device-mappings "DeviceName=/dev/sda1,Ebs={VolumeSize=50,VolumeType=gp3,Iops=3000,Throughput=125}" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=onlyone-app}]" \
    --query 'Instances[0].InstanceId' --output text)
log_ok "앱 인스턴스: $APP_INSTANCE_ID"

# ── 6. 인스턴스 Running 대기 ──
log_info "=== 6. 인스턴스 Running 대기 ==="

aws ec2 wait instance-running --region "$REGION" \
    --instance-ids "$INFRA_INSTANCE_ID" "$APP_INSTANCE_ID"
log_ok "두 인스턴스 모두 Running"

# IP 조회
INFRA_PUBLIC_IP=$(aws ec2 describe-instances --region "$REGION" \
    --instance-ids "$INFRA_INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
INFRA_PRIVATE_IP=$(aws ec2 describe-instances --region "$REGION" \
    --instance-ids "$INFRA_INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].PrivateIpAddress' --output text)
APP_PUBLIC_IP=$(aws ec2 describe-instances --region "$REGION" \
    --instance-ids "$APP_INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
APP_PRIVATE_IP=$(aws ec2 describe-instances --region "$REGION" \
    --instance-ids "$APP_INSTANCE_ID" \
    --query 'Reservations[0].Instances[0].PrivateIpAddress' --output text)

# ── 상태 저장 ──
cat > "$STATE_FILE" <<EOF
REGION=$REGION
VPC_ID=$VPC_ID
SUBNET_ID=$SUBNET_ID
SG_ID=$SG_ID
INFRA_INSTANCE_ID=$INFRA_INSTANCE_ID
APP_INSTANCE_ID=$APP_INSTANCE_ID
INFRA_PUBLIC_IP=$INFRA_PUBLIC_IP
INFRA_PRIVATE_IP=$INFRA_PRIVATE_IP
APP_PUBLIC_IP=$APP_PUBLIC_IP
APP_PRIVATE_IP=$APP_PRIVATE_IP
EOF

# ── 완료 ──
echo ""
echo "============================================"
echo "   EC2 프로비저닝 완료!"
echo "============================================"
echo ""
echo "  인프라 서버 (c5.2xlarge, 8 vCPU, 16GB)"
echo "    Instance: $INFRA_INSTANCE_ID"
echo "    Public:   $INFRA_PUBLIC_IP"
echo "    Private:  $INFRA_PRIVATE_IP"
echo ""
echo "  앱 서버 (c5.xlarge, 4 vCPU, 8GB)"
echo "    Instance: $APP_INSTANCE_ID"
echo "    Public:   $APP_PUBLIC_IP"
echo "    Private:  $APP_PRIVATE_IP"
echo ""
echo "  Key:  $KEY_FILE"
echo "  SG:   $SG_ID"
echo ""
echo "  === 다음 단계 ==="
echo ""
echo "  # 1. 인프라 서버 설정 (SSH 접속 후 1-2분 대기)"
echo "  scp -i $KEY_FILE scripts/ec2-setup-infra.sh ubuntu@${INFRA_PUBLIC_IP}:~/"
echo "  ssh -i $KEY_FILE ubuntu@${INFRA_PUBLIC_IP}"
echo "  APP_PRIVATE_IP=${APP_PRIVATE_IP} ./ec2-setup-infra.sh"
echo ""
echo "  # 2. 앱 서버 설정"
echo "  scp -i $KEY_FILE scripts/ec2-setup-app.sh ubuntu@${APP_PUBLIC_IP}:~/"
echo "  ssh -i $KEY_FILE ubuntu@${APP_PUBLIC_IP}"
echo "  INFRA_HOST=${INFRA_PRIVATE_IP} ./ec2-setup-app.sh"
echo ""
echo "  # 3. 시딩"
echo "  INFRA_HOST=${INFRA_PRIVATE_IP} ./scripts/ec2-seed-data.sh"
echo ""
echo "  # 정리 (테스트 완료 후)"
echo "  ./scripts/ec2-provision.sh teardown"
echo ""
