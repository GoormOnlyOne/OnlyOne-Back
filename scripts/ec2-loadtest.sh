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
APP_URL="${APP_URL:-$BASE_URL}"
THREAD_DUMP_INTERVAL="${THREAD_DUMP_INTERVAL:-30}"
GRAFANA_URL="${GRAFANA_URL:-http://${INFRA_HOST}:3000}"
GRAFANA_USER="${GRAFANA_USER:-admin}"
GRAFANA_PASS="${GRAFANA_PASS:-admin}"
PROMETHEUS_RW_URL="${PROMETHEUS_RW_URL:-http://${INFRA_HOST}:9090/api/v1/write}"
FILE_SERVER_PORT="${FILE_SERVER_PORT:-9999}"
S3_BUCKET="${S3_BUCKET:-onlyone-loadtest-results}"
S3_PREFIX="${S3_PREFIX:-results}"
S3_REGION="${S3_REGION:-ap-northeast-2}"

# ── Thread Dump 수집 (백그라운드) ──
start_thread_dump_collector() {
    local test_name="$1"
    local dump_dir="$RESULTS_DIR/threaddumps/${test_name}_$(date +%Y%m%d_%H%M%S)"
    mkdir -p "$dump_dir"

    (
        local seq=0
        while true; do
            local ts=$(date +%H%M%S)
            curl -sf -m 5 "$APP_URL/actuator/threaddump" \
                -H "Accept: application/json" \
                > "$dump_dir/dump_${seq}_${ts}.json" 2>/dev/null || true
            seq=$((seq + 1))
            sleep "$THREAD_DUMP_INTERVAL"
        done
    ) &
    THREAD_DUMP_PID=$!
    log_info "Thread dump 수집 시작 (PID=$THREAD_DUMP_PID, 간격=${THREAD_DUMP_INTERVAL}s) → $dump_dir"
}

stop_thread_dump_collector() {
    if [ -n "${THREAD_DUMP_PID:-}" ] && kill -0 "$THREAD_DUMP_PID" 2>/dev/null; then
        kill "$THREAD_DUMP_PID" 2>/dev/null || true
        wait "$THREAD_DUMP_PID" 2>/dev/null || true
        log_info "Thread dump 수집 종료 (PID=$THREAD_DUMP_PID)"
        unset THREAD_DUMP_PID
    fi
}

# ── Grafana 대시보드 스냅샷 캡처 ──
capture_grafana_snapshots() {
    local test_name="$1"
    local from_epoch="$2"    # 테스트 시작 epoch (ms)
    local to_epoch="$3"      # 테스트 종료 epoch (ms)
    local snap_dir="$RESULTS_DIR/grafana"
    mkdir -p "$snap_dir"

    if ! curl -sf -m 3 "$GRAFANA_URL/api/health" &>/dev/null; then
        log_warn "Grafana 연결 불가 — 스냅샷 스킵"
        return
    fi

    local auth_header="Authorization: Basic $(echo -n "$GRAFANA_USER:$GRAFANA_PASS" | base64)"

    # 사용 가능한 대시보드 목록 가져오기
    local dashboards
    dashboards=$(curl -sf -m 5 -H "$auth_header" "$GRAFANA_URL/api/search?type=dash-db" 2>/dev/null || echo "[]")

    if [ "$dashboards" = "[]" ]; then
        log_warn "Grafana 대시보드 없음"
        return
    fi

    log_info "Grafana 스냅샷 캡처 중 ($test_name)..."

    echo "$dashboards" | jq -r '.[] | "\(.uid)\t\(.title)"' 2>/dev/null | while IFS=$'\t' read -r uid title; do
        local safe_title
        safe_title=$(echo "$title" | tr ' /' '-_' | tr '[:upper:]' '[:lower:]')
        local output="$snap_dir/${test_name}_${safe_title}.png"

        curl -sf -m 30 -H "$auth_header" \
            "$GRAFANA_URL/render/d/${uid}?orgId=1&from=${from_epoch}&to=${to_epoch}&width=1920&height=1080&theme=light" \
            -o "$output" 2>/dev/null || true

        if [ -f "$output" ] && [ "$(stat -c%s "$output" 2>/dev/null || echo 0)" -gt 1024 ]; then
            log_ok "  $title → $(basename "$output")"
        else
            rm -f "$output"
        fi
    done
}

# ── S3 업로드 (결과 파일 클라우드 저장) ──
upload_to_s3() {
    local test_name="$1"
    local timestamp="$2"
    local run_dir="${S3_PREFIX}/$(date +%Y%m%d)/${test_name}_${timestamp}"

    if ! command -v aws &>/dev/null; then
        log_warn "AWS CLI 미설치 — S3 업로드 스킵"
        return
    fi

    # 자격증명 확인 (IAM Role 또는 aws configure)
    if ! aws sts get-caller-identity &>/dev/null; then
        log_warn "AWS 자격증명 미설정 — S3 업로드 스킵 (aws configure 또는 IAM Role 필요)"
        return
    fi

    log_info "S3 업로드 중 (s3://${S3_BUCKET}/${run_dir}/) ..."

    local uploaded=0

    # HTML 리포트
    for f in "$RESULTS_DIR/${test_name}_${timestamp}"*_report.html; do
        [ -f "$f" ] || continue
        aws s3 cp "$f" "s3://${S3_BUCKET}/${run_dir}/$(basename "$f")" \
            --content-type "text/html" --quiet 2>/dev/null && uploaded=$((uploaded+1)) || true
    done

    # 요약 + 로그
    for f in "$RESULTS_DIR/${test_name}_${timestamp}"*_summary.txt "$RESULTS_DIR/${test_name}_${timestamp}"*.log; do
        [ -f "$f" ] || continue
        aws s3 cp "$f" "s3://${S3_BUCKET}/${run_dir}/$(basename "$f")" --quiet 2>/dev/null && uploaded=$((uploaded+1)) || true
    done

    # Grafana 스냅샷
    for f in "$RESULTS_DIR/grafana/${test_name}_"*.png; do
        [ -f "$f" ] || continue
        aws s3 cp "$f" "s3://${S3_BUCKET}/${run_dir}/grafana/$(basename "$f")" \
            --content-type "image/png" --quiet 2>/dev/null && uploaded=$((uploaded+1)) || true
    done

    # Thread dumps (최신 3개만 — 전체는 너무 큼)
    local dump_dir
    dump_dir=$(ls -td "$RESULTS_DIR/threaddumps/${test_name}_"* 2>/dev/null | head -1)
    if [ -n "$dump_dir" ] && [ -d "$dump_dir" ]; then
        ls -t "$dump_dir"/*.json 2>/dev/null | head -3 | while read -r f; do
            aws s3 cp "$f" "s3://${S3_BUCKET}/${run_dir}/threaddumps/$(basename "$f")" --quiet 2>/dev/null && uploaded=$((uploaded+1)) || true
        done
    fi

    if [ "$uploaded" -gt 0 ]; then
        log_ok "S3 업로드 완료 (${uploaded}개 파일)"
        log_info "  S3 경로: s3://${S3_BUCKET}/${run_dir}/"
        log_info "  다운로드: aws s3 sync s3://${S3_BUCKET}/${run_dir}/ ./${test_name}/"
    fi
}

# ── 결과 파일 HTTP 서버 (외부 접근용) ──
start_file_server() {
    # 이미 실행 중이면 스킵
    if lsof -i :"$FILE_SERVER_PORT" &>/dev/null 2>&1; then
        log_info "파일 서버 이미 실행 중 (port $FILE_SERVER_PORT)"
        return
    fi

    cd "$RESULTS_DIR"
    python3 -m http.server "$FILE_SERVER_PORT" --bind 0.0.0.0 &>/dev/null &
    FILE_SERVER_PID=$!
    cd "$PROJECT_DIR"

    local public_ip
    public_ip=$(curl -sf -m 3 http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo "<K6_PUBLIC_IP>")
    log_ok "결과 파일 서버: http://${public_ip}:${FILE_SERVER_PORT}/"
    log_info "  → HTML 리포트, Thread dump, Grafana 스냅샷 브라우저에서 직접 열람 가능"
}

stop_file_server() {
    if [ -n "${FILE_SERVER_PID:-}" ] && kill -0 "$FILE_SERVER_PID" 2>/dev/null; then
        kill "$FILE_SERVER_PID" 2>/dev/null || true
        log_info "파일 서버 종료"
    fi
}

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
    local test_start_ms=$((test_start * 1000))

    # Thread dump 수집 시작
    start_thread_dump_collector "$test_name"

    local result_html="$RESULTS_DIR/${test_name}_${timestamp}_report.html"

    local k6_exit=0
    K6_WEB_DASHBOARD=true \
    K6_WEB_DASHBOARD_EXPORT="$result_html" \
    K6_PROMETHEUS_RW_SERVER_URL="$PROMETHEUS_RW_URL" \
    K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true \
    k6 run \
        -e BASE_URL="$BASE_URL" \
        --out experimental-prometheus-rw \
        --out json="$result_json" \
        --summary-export="$result_summary" \
        $EXTRA_K6_ARGS \
        "$K6_DIR/$test_file" 2>&1 | tee "$RESULTS_DIR/${test_name}_${timestamp}.log" || k6_exit=$?

    local test_end=$(date +%s)
    local elapsed=$(( (test_end - test_start) / 60 ))

    local test_end_ms=$(($(date +%s) * 1000))

    # Thread dump 수집 종료
    stop_thread_dump_collector

    # Grafana 대시보드 스냅샷 캡처 (테스트 시간 범위)
    capture_grafana_snapshots "$test_name" "$test_start_ms" "$test_end_ms"

    # S3 클라우드 업로드
    upload_to_s3 "$test_name" "$timestamp"

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

# 결과 파일 서버 시작 (외부 브라우저 접근용)
start_file_server
trap stop_file_server EXIT

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
    feed)
        run_k6 "feed/feed-loadtest.js" "feed"
        ;;
    notification|notif)
        run_k6 "notification/notification-loadtest.js" "notification"
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
    club|schedule)
        run_k6 "club-schedule/club-schedule-loadtest.js" "club-schedule"
        ;;
    each)
        log_info "전 도메인 순차 테스트 (쿨다운: ${COOLDOWN}초)"
        echo ""

        run_k6 "notification/notification-loadtest.js" "notification"
        cooldown

        run_k6 "feed/feed-loadtest.js" "feed"
        cooldown

        run_k6 "chat/chat-loadtest.js" "chat"
        cooldown

        run_k6 "search/search-loadtest.js" "search"
        cooldown

        run_k6 "finance/finance-loadtest.js" "finance"
        cooldown

        run_k6 "club-schedule/club-schedule-loadtest.js" "club-schedule"
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
echo "  Thread dump 수집:"
for dir in "$RESULTS_DIR/threaddumps/"*/; do
    [ -d "$dir" ] || continue
    count=$(ls "$dir"*.json 2>/dev/null | wc -l)
    echo "    $(basename "$dir"): ${count}개"
done
echo ""
echo "  k6 HTML 리포트:"
ls -lh "$RESULTS_DIR/"*_report.html 2>/dev/null | awk '{print "    " $NF " (" $5 ")"}' || echo "    (없음)"
echo ""

PUBLIC_IP=$(curl -sf -m 3 http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo "<K6_PUBLIC_IP>")
echo "  ★ 브라우저에서 결과 열람:"
echo "    http://${PUBLIC_IP}:${FILE_SERVER_PORT}/"
echo ""
echo "  다음 단계: ./scripts/ec2-collect-results.sh"
echo ""
echo "  파일 서버가 계속 실행 중입니다. Ctrl+C로 종료하세요."
# 파일 서버를 유지하기 위해 대기
wait "${FILE_SERVER_PID:-}" 2>/dev/null || true
