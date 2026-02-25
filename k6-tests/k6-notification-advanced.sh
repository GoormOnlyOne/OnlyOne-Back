#!/bin/bash
# =============================================================
# 알림/SSE 상세 부하 테스트 (개별 실행 - Docker 기반)
# =============================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

NETWORK="onlyone-back_onlyone-network"
MYSQL_CONTAINER="onlyone-mysql"
BASE_URL="http://app:8080"
JWT_SECRET="7e9eeb12d176a2d72f554c6b096522b4e1a34d799727e45a96f192bbff2a2a851ede29ed24b10b6e6b1835ac94380e2469df99ff9713477bf4d43eeaa9cd16a3"

show_help() {
    echo "============================================"
    echo "  알림/SSE 상세 부하 테스트 (개별 실행)"
    echo "============================================"
    echo ""
    echo "사용법: $0 [테스트명]"
    echo ""
    echo "테스트 목록:"
    echo "  delivery   - SSE 알림 전달 검증 (~10분, 200 VU)"
    echo "  reconnect  - SSE 재연결 복구 검증 (~13분, 200 VU)"
    echo "  lifecycle  - SSE 연결 수명주기 (~15분, 1000 VU)"
    echo "  batch      - 배치 프로세서 포화 (~18분, 500 VU)"
    echo "  conflict   - 동시 쓰기 충돌 (~12분, 100 VU)"
    echo "  mixed      - 운영 시뮬레이션 (~10분, 500 VU)"
    echo "  limit      - SSE 연결 한계 (~19분, 7500 VU)"
    echo ""
    echo "권장 실행 순서:"
    echo "  1. conflict   → DB 동시성 검증"
    echo "  2. delivery   → SSE 전달 확인"
    echo "  3. reconnect  → 재연결 복구"
    echo "  4. mixed      → 운영 시뮬레이션"
    echo "  5. batch      → 배치 포화"
    echo "  6. lifecycle  → 수명주기 스트레스"
    echo "  7. limit      → 연결 한계"
    echo ""
}

# 테스트명 → 파일명 매핑
get_test_file() {
    case "$1" in
        delivery)   echo "sse-delivery-verification-test.js" ;;
        reconnect)  echo "sse-reconnection-recovery-test.js" ;;
        lifecycle)  echo "sse-connection-lifecycle-test.js" ;;
        batch)      echo "sse-batch-saturation-test.js" ;;
        conflict)   echo "notification-concurrent-conflict-test.js" ;;
        mixed)      echo "notification-mixed-user-behavior-test.js" ;;
        limit)      echo "sse-connection-limit-test.js" ;;
        *)          echo "" ;;
    esac
}

# 테스트명 → 시드 SQL 매핑
get_seed_file() {
    case "$1" in
        delivery)   echo "seed-delivery-test.sql" ;;
        reconnect)  echo "seed-delivery-test.sql" ;;
        batch)      echo "seed-batch-saturation.sql" ;;
        conflict)   echo "seed-conflict-test.sql" ;;
        *)          echo "" ;;
    esac
}

# 시드 SQL 실행
run_seed() {
    local seed_file="$1"
    if [ -n "$seed_file" ]; then
        echo ""
        echo "[시드] $seed_file 실행 중..."
        if docker exec -i "$MYSQL_CONTAINER" mysql -uroot -proot onlyone < "$SCRIPT_DIR/$seed_file"; then
            echo "[시드] 완료"
        else
            echo "[시드] 경고: 시드 실행 실패. 계속 진행합니다."
        fi
        echo ""
    fi
}

# k6 테스트 실행
run_k6() {
    local test_name="$1"
    local test_file="$2"

    mkdir -p "$PROJECT_DIR/k6-results"

    echo "============================================"
    echo "[실행] $test_name - $test_file"
    echo "[결과] k6-results/${test_name}-results.json"
    echo "============================================"
    echo ""

    docker run --rm \
        --network "$NETWORK" \
        -v "$PROJECT_DIR":/k6 \
        -e BASE_URL="$BASE_URL" \
        -e JWT_SECRET="$JWT_SECRET" \
        grafana/k6 run \
        --out json=/k6/k6-results/${test_name}-results.json \
        /k6/k6-tests/${test_file}

    echo ""
    echo "============================================"
    echo "[완료] $test_name 테스트 종료"
    echo "[결과] k6-results/${test_name}-results.json"
    echo "============================================"
    echo ""
    echo "다음 단계:"
    echo "  1. k6-results/${test_name}-results.json 확인"
    echo "  2. Grafana 대시보드에서 해당 시간 범위 스냅샷"
    echo "  3. 서버 에러 로그 확인"
    echo ""
}

# =============================================================
# 메인
# =============================================================

TEST="$1"

if [ -z "$TEST" ]; then
    show_help
    echo "테스트를 선택하세요."
    echo "예: $0 delivery"
    exit 1
fi

TEST_FILE=$(get_test_file "$TEST")

if [ -z "$TEST_FILE" ]; then
    echo "알 수 없는 테스트: $TEST"
    echo ""
    echo "사용 가능한 테스트: delivery, reconnect, lifecycle, batch, conflict, mixed, limit"
    exit 1
fi

SEED_FILE=$(get_seed_file "$TEST")

# 시드 실행
run_seed "$SEED_FILE"

# k6 실행
run_k6 "$TEST" "$TEST_FILE"
