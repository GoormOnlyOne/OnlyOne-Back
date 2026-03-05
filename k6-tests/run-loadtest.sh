#!/bin/bash
# =============================================================
# 부하 테스트 실행 스크립트
# =============================================================
# 사용법:
#   ./k6-tests/run-loadtest.sh [도메인] [옵션]
#
# 도메인:
#   all                전체 도메인 통합 병목 테스트 (500VU)
#   feed               피드 종합 부하 테스트
#   feed-cache         피드 캐시 무효화 + 동시 수정 테스트
#   notification|notif 알림 종합 부하 테스트
#   notif-sse-e2e      알림 SSE E2E 전달 검증
#   notif-sse-reconnect SSE 재연결 복구 테스트
#   notif-sse-capacity  SSE 연결 용량 + CRUD 성능 저하
#   notif-markall      전체읽음 row lock 경합 테스트
#   chat               채팅 종합 부하 테스트
#   chat-ws            채팅 WebSocket 메시지 검증 + soak
#   finance            결제/정산 종합 부하 테스트
#   finance-integrity  지갑 잔액 정합성 + CAS 충돌 추적
#   search             검색 종합 부하 테스트
#   search-sync        검색 결과 정확도 + 일관성 검증
#   club|schedule      클럽/스케줄 종합 부하 테스트
#   club-concurrent    클럽 동시 가입 + 인원제한 검증
#   each               모든 도메인 순차 실행
#   seed               시드 데이터만 투입 (100x, AWS용)
#   seed-10x           시드 데이터 10x 투입 (로컬용)
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
EXTRA_ENV_ARGS=""

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
    docker exec -i onlyone-mysql mysql -uroot -proot onlyone < "$SCRIPT_DIR/seed/seed-all-domains.sql"
    log_ok "MySQL 전체 도메인 시드 완료"

    log_info "MySQL 검색 시드 데이터 투입..."
    docker exec -i onlyone-mysql mysql -uroot -proot onlyone < "$SCRIPT_DIR/search/seed-search.sql"
    log_ok "MySQL 검색 시드 완료"

    log_info "MySQL 정산 시드 데이터 투입..."
    docker exec -i onlyone-mysql mysql -uroot -proot onlyone < "$SCRIPT_DIR/finance/seed-finance.sql"
    log_ok "MySQL 정산 시드 완료"

    # ES reindex (앱이 떠있어야)
    if curl -sf -u elastic:changeme http://localhost:9200/_cluster/health &>/dev/null; then
        log_info "Elasticsearch reindex..."
        curl -sf -X POST http://localhost:8080/api/v1/admin/search/reindex || log_warn "ES reindex 실패 (API 없을 수 있음)"
        log_ok "ES reindex 요청 완료"
    fi

    echo ""
}

# ── 시드 데이터 투입 (10x 로컬용) ──
seed_data_10x() {
    log_info "=== 시드 데이터 투입 (10x 로컬) ==="

    log_info "MySQL 시드 데이터 투입 (10x 스케일)..."
    docker exec -i onlyone-mysql mysql -uroot -proot onlyone < "$SCRIPT_DIR/seed/seed-all-domains-10x.sql"
    log_ok "MySQL 10x 시드 완료"

    echo ""
}

# ── DB에서 MIN_CLUB 등 오프셋 자동 탐지 (AUTO_INCREMENT drift 대응) ──
resolve_db_offsets() {
    log_info "DB 오프셋 자동 탐지..."

    local min_club min_chatroom min_schedule total_clubs total_chatrooms total_schedules total_users

    min_club=$(docker exec onlyone-mysql mysql -uroot -proot -N -e "SELECT MIN(club_id) FROM onlyone.club" 2>/dev/null | tr -d '[:space:]')
    min_chatroom=$(docker exec onlyone-mysql mysql -uroot -proot -N -e "SELECT MIN(chat_room_id) FROM onlyone.chat_room" 2>/dev/null | tr -d '[:space:]')
    min_schedule=$(docker exec onlyone-mysql mysql -uroot -proot -N -e "SELECT MIN(schedule_id) FROM onlyone.schedule WHERE schedule_id < 5000000" 2>/dev/null | tr -d '[:space:]')
    total_clubs=$(docker exec onlyone-mysql mysql -uroot -proot -N -e "SELECT COUNT(*) FROM onlyone.club" 2>/dev/null | tr -d '[:space:]')
    total_chatrooms=$(docker exec onlyone-mysql mysql -uroot -proot -N -e "SELECT COUNT(*) FROM onlyone.chat_room" 2>/dev/null | tr -d '[:space:]')
    total_schedules=$(docker exec onlyone-mysql mysql -uroot -proot -N -e "SELECT COUNT(*) FROM onlyone.schedule WHERE schedule_id < 5000000" 2>/dev/null | tr -d '[:space:]')
    total_users=$(docker exec onlyone-mysql mysql -uroot -proot -N -e "SELECT COUNT(*) FROM onlyone.user WHERE kakao_id BETWEEN 1000001 AND 1100000" 2>/dev/null | tr -d '[:space:]')

    # 환경변수 설정 (k6 Docker에 전달)
    export MIN_CLUB="${min_club:-1}"
    export MIN_CHATROOM="${min_chatroom:-1}"
    export MIN_SCHEDULE="${min_schedule:-1}"
    export TOTAL_CLUBS="${total_clubs:-50000}"
    export TOTAL_CHATROOMS="${total_chatrooms:-50000}"
    export TOTAL_SCHEDULES="${total_schedules:-2000000}"
    export TOTAL_USERS="${total_users:-100000}"
    export USER_COUNT="${total_users:-100000}"
    local settlement_count
    settlement_count=$(docker exec onlyone-mysql mysql -uroot -proot -N -e "SELECT COUNT(*) FROM onlyone.settlement WHERE schedule_id BETWEEN 5000000 AND 5099999" 2>/dev/null | tr -d '[:space:]')
    export SETTLEMENT_COUNT="${settlement_count:-100000}"

    EXTRA_ENV_ARGS="-e MIN_CLUB=$MIN_CLUB -e MIN_CHATROOM=$MIN_CHATROOM -e MIN_SCHEDULE=$MIN_SCHEDULE"
    EXTRA_ENV_ARGS="$EXTRA_ENV_ARGS -e TOTAL_CLUBS=$TOTAL_CLUBS -e TOTAL_CHATROOMS=$TOTAL_CHATROOMS"
    EXTRA_ENV_ARGS="$EXTRA_ENV_ARGS -e TOTAL_SCHEDULES=$TOTAL_SCHEDULES -e TOTAL_USERS=$TOTAL_USERS"
    EXTRA_ENV_ARGS="$EXTRA_ENV_ARGS -e USER_COUNT=$USER_COUNT -e SETTLEMENT_COUNT=$SETTLEMENT_COUNT"

    log_ok "MIN_CLUB=$MIN_CLUB  MIN_CHATROOM=$MIN_CHATROOM  MIN_SCHEDULE=$MIN_SCHEDULE"
    log_ok "TOTAL_CLUBS=$TOTAL_CLUBS  TOTAL_CHATROOMS=$TOTAL_CHATROOMS  TOTAL_SCHEDULES=$TOTAL_SCHEDULES"
    log_ok "TOTAL_USERS=$TOTAL_USERS  USER_COUNT=$USER_COUNT  SETTLEMENT_COUNT=$SETTLEMENT_COUNT"
}

# ── k6 Docker 이미지 ──
K6_IMAGE="${K6_IMAGE:-grafana/k6}"
K6_SSE_IMAGE="${K6_SSE_IMAGE:-k6-sse:latest}"

# ── k6 실행 ──
# $1: test_file  $2: test_name  $3: docker image (optional, default $K6_IMAGE)
run_k6() {
    local test_file="$1"
    local test_name="$2"
    local image="${3:-$K6_IMAGE}"
    local timestamp=$(date +%Y%m%d_%H%M%S)
    local result_file="$RESULTS_DIR/${test_name}_${timestamp}.json"

    log_info "=== $test_name 테스트 시작 (image: $image) ==="

    if [ "$USE_DOCKER" = true ]; then
        MSYS_NO_PATHCONV=1 docker run --rm -i --network=host \
            -v "$(cd "$SCRIPT_DIR" && pwd):/scripts" \
            --add-host=host.docker.internal:host-gateway \
            -e BASE_URL=http://host.docker.internal:8080 \
            $EXTRA_ENV_ARGS \
            "$image" run \
            --out json=/scripts/results/${test_name}_${timestamp}.json \
            $EXTRA_K6_ARGS \
            "/scripts/$test_file"
    else
        # K6_INFLUXDB_URL이 설정되면 InfluxDB로 메트릭 전송 (Grafana 대시보드용)
        local influxdb_out=""
        if [ -n "${K6_INFLUXDB_URL:-}" ]; then
            influxdb_out="--out influxdb=${K6_INFLUXDB_URL}"
        fi
        k6 run \
            --out json="$result_file" \
            $influxdb_out \
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
if [ "$DOMAIN" = "seed" ] || [ "$DOMAIN" = "seed-10x" ] || [ "$SEED_ONLY" = true ]; then
    if [ "$DOMAIN" = "seed-10x" ]; then
        seed_data_10x
    else
        seed_data
    fi
    resolve_db_offsets
    if [ "$SEED_ONLY" = true ] || [ "$DOMAIN" = "seed" ] || [ "$DOMAIN" = "seed-10x" ]; then
        log_ok "시드 데이터 투입 완료. 종료합니다."
        exit 0
    fi
fi

# DB 오프셋 자동 탐지 (seed가 아닌 경우에도)
if [ -z "$MIN_CLUB" ]; then
    resolve_db_offsets
fi

# 테스트 실행
case "$DOMAIN" in
    all)
        run_k6 "common/all-domains-bottleneck-test.js" "all-domains"
        ;;
    feed)
        run_k6 "feed/feed-loadtest.js" "feed"
        ;;
    notification|notif)
        run_k6 "notification/notification-loadtest.js" "notification"
        ;;
    notif-sse-e2e)
        run_k6 "notification/notification-sse-e2e-test.js" "notification-sse-e2e" "$K6_SSE_IMAGE"
        ;;
    notif-sse-reconnect)
        run_k6 "notification/notification-sse-reconnect-test.js" "notification-sse-reconnect" "$K6_SSE_IMAGE"
        ;;
    notif-sse-capacity)
        run_k6 "notification/notification-sse-capacity-test.js" "notification-sse-capacity" "$K6_SSE_IMAGE"
        ;;
    notif-markall)
        run_k6 "notification/notification-markall-contention-test.js" "notification-markall"
        ;;
    chat)
        run_k6 "chat/chat-loadtest.js" "chat"
        ;;
    finance)
        run_k6 "finance/finance-loadtest.js" "finance"
        ;;
    search)
        run_k6 "search/search-loadtest.js" "search"
        ;;
    feed-cache)
        run_k6 "feed/feed-cache-concurrency-test.js" "feed-cache"
        ;;
    chat-ws)
        run_k6 "chat/chat-ws-verification-test.js" "chat-ws-verify"
        ;;
    finance-integrity)
        run_k6 "finance/finance-integrity-test.js" "finance-integrity"
        ;;
    club|schedule)
        run_k6 "club-schedule/club-schedule-loadtest.js" "club-schedule"
        ;;
    club-concurrent)
        run_k6 "club-schedule/club-concurrent-join-test.js" "club-concurrent"
        ;;
    search-sync)
        run_k6 "search/search-index-sync-test.js" "search-sync"
        ;;
    each)
        # 모든 도메인 순차 실행
        log_info "모든 도메인 순차 테스트 실행..."
        run_k6 "notification/notification-loadtest.js" "notification"
        run_k6 "feed/feed-loadtest.js" "feed"
        run_k6 "chat/chat-loadtest.js" "chat"
        run_k6 "search/search-loadtest.js" "search"
        run_k6 "finance/finance-loadtest.js" "finance"
        run_k6 "club-schedule/club-schedule-loadtest.js" "club-schedule"
        log_ok "전체 도메인 순차 테스트 완료!"
        ;;
    *)
        log_error "알 수 없는 도메인: $DOMAIN"
        echo "사용법: $0 [all|feed|feed-cache|notification|notif-sse-e2e|notif-sse-reconnect|notif-sse-capacity|notif-markall|chat|chat-ws|finance|finance-integrity|search|search-sync|club|club-concurrent|each|seed]"
        exit 1
        ;;
esac

echo "============================================"
echo "   테스트 완료!"
echo "   결과: $RESULTS_DIR/"
echo "============================================"
