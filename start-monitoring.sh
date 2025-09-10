#!/bin/bash

echo "🚀 Starting Monitoring Stack (Prometheus + Grafana)..."
echo "================================================"

# 모니터링 스택 시작
docker-compose -f docker-compose-monitoring.yml up -d prometheus grafana influxdb

# 서비스 대기
echo "⏳ Waiting for services to start..."
sleep 10

# 상태 확인
echo ""
echo "✅ Services Status:"
docker-compose -f docker-compose-monitoring.yml ps

echo ""
echo "📊 Access URLs:"
echo "================================================"
echo "📈 Grafana: http://localhost:3000 (admin/admin)"
echo "🔍 Prometheus: http://localhost:9090"
echo "📡 Spring Boot Metrics: http://localhost:8080/actuator/prometheus"
echo ""

echo "🎯 HikariCP Metrics in Prometheus:"
echo "================================================"
echo "hikari_connections_active"
echo "hikari_connections_idle"
echo "hikari_connections_pending"
echo "hikari_connections_max"
echo "hikari_connections_min"
echo "hikari_connections_timeout_total"
echo "hikari_connections_acquire_seconds"
echo "hikari_connections_creation_seconds"
echo "hikari_connections_usage_seconds"
echo ""

echo "📝 Import Dashboard to Grafana:"
echo "================================================"
echo "1. Login to Grafana (admin/admin)"
echo "2. Add Data Source > Prometheus > URL: http://prometheus:9090"
echo "3. Import Dashboard > Upload JSON > grafana-hikaricp-dashboard.json"
echo ""

echo "✨ Monitoring is ready!"