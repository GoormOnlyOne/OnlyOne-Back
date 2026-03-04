#!/bin/bash
# =============================================================
# EC2 k6 부하 테스트 실행 + 결과 수집
# =============================================================
# 앱 서버에서 네이티브 k6 실행
#
# 사용법:
#   INFRA_HOST=10.0.1.x ./scripts/ec2-loadtest.sh [도메인] [옵션]
#
# 도메인:
#   each         전 도메인 순차 실행 (기본)
#   all          전체 통합 테스트
#   feed         피드 도메인
#   notification 알림 도메인
#   chat         채팅 도메인
#   finance      정산 도메인
#   search       검색 도메인
#   club         클럽/스케줄 도메인
#   seed         시드 데이터만 투입
#
# 옵션:
#   --seed       테스트 전 시딩 실행
#   --cooldown N 도메인 간 쿨다운 시간(초, 기본 60)
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

INFRA_HOST="${INFRA_HOST:?INFRA_HOST 환경변수를 설정하세요}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
K6_DIR="$PROJECT_DIR/k6-tests"
RESULTS_DIR="$PROJECT_DIR/results"

mkdir -p "$RESULTS_DIR"

DOMAIN="${1:-each}"
RUN_SEED=false
COOLDOWN=60
EXTRA_K6_ARGS=""

# 옵션 파싱
shift || true
while [[ $# -gt 0 ]]; do
    case $1 in
        --seed)      RUN_SEED=true; shift ;;
        --cooldown)  COOLDOWN="$2"; shift 2 ;;
        --vus)       EXTRA_K6_ARGS="$EXTRA_K6_ARGS --vus $2"; shift 2 ;;
        --duration)  EXTRA_K6_ARGS="$EXTRA_K6_ARGS --duration $2"; shift 2 ;;
        *)           EXTRA_K6_ARGS="$EXTRA_K6_ARGS $1"; shift ;;
    esac
done

BASE_URL="${BASE_URL:-http://localhost:8080}"

# ── 인프라 연결 확인 ──
check_infra() {
    log_info "인프라 연결 확인..."

    # MySQL
    if timeout 5 bash -c "echo >/dev/tcp/$INFRA_HOST/3306" 2>/dev/null; then
        log_ok "MySQL ($INFRA_HOST:3306)"
    else
        log_error "MySQL 연결 실패"
        exit 1
    fi

    # Redis
    if timeout 5 bash -c "echo >/dev/tcp/$INFRA_HOST/6379" 2>/dev/null; then
        log_ok "Redis ($INFRA_HOST:6379)"
    else
        log_error "Redis 연결 실패"
        exit 1
    fi

    # MongoDB
    if timeout 5 bash -c "echo >/dev/tcp/$INFRA_HOST/27017" 2>/dev/null; then
        log_ok "MongoDB ($INFRA_HOST:27017)"
    else
        log_warn "MongoDB 연결 실패 — MongoDB 의존 테스트 실패 가능"
    fi

    # Elasticsearch
    if curl -sf -u elastic:changeme "http://$INFRA_HOST:9200/_cluster/health" &>/dev/null; then
        log_ok "Elasticsearch ($INFRA_HOST:9200)"
    else
        log_warn "Elasticsearch 연결 실패 — 검색 테스트 실패 가능"
    fi

    # 앱 서버
    if curl -sf "$BASE_URL/actuator/health" &>/dev/null; then
        log_ok "앱 서버 ($BASE_URL)"
    else
        log_error "앱 서버 미실행 ($BASE_URL)"
        log_info "실행: SPRING_PROFILES_ACTIVE=ec2,loadtest java -jar *.jar"
        exit 1
    fi

    echo ""
}

# ── k6 실행 ──
run_k6() {
    local test_file="$1"
    local test_name="$2"
    local timestamp=$(date +%Y%m%d_%H%M%S)
    local result_json="$RESULTS_DIR/${test_name}_${timestamp}.json"
    local result_summary="$RESULTS_DIR/${test_name}_${timestamp}_summary.txt"

    log_info "=== $test_name 테스트 시작 ($(date '+%H:%M:%S')) ==="

    local test_start=$(date +%s)

    local k6_exit=0
    k6 run \
        -e BASE_URL="$BASE_URL" \
        --out json="$result_json" \
        --summary-export="$result_summary" \
        $EXTRA_K6_ARGS \
        "$K6_DIR/$test_file" 2>&1 | tee "$RESULTS_DIR/${test_name}_${timestamp}.log" || k6_exit=$?

    local test_end=$(date +%s)
    local elapsed=$(( (test_end - test_start) / 60 ))

    if [ "$k6_exit" -eq 99 ]; then
        log_warn "$test_name 완료 — threshold 초과 있음 (${elapsed}분) → $result_json"
    elif [ "$k6_exit" -ne 0 ]; then
        log_error "$test_name 실패 (exit=$k6_exit, ${elapsed}분)"
    else
        log_ok "$test_name 완료 (${elapsed}분) → $result_json"
    fi
    echo ""
}

# ── 쿨다운 ──
cooldown() {
    if [ "$COOLDOWN" -gt 0 ]; then
        log_info "쿨다운 ${COOLDOWN}초 (GC 안정화, 커넥션 정리)..."
        sleep "$COOLDOWN"
    fi
}

# ── 메인 ──
echo ""
echo "============================================"
echo "   EC2 부하 테스트 ($DOMAIN)"
echo "   인프라: $INFRA_HOST"
echo "   앱: $BASE_URL"
echo "============================================"
echo ""

check_infra

# 시딩
if [ "$RUN_SEED" = true ] || [ "$DOMAIN" = "seed" ]; then
    INFRA_HOST="$INFRA_HOST" "$SCRIPT_DIR/ec2-seed-data.sh" all
    if [ "$DOMAIN" = "seed" ]; then
        exit 0
    fi
fi

TOTAL_START=$(date +%s)

# 테스트 실행
case "$DOMAIN" in
    all)
        run_k6 "all-domains-bottleneck-test.js" "all-domains"
        ;;
    feed)
        run_k6 "feed-loadtest.js" "feed"
        ;;
    notification|notif)
        run_k6 "notification-loadtest.js" "notification"
        ;;
    chat)
        run_k6 "chat-loadtest.js" "chat"
        ;;
    finance)
        run_k6 "finance-loadtest.js" "finance"
        ;;
    search)
        run_k6 "search-loadtest.js" "search"
        ;;
    club|schedule)
        run_k6 "club-schedule-loadtest.js" "club-schedule"
        ;;
    each)
        log_info "전 도메인 순차 테스트 (쿨다운: ${COOLDOWN}초)"
        echo ""

        run_k6 "notification-loadtest.js" "notification"
        cooldown

        run_k6 "feed-loadtest.js" "feed"
        cooldown

        run_k6 "chat-loadtest.js" "chat"
        cooldown

        run_k6 "search-loadtest.js" "search"
        cooldown

        run_k6 "finance-loadtest.js" "finance"
        cooldown

        run_k6 "club-schedule-loadtest.js" "club-schedule"
        ;;
    *)
        log_error "알 수 없는 도메인: $DOMAIN"
        echo "사용법: $0 [each|all|feed|notification|chat|finance|search|club|seed]"
        exit 1
        ;;
esac

TOTAL_END=$(date +%s)
TOTAL_MIN=$(( (TOTAL_END - TOTAL_START) / 60 ))

echo ""
echo "============================================"
echo "   전체 테스트 완료! (총 ${TOTAL_MIN}분)"
echo "   결과: $RESULTS_DIR/"
echo "============================================"
echo ""
echo "  결과 파일 목록:"
ls -lh "$RESULTS_DIR/"*.json 2>/dev/null || echo "  (결과 파일 없음)"
echo ""
echo "  다음 단계: ./scripts/ec2-collect-results.sh"
echo ""
