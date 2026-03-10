#!/bin/bash
# =============================================================
# EC2 데이터 시딩 오케스트레이션
# =============================================================
# 앱 서버에서 실행, 인프라 서버에 데이터 투입
#
# 사용법:
#   INFRA_HOST=10.0.1.x ./scripts/ec2-seed-data.sh [phase]
#
# phase:
#   all       전체 시딩 (기본)
#   mysql     MySQL만
#   es        Elasticsearch만
#   verify    검증만
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
PHASE="${1:-all}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K6_DIR="$(dirname "$SCRIPT_DIR")/k6-tests"

MYSQL_CMD="mysql -h $INFRA_HOST -P 3306 -uroot -proot onlyone"
ES_URL="http://$INFRA_HOST:9200"
ES_AUTH="elastic:changeme"
BASE_URL="${BASE_URL:-http://localhost:8080}"

START_TIME=$(date +%s)

log_info "=== EC2 데이터 시딩 시작 ==="
log_info "인프라: $INFRA_HOST | Phase: $PHASE"
echo ""

# ── Phase 1: MySQL (순차, 순서 중요) ──
seed_mysql() {
    log_info "=== Phase 1: MySQL 시딩 ==="

    local phase_start=$(date +%s)

    log_info "[1/3] seed-all-domains.sql (57M rows, ~60-90분)..."
    $MYSQL_CMD < "$K6_DIR/seed-all-domains.sql"
    log_ok "seed-all-domains.sql 완료"

    log_info "[2/3] seed-search.sql (200K clubs, ~10분)..."
    $MYSQL_CMD < "$K6_DIR/seed-search.sql"
    log_ok "seed-search.sql 완료"

    log_info "[3/3] seed-finance.sql (25K settlements, ~5분)..."
    $MYSQL_CMD < "$K6_DIR/seed-finance.sql"
    log_ok "seed-finance.sql 완료"

    local phase_end=$(date +%s)
    log_ok "MySQL 시딩 완료 ($(( (phase_end - phase_start) / 60 ))분)"
    echo ""
}

# ── Phase 2: Elasticsearch ──
seed_es() {
    log_info "=== Phase 2: Elasticsearch 시딩 ==="

    # nori 플러그인 확인
    PLUGINS=$(curl -sf -u "$ES_AUTH" "$ES_URL/_cat/plugins?format=json" 2>/dev/null || echo "[]")
    if echo "$PLUGINS" | grep -q "analysis-nori"; then
        log_ok "nori 플러그인 OK"
    else
        log_warn "nori 플러그인 미설치 — 인프라 서버에서 설치 필요"
        log_info "인프라 서버: docker exec onlyone-elasticsearch elasticsearch-plugin install analysis-nori -b && docker restart onlyone-elasticsearch"
        return 1
    fi

    # reindex (앱 API)
    log_info "Reindex 요청..."
    if curl -sf http://localhost:8080/actuator/health &>/dev/null; then
        RESPONSE=$(curl -sf -X POST "$BASE_URL/api/v1/admin/search/reindex" -H "Content-Type: application/json" 2>/dev/null || echo "FAILED")
        if [ "$RESPONSE" = "FAILED" ]; then
            log_warn "Reindex API 호출 실패 — 앱 서버 확인 필요"
        else
            log_ok "Reindex 요청 완료: $RESPONSE"
        fi
    else
        log_warn "앱 서버 미실행 — ES reindex 스킵 (앱 시작 후 수동 실행 필요)"
        log_info "수동 실행: curl -X POST $BASE_URL/api/v1/admin/search/reindex"
    fi

    # 검증 (15초 대기)
    sleep 15
    DOC_COUNT=$(curl -sf -u "$ES_AUTH" "$ES_URL/club/_count" 2>/dev/null | grep -o '"count":[0-9]*' | grep -o '[0-9]*' || echo "0")
    log_info "ES club 인덱스 문서 수: $DOC_COUNT"
    echo ""
}

# ── Phase 3: 검증 ──
verify_data() {
    log_info "=== Phase 3: 데이터 검증 ==="

    echo ""
    log_info "--- MySQL 테이블 행 수 ---"
    $MYSQL_CMD -e "
        SELECT table_name, table_rows
        FROM information_schema.tables
        WHERE table_schema = 'onlyone'
        ORDER BY table_rows DESC
        LIMIT 20;
    " 2>/dev/null || log_warn "MySQL 조회 실패"

    echo ""
    log_info "--- Elasticsearch 인덱스 ---"
    curl -sf -u "$ES_AUTH" "$ES_URL/_cat/indices?v&index=club*" 2>/dev/null || log_warn "ES 조회 실패"

    echo ""
}

# ── 메인 ──
case "$PHASE" in
    all)
        seed_mysql
        seed_es
        verify_data
        ;;
    mysql)
        seed_mysql
        ;;
    es)
        seed_es
        ;;
    verify)
        verify_data
        ;;
    *)
        log_error "알 수 없는 phase: $PHASE"
        echo "사용법: $0 [all|mysql|es|verify]"
        exit 1
        ;;
esac

END_TIME=$(date +%s)
ELAPSED=$(( (END_TIME - START_TIME) / 60 ))
echo ""
echo "============================================"
echo "   시딩 완료! (총 ${ELAPSED}분)"
echo "============================================"
