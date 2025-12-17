#!/bin/bash

# Nginx 로그를 분석하여 부하 분산 통계를 출력하는 스크립트

echo "===== Nginx 부하 분산 분석 ====="
echo ""

# Docker 컨테이너에서 Nginx 로그 가져오기
echo "📊 최근 1000개의 요청 분석 중..."
echo ""

docker logs onlyone-nginx 2>&1 | grep "upstream:" | tail -1000 > /tmp/nginx_analysis.log

if [ ! -s /tmp/nginx_analysis.log ]; then
    echo "❌ Nginx 로그를 찾을 수 없습니다."
    echo "   먼저 'docker-compose --profile scale up -d' 명령으로 서비스를 시작하세요."
    exit 1
fi

# Upstream 서버별 요청 수 집계
echo "📈 Upstream 서버별 요청 분산:"
echo ""

app1_count=$(grep -o "app1:8080" /tmp/nginx_analysis.log | wc -l)
app2_count=$(grep -o "app2:8080" /tmp/nginx_analysis.log | wc -l)
app3_count=$(grep -o "app3:8080" /tmp/nginx_analysis.log | wc -l)
total=$((app1_count + app2_count + app3_count))

if [ $total -eq 0 ]; then
    echo "❌ 분석할 요청이 없습니다."
    echo "   부하 테스트를 실행하세요: docker-compose --profile load-test up k6"
    exit 1
fi

# 퍼센티지 계산
app1_pct=$(awk "BEGIN {printf \"%.1f\", ($app1_count/$total)*100}")
app2_pct=$(awk "BEGIN {printf \"%.1f\", ($app2_count/$total)*100}")
app3_pct=$(awk "BEGIN {printf \"%.1f\", ($app3_count/$total)*100}")

printf "  app1: %5d 요청 (%5s%%)\n" $app1_count $app1_pct
printf "  app2: %5d 요청 (%5s%%)\n" $app2_count $app2_pct
printf "  app3: %5d 요청 (%5s%%)\n" $app3_count $app3_pct
printf "  총합: %5d 요청\n" $total
echo ""

# 평균 응답 시간
echo "⏱️  평균 응답 시간:"
echo ""

avg_time=$(grep "upstream_response_time:" /tmp/nginx_analysis.log | \
    sed 's/.*upstream_response_time: //' | \
    awk '{sum+=$1; count++} END {if(count>0) printf "%.3f", sum/count; else print "0"}')

echo "  평균: ${avg_time}초"
echo ""

# 상태 코드별 집계
echo "📋 HTTP 상태 코드:"
echo ""

grep "upstream:" /tmp/nginx_analysis.log | \
    awk '{print $9}' | \
    sort | uniq -c | \
    awk '{printf "  %s: %d 요청\n", $2, $1}'

echo ""

# 분산 균형도 평가
echo "✅ 분산 균형도 평가:"
echo ""

# 이상적인 분산은 33.3%씩
ideal=33.3
max_deviation=0

for pct in $app1_pct $app2_pct $app3_pct; do
    deviation=$(awk "BEGIN {printf \"%.1f\", ($pct - $ideal) < 0 ? ($ideal - $pct) : ($pct - $ideal)}")
    max_deviation=$(awk "BEGIN {print ($deviation > $max_deviation) ? $deviation : $max_deviation}")
done

if (( $(awk "BEGIN {print ($max_deviation < 5)}") )); then
    echo "  🟢 매우 좋음 (최대 편차: ${max_deviation}%)"
elif (( $(awk "BEGIN {print ($max_deviation < 10)}") )); then
    echo "  🟡 양호 (최대 편차: ${max_deviation}%)"
else
    echo "  🔴 불균형 (최대 편차: ${max_deviation}%)"
    echo "  ⚠️  ip_hash 알고리즘은 클라이언트 IP 기반으로 분산하므로"
    echo "      클라이언트가 적을 경우 불균형할 수 있습니다."
fi

echo ""

# 정리
rm /tmp/nginx_analysis.log

echo "===== 분석 완료 ====="
