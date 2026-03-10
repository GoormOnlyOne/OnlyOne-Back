#!/bin/bash
# =============================================================
# EC2 부하 테스트 결과 자동 수집
# =============================================================
# Grafana 대시보드 PNG 캡처 + k6 결과 요약 생성
#
# 사용법:
#   INFRA_HOST=10.0.1.x ./scripts/ec2-collect-results.sh
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
RESULTS_DIR="$PROJECT_DIR/results"

mkdir -p "$RESULTS_DIR"

GRAFANA_URL="http://${INFRA_HOST}:3000"
GRAFANA_USER="admin"
GRAFANA_PASS="admin"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

log_info "=== EC2 부하 테스트 결과 수집 ==="
echo ""

# ── 1. Grafana API Key 생성 ──
log_info "=== 1. Grafana API Key 생성 ==="

GRAFANA_API_KEY=""
if curl -sf "$GRAFANA_URL/api/health" &>/dev/null; then
    # 기존 키가 있으면 사용, 없으면 생성
    GRAFANA_API_KEY=$(curl -sf -X POST "$GRAFANA_URL/api/auth/keys" \
        -H "Content-Type: application/json" \
        -d "{\"name\":\"loadtest-${TIMESTAMP}\",\"role\":\"Admin\",\"secondsToLive\":3600}" \
        -u "$GRAFANA_USER:$GRAFANA_PASS" 2>/dev/null | jq -r '.key // empty')

    if [ -n "$GRAFANA_API_KEY" ]; then
        log_ok "Grafana API Key 생성 완료"
    else
        log_warn "Grafana API Key 생성 실패 — Basic Auth로 시도합니다"
    fi
else
    log_warn "Grafana 연결 불가 ($GRAFANA_URL) — 스냅샷 스킵"
fi

# ── 2. Grafana 대시보드 PNG 캡처 ──
log_info "=== 2. Grafana 대시보드 스냅샷 ==="

capture_dashboard() {
    local dashboard_uid="$1"
    local dashboard_name="$2"
    local from="${3:-now-2h}"
    local to="${4:-now}"
    local output="$RESULTS_DIR/${dashboard_name}_${TIMESTAMP}.png"

    local auth_header=""
    if [ -n "$GRAFANA_API_KEY" ]; then
        auth_header="Authorization: Bearer $GRAFANA_API_KEY"
    else
        # Basic auth fallback
        auth_header="Authorization: Basic $(echo -n "$GRAFANA_USER:$GRAFANA_PASS" | base64)"
    fi

    local render_url="$GRAFANA_URL/render/d/$dashboard_uid?orgId=1&from=$from&to=$to&width=1920&height=1080&theme=light"

    if curl -sf -H "$auth_header" "$render_url" -o "$output" 2>/dev/null; then
        # 유효한 PNG인지 확인 (최소 1KB)
        if [ -f "$output" ] && [ "$(stat -f%z "$output" 2>/dev/null || stat -c%s "$output" 2>/dev/null || echo 0)" -gt 1024 ]; then
            log_ok "$dashboard_name → $output"
        else
            rm -f "$output"
            log_warn "$dashboard_name 렌더링 실패 (빈 응답)"
        fi
    else
        log_warn "$dashboard_name 렌더링 실패 (HTTP 에러)"
    fi
}

if curl -sf "$GRAFANA_URL/api/health" &>/dev/null; then
    # Grafana에서 사용 가능한 대시보드 목록 조회
    log_info "사용 가능한 대시보드 검색..."

    DASHBOARDS=$(curl -sf -u "$GRAFANA_USER:$GRAFANA_PASS" "$GRAFANA_URL/api/search?type=dash-db" 2>/dev/null || echo "[]")

    if [ "$DASHBOARDS" != "[]" ]; then
        echo "$DASHBOARDS" | jq -r '.[] | "\(.uid) \(.title)"' 2>/dev/null | while read -r uid title; do
            log_info "  캡처 중: $title ($uid)"
            capture_dashboard "$uid" "$(echo "$title" | tr ' /' '-_' | tr '[:upper:]' '[:lower:]')"
        done
    else
        log_warn "프로비저닝된 대시보드 없음"
    fi

    # 기본 대시보드 UID 시도
    capture_dashboard "spring-boot"    "spring-boot"    "now-2h" "now"
    capture_dashboard "mysql"          "mysql"          "now-2h" "now"
    capture_dashboard "redis"          "redis"          "now-2h" "now"
    capture_dashboard "elasticsearch"  "elasticsearch"  "now-2h" "now"
else
    log_warn "Grafana 미연결 — 대시보드 캡처 스킵"
fi

echo ""

# ── 3. k6 결과 요약 생성 ──
log_info "=== 3. k6 결과 요약 생성 ==="

SUMMARY_FILE="$RESULTS_DIR/summary_${TIMESTAMP}.txt"

cat > "$SUMMARY_FILE" <<EOF
====================================================================
  OnlyOne EC2 부하 테스트 결과 요약
  생성: $(date '+%Y-%m-%d %H:%M:%S')
  인프라: $INFRA_HOST
====================================================================

EOF

# 각 summary JSON 파일 처리
SUMMARY_COUNT=0
for summary_file in "$RESULTS_DIR"/*_summary.txt; do
    [ -f "$summary_file" ] || continue

    domain_name=$(basename "$summary_file" | sed 's/_[0-9]*_[0-9]*_summary\.txt//')

    # summary export JSON 파일에서 핵심 메트릭 추출
    if jq -e '.metrics' "$summary_file" &>/dev/null; then
        SUMMARY_COUNT=$((SUMMARY_COUNT + 1))

        echo "--- $domain_name ---" >> "$SUMMARY_FILE"

        # http_req_duration 추출
        jq -r '
            .metrics.http_req_duration // empty |
            "  요청 수:    \(.values.count // "N/A")\n" +
            "  평균:       \((.values.avg // 0) | round)ms\n" +
            "  p95:        \((.values["p(95)"] // 0) | round)ms\n" +
            "  p99:        \((.values["p(99)"] // 0) | round)ms\n" +
            "  최대:       \((.values.max // 0) | round)ms"
        ' "$summary_file" 2>/dev/null >> "$SUMMARY_FILE" || true

        # http_req_failed 추출
        jq -r '
            .metrics.http_req_failed // empty |
            "  에러율:     \((.values.rate // 0) * 100 | . * 100 | round / 100)%"
        ' "$summary_file" 2>/dev/null >> "$SUMMARY_FILE" || true

        # http_reqs (처리량)
        jq -r '
            .metrics.http_reqs // empty |
            "  처리량:     \((.values.rate // 0) | . * 100 | round / 100) req/s"
        ' "$summary_file" 2>/dev/null >> "$SUMMARY_FILE" || true

        echo "" >> "$SUMMARY_FILE"
    fi
done

if [ $SUMMARY_COUNT -eq 0 ]; then
    echo "  (k6 summary 파일 없음)" >> "$SUMMARY_FILE"
    log_warn "k6 summary 파일이 없습니다. 테스트를 먼저 실행하세요."
else
    log_ok "k6 요약: $SUMMARY_COUNT 도메인 처리"
fi

# 결과 파일 목록 추가
echo "" >> "$SUMMARY_FILE"
echo "=== 결과 파일 목록 ===" >> "$SUMMARY_FILE"
ls -lh "$RESULTS_DIR/" >> "$SUMMARY_FILE" 2>/dev/null

log_ok "요약 파일: $SUMMARY_FILE"
echo ""

# ── 4. 결과 파일 목록 ──
log_info "=== 4. 결과 파일 목록 ==="

echo ""
echo "  JSON 결과:"
ls -lh "$RESULTS_DIR"/*.json 2>/dev/null | awk '{print "    " $NF " (" $5 ")"}' || echo "    (없음)"

echo ""
echo "  로그:"
ls -lh "$RESULTS_DIR"/*.log 2>/dev/null | awk '{print "    " $NF " (" $5 ")"}' || echo "    (없음)"

echo ""
echo "  PNG 스냅샷:"
ls -lh "$RESULTS_DIR"/*.png 2>/dev/null | awk '{print "    " $NF " (" $5 ")"}' || echo "    (없음)"

echo ""
echo "  tcpdump 캡처:"
ls -lh "$RESULTS_DIR/tcpdump/"*.pcap 2>/dev/null | awk '{print "    " $NF " (" $5 ")"}' || echo "    (없음)"

echo ""
echo "  JFR 레코딩:"
ls -lh "$RESULTS_DIR/jfr/"*.jfr 2>/dev/null | awk '{print "    " $NF " (" $5 ")"}' || echo "    (없음)"

echo ""
echo "  GC 로그:"
ls -lh "$RESULTS_DIR/gclog/"*.log 2>/dev/null | awk '{print "    " $NF " (" $5 ")"}' || echo "    (없음)"

echo ""

# ── 5. 로컬 다운로드 안내 ──
K6_PUBLIC_IP=$(curl -sf http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo "<K6_PUBLIC_IP>")

echo "============================================"
echo "   결과 수집 완료!"
echo "============================================"
echo ""
echo "  요약: $SUMMARY_FILE"
echo ""
echo "  === 로컬로 전체 다운로드 ==="
echo ""
echo "  scp -i scripts/onlyone-loadtest.pem -r \\"
echo "    ubuntu@${K6_PUBLIC_IP}:~/OnlyOne-Back/results/ \\"
echo "    ./ec2-loadtest-results/"
echo ""
echo "  === 로컬에서 분석 ==="
echo ""
echo "  # Wireshark (네트워크 패킷 분석)"
echo "  wireshark ./ec2-loadtest-results/tcpdump/<파일>.pcap"
echo ""
echo "  # JDK Mission Control (JFR CPU/스레드/GC 분석)"
echo "  jmc -open ./ec2-loadtest-results/jfr/<파일>.jfr"
echo ""
echo "  # Eclipse MAT (힙 덤프 메모리 분석)"
echo "  # ./ec2-loadtest-results/heapdumps/<파일>.hprof"
echo ""
echo "  # GC 로그 (GCViewer 또는 gceasy.io)"
echo "  # ./ec2-loadtest-results/gclog/gc_*.log"
echo ""
echo "  === 요약 내용 ==="
echo ""
cat "$SUMMARY_FILE"
