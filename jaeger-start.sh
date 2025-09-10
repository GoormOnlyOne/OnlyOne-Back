#!/bin/bash

echo "🚀 Starting Jaeger Tracing System..."
echo "====================================="

# Docker Compose 실행
docker-compose -f docker-compose-jaeger.yml up -d

# 서비스 시작 대기
echo "⏳ Waiting for services to start..."
sleep 5

# 상태 확인
echo ""
echo "✅ Services Status:"
docker-compose -f docker-compose-jaeger.yml ps

echo ""
echo "📊 Jaeger Access URLs:"
echo "====================================="
echo "🌐 Jaeger UI: http://localhost:16686"
echo "📈 Query API: http://localhost:16687"
echo "📡 OTLP HTTP: http://localhost:4318"
echo "🔌 Zipkin: http://localhost:9411"
echo ""
echo "📝 Spring Boot Config:"
echo "====================================="
echo "Add to application.yml:"
echo "  management.tracing.sampling.probability: 1.0"
echo "  management.otlp.tracing.endpoint: http://localhost:4318/v1/traces"
echo ""
echo "🎯 Usage:"
echo "====================================="
echo "1. Start your Spring Boot app"
echo "2. Make some API calls"
echo "3. Open http://localhost:16686 to view traces"
echo "4. Search by Service: 'onlyone-backend'"
echo ""
echo "✨ Jaeger is ready!"