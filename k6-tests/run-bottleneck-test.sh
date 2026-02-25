#!/bin/bash
# ===========================================
# 알림/SSE 병목점 탐지 부하 테스트 실행
# ===========================================

set -e

BASE_URL="${BASE_URL:-http://localhost:8888}"
JWT_SECRET="${JWT_SECRET:-7e9eeb12d176a2d72f554c6b096522b4e1a34d799727e45a96f192bbff2a2a851ede29ed24b10b6e6b1835ac94380e2469df99ff9713477bf4d43eeaa9cd16a3}"

echo "============================================"
echo "  알림/SSE 병목점 탐지 부하 테스트"
echo "============================================"
echo ""

# 결과 디렉터리
mkdir -p ../k6-results

# 헬스체크
echo "[1/2] 서버 헬스체크..."
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health" 2>/dev/null || echo "000")
if [ "$STATUS" != "200" ]; then
    echo "  서버가 응답하지 않습니다: $BASE_URL (status=$STATUS)"
    exit 1
fi
echo "  서버 정상"

echo ""
echo "[2/2] k6 부하 테스트 시작 (약 9분)"
echo "  BASE_URL=$BASE_URL"
echo ""

k6 run \
  -e BASE_URL="$BASE_URL" \
  -e JWT_SECRET="$JWT_SECRET" \
  bottleneck-test.js

echo ""
echo "============================================"
echo "  테스트 완료 — 결과: k6-results/bottleneck-result.json"
echo "============================================"
