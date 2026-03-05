#!/bin/bash
# =============================================================
# Elasticsearch 시드 데이터 (reindex 트리거)
# =============================================================
# 사전조건:
#   1. ES nori 플러그인 설치됨
#   2. seed-search.sql로 MySQL에 클럽 20K 생성됨
#   3. 앱 서버 실행 중
#
# 실행: ./k6-tests/seed-elasticsearch.sh
# =============================================================

set -e

BASE_URL="${BASE_URL:-http://localhost:8080}"
ES_URL="${ES_URL:-http://localhost:9200}"
ES_USER="${ES_USER:-elastic}"
ES_PASS="${ES_PASS:-changeme}"

echo "=== Elasticsearch 시드 데이터 ==="

# 1) nori 플러그인 확인
echo "--- nori 플러그인 확인 ---"
PLUGINS=$(curl -sf -u "$ES_USER:$ES_PASS" "$ES_URL/_cat/plugins?format=json" 2>/dev/null || echo "[]")
if echo "$PLUGINS" | grep -q "analysis-nori"; then
    echo "  nori 플러그인: OK"
else
    echo "  nori 플러그인 미설치. 설치합니다..."
    docker exec onlyone-elasticsearch elasticsearch-plugin install analysis-nori -b
    echo "  ES 재시작 중..."
    docker restart onlyone-elasticsearch
    sleep 15
    echo "  nori 플러그인 설치 완료"
fi

# 2) 기존 인덱스 상태 확인
echo "--- 현재 인덱스 상태 ---"
curl -sf -u "$ES_USER:$ES_PASS" "$ES_URL/_cat/indices?v&index=club*" 2>/dev/null || echo "  (인덱스 없음)"

# 3) reindex 요청 (앱 API)
echo ""
echo "--- Reindex 요청 ---"
RESPONSE=$(curl -sf -X POST "$BASE_URL/api/v1/admin/search/reindex" \
    -H "Content-Type: application/json" 2>/dev/null || echo "FAILED")

if [ "$RESPONSE" = "FAILED" ]; then
    echo "  앱 API reindex 실패. 수동 확인 필요."
    echo "  앱 서버가 실행 중인지 확인: curl $BASE_URL/actuator/health"
else
    echo "  Reindex 요청 완료: $RESPONSE"
fi

# 4) 인덱스 검증 (10초 대기 후)
echo ""
echo "--- 인덱스 검증 (10초 대기) ---"
sleep 10

DOC_COUNT=$(curl -sf -u "$ES_USER:$ES_PASS" "$ES_URL/club/_count" 2>/dev/null | grep -o '"count":[0-9]*' | grep -o '[0-9]*')
echo "  club 인덱스 문서 수: ${DOC_COUNT:-0}"

if [ "${DOC_COUNT:-0}" -gt 1000 ]; then
    echo "  검증 OK (1,000개 이상)"
else
    echo "  문서 수 부족. reindex 진행 중일 수 있으니 잠시 후 재확인."
fi

echo ""
echo "=== 완료 ==="
