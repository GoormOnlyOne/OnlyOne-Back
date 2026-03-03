#!/bin/bash
# =============================================================
# 부하 테스트 실행 스크립트
# =============================================================
# 사용법:
#   ./k6-tests/run-loadtest.sh [도메인] [옵션]
#
# 도메인:
#   all          전체 도메인 통합 테스트
#   feed         피드 도메인
#   notification 알림 도메인
#   chat         채팅 도메인
#   finance      결제/정산/지갑 도메인
#   search       검색 도메인
#   club         클럽/스케줄 도메인
#   seed         시드 데이터만 투입 (테스트 미실행)
#
# 옵션:
#   --vus N      최대 VU 수 오버라이드
#   --duration D 각 단계 지속시간 오버라이드 (예: 3m)
#   --docker     Docker로 k6 실행 (기본: 로컬 k6)
#   --seed-only  시드 데이터만 투입하고 종료
# =============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
RESULTS_DIR="$SCRIPT_DIR/results"

# 결과 디렉토리
mkdir -p "$RESULTS_DIR"

# 색상
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# 기본값
DOMAIN="${1:-all}"
USE_DOCKER=false
SEED_ONLY=false
EXTRA_K6_ARGS=""

# 옵션 파싱
shift || true
while [[ $# -gt 0 ]]; do
    case $1 in
        --docker)   USE_DOCKER=true; shift ;;
        --seed-only) SEED_ONLY=true; shift ;;
        --vus)      EXTRA_K6_ARGS="$EXTRA_K6_ARGS --vus $2"; shift 2 ;;
        --duration) EXTRA_K6_ARGS="$EXTRA_K6_ARGS --duration $2"; shift 2 ;;
        *)          EXTRA_K6_ARGS="$EXTRA_K6_ARGS $1"; shift ;;
    esac
done

# ── 유틸 ──
log_info()  { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok()    { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ── 인프라 확인 ──
check_infra() {
    log_info "인프라 상태 확인..."

    # MySQL
    if docker exec onlyone-mysql mysqladmin ping -h localhost -uroot -proot &>/dev/null; then
        log_ok "MySQL OK"
    else
        log_error "MySQL 연결 실패"
        exit 1
    fi

    # Redis
    if docker exec onlyone-redis redis-cli ping &>/dev/null; then
        log_ok "Redis OK"
    else
        log_error "Redis 연결 실패"
        exit 1
    fi

    # MongoDB (선택)
    if docker ps --format '{{.Names}}' | grep -q onlyone-mongodb; then
        if docker exec onlyone-mongodb mongosh -u root -p root --authenticationDatabase admin --eval "db.runCommand('ping')" &>/dev/null; then
            log_ok "MongoDB OK"
        else
            log_warn "MongoDB 컨테이너 있으나 연결 실패"
        fi
    else
        log_warn "MongoDB 미실행 (--profile mongodb 필요)"
    fi

    # Elasticsearch (선택)
    if curl -sf -u elastic:changeme http://localhost:9200/_cluster/health &>/dev/null; then
        log_ok "Elasticsearch OK"
    else
        log_warn "Elasticsearch 미연결"
    fi

    # 앱 확인
    if curl -sf http://localhost:8080/actuator/health &>/dev/null; then
        log_ok "앱 서버 OK (port 8080)"
    else
        log_error "앱 서버 미실행 (localhost:8080)"
        log_info "실행: SPRING_PROFILES_ACTIVE=local,loadtest ./gradlew :onlyone-api:bootRun"
        exit 1
    fi

    echo ""
}

# ── 시드 데이터 투입 ──
seed_data() {
    log_info "=== 시드 데이터 투입 ==="

    # MySQL (순서: all-domains → search → finance)
    log_info "MySQL 시드 데이터 투입 (100x 스케일)..."
    docker exec -i onlyone-mysql mysql -uroot -proot onlyone < "$SCRIPT_DIR/seed-all-domains.sql"
    log_ok "MySQL 전체 도메인 시드 완료"

    log_info "MySQL 검색 시드 데이터 투입..."
    docker exec -i onlyone-mysql mysql -uroot -proot onlyone < "$SCRIPT_DIR/seed-search.sql"
    log_ok "MySQL 검색 시드 완료"

    log_info "MySQL 정산 시드 데이터 투입..."
    docker exec -i onlyone-mysql mysql -uroot -proot onlyone < "$SCRIPT_DIR/seed-finance.sql"
    log_ok "MySQL 정산 시드 완료"

    # MongoDB (있는 경우)
    if docker ps --format '{{.Names}}' | grep -q onlyone-mongodb; then
        log_info "MongoDB 시드 데이터 투입..."

        # 스크립트 복사
        docker exec onlyone-mongodb mkdir -p /scripts
        docker cp "$SCRIPT_DIR/seed-mongo-feed.js" onlyone-mongodb:/scripts/
        docker cp "$SCRIPT_DIR/seed-mongo-notifications.js" onlyone-mongodb:/scripts/
        docker cp "$SCRIPT_DIR/seed-mongo-chat.js" onlyone-mongodb:/scripts/

        # 실행 (3개 병렬 — 100x 스케일)
        log_info "MongoDB 3개 시드 스크립트 병렬 실행..."
        docker exec onlyone-mongodb mongosh -u root -p root --authenticationDatabase admin onlyone /scripts/seed-mongo-feed.js &
        PID_FEED=$!
        docker exec onlyone-mongodb mongosh -u root -p root --authenticationDatabase admin onlyone /scripts/seed-mongo-notifications.js &
        PID_NOTIF=$!
        docker exec onlyone-mongodb mongosh -u root -p root --authenticationDatabase admin onlyone /scripts/seed-mongo-chat.js &
        PID_CHAT=$!

        wait $PID_FEED && log_ok "MongoDB 피드 시드 완료" || log_error "MongoDB 피드 시드 실패"
        wait $PID_NOTIF && log_ok "MongoDB 알림 시드 완료" || log_error "MongoDB 알림 시드 실패"
        wait $PID_CHAT && log_ok "MongoDB 채팅 시드 완료" || log_error "MongoDB 채팅 시드 실패"
    fi

    # ES reindex (앱이 떠있어야)
    if curl -sf -u elastic:changeme http://localhost:9200/_cluster/health &>/dev/null; then
        log_info "Elasticsearch reindex..."
        curl -sf -X POST http://localhost:8080/api/v1/admin/search/reindex || log_warn "ES reindex 실패 (API 없을 수 있음)"
        log_ok "ES reindex 요청 완료"
    fi

    echo ""
}

# ── k6 실행 ──
run_k6() {
    local test_file="$1"
    local test_name="$2"
    local timestamp=$(date +%Y%m%d_%H%M%S)
    local result_file="$RESULTS_DIR/${test_name}_${timestamp}.json"

    log_info "=== $test_name 테스트 시작 ==="

    if [ "$USE_DOCKER" = true ]; then
        MSYS_NO_PATHCONV=1 docker run --rm -i --network=host \
            -v "$(cd "$SCRIPT_DIR" && pwd):/scripts" \
            -e BASE_URL=http://host.docker.internal:8080 \
            grafana/k6 run \
            --out json=/scripts/results/${test_name}_${timestamp}.json \
            $EXTRA_K6_ARGS \
            "/scripts/$test_file"
    else
        k6 run \
            --out json="$result_file" \
            $EXTRA_K6_ARGS \
            "$SCRIPT_DIR/$test_file"
    fi

    log_ok "$test_name 테스트 완료 → $result_file"
    echo ""
}

# ── 메인 ──
echo ""
echo "============================================"
echo "   OnlyOne 부하 테스트 ($DOMAIN)"
echo "============================================"
echo ""

check_infra

# 시드 데이터
if [ "$DOMAIN" = "seed" ] || [ "$SEED_ONLY" = true ]; then
    seed_data
    if [ "$SEED_ONLY" = true ] || [ "$DOMAIN" = "seed" ]; then
        log_ok "시드 데이터 투입 완료. 종료합니다."
        exit 0
    fi
fi

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
        # 모든 도메인 순차 실행
        log_info "모든 도메인 순차 테스트 실행..."
        run_k6 "notification-loadtest.js" "notification"
        run_k6 "feed-loadtest.js" "feed"
        run_k6 "chat-loadtest.js" "chat"
        run_k6 "search-loadtest.js" "search"
        run_k6 "finance-loadtest.js" "finance"
        run_k6 "club-schedule-loadtest.js" "club-schedule"
        log_ok "전체 도메인 순차 테스트 완료!"
        ;;
    *)
        log_error "알 수 없는 도메인: $DOMAIN"
        echo "사용법: $0 [all|feed|notification|chat|finance|search|club|each|seed]"
        exit 1
        ;;
esac

echo "============================================"
echo "   테스트 완료!"
echo "   결과: $RESULTS_DIR/"
echo "============================================"
