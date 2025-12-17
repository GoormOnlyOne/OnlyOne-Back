#!/bin/bash

# K6 부하 테스트 실행 스크립트
# 각 테스트를 개별적으로 실행하거나 전체를 순차적으로 실행할 수 있습니다

BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"
INFLUXDB_URL="${INFLUXDB_URL:-http://onlyone-influxdb:8086/k6}"

echo "=========================================="
echo "K6 부하 테스트 실행"
echo "Base URL: $BASE_URL"
echo "InfluxDB: $INFLUXDB_URL"
echo "=========================================="

# 테스트 선택
case "$1" in
  1|sse)
    echo "1️⃣  SSE 연결 테스트 실행..."
    docker run --rm \
      --network onlyone-network \
      -v "$(pwd)":/scripts \
      -e BASE_URL="$BASE_URL" \
      xk6-sse:local run \
      --out "influxdb=$INFLUXDB_URL" \
      /scripts/1-sse-connection-test.js
    ;;

  2|notification)
    echo "2️⃣  알림 생성 테스트 실행..."
    docker run --rm \
      --network onlyone-network \
      -v "$(pwd)":/scripts \
      -e BASE_URL="$BASE_URL" \
      xk6-sse:local run \
      --out "influxdb=$INFLUXDB_URL" \
      /scripts/2-notification-create-test.js
    ;;

  3|api)
    echo "3️⃣  API 조회 테스트 실행..."
    docker run --rm \
      --network onlyone-network \
      -v "$(pwd)":/scripts \
      -e BASE_URL="$BASE_URL" \
      xk6-sse:local run \
      --out "influxdb=$INFLUXDB_URL" \
      /scripts/3-api-query-test.js
    ;;

  all)
    echo "🔄 전체 테스트 순차 실행..."
    echo ""

    echo "1️⃣  SSE 연결 테스트..."
    bash "$0" sse
    echo ""
    sleep 10

    echo "2️⃣  알림 생성 테스트..."
    bash "$0" notification
    echo ""
    sleep 10

    echo "3️⃣  API 조회 테스트..."
    bash "$0" api
    echo ""

    echo "✅ 전체 테스트 완료!"
    ;;

  *)
    echo "사용법: $0 {1|sse|2|notification|3|api|all}"
    echo ""
    echo "개별 테스트:"
    echo "  $0 1 또는 $0 sse          - SSE 연결 테스트만 실행"
    echo "  $0 2 또는 $0 notification - 알림 생성 테스트만 실행"
    echo "  $0 3 또는 $0 api          - API 조회 테스트만 실행"
    echo ""
    echo "전체 테스트:"
    echo "  $0 all                    - 모든 테스트 순차 실행"
    echo ""
    exit 1
    ;;
esac

echo ""
echo "=========================================="
echo "테스트 완료!"
echo "Grafana: http://localhost:3333"
echo "Jaeger: http://localhost:16686"
echo "=========================================="
