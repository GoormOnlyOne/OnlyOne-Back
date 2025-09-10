#!/bin/bash

echo "🔥 Starting K6 Load Test (10,000 Users)..."
echo "=========================================="

# K6 실행 옵션 선택
echo "Select test mode:"
echo "1) Local execution (K6 installed)"
echo "2) Docker execution"
echo "3) Docker with InfluxDB output"
read -p "Choice (1-3): " choice

case $choice in
  1)
    echo "Running K6 locally..."
    k6 run k6-scripts/load-test-10k-users.js
    ;;
  2)
    echo "Running K6 in Docker..."
    docker run --rm -v $(pwd)/k6-scripts:/scripts \
      --network host \
      grafana/k6:latest run /scripts/load-test-10k-users.js
    ;;
  3)
    echo "Running K6 with InfluxDB output..."
    docker run --rm -v $(pwd)/k6-scripts:/scripts \
      --network onlyone-back_monitoring-network \
      grafana/k6:latest run \
      --out influxdb=http://influxdb:8086/k6 \
      /scripts/load-test-10k-users.js
    ;;
  *)
    echo "Invalid choice!"
    exit 1
    ;;
esac

echo ""
echo "📊 Test Results:"
echo "=========================================="
echo "Check Grafana: http://localhost:3000"
echo "Check Jaeger: http://localhost:16686"
echo ""
echo "🔍 37초 문제 확인:"
echo "1. Grafana > Response Time 패널"
echo "2. Jaeger > Min Duration: 30s"
echo "3. Logs: grep '37초 문제' logs/*.log"