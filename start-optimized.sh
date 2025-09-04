#!/bin/bash

# OnlyOne Backend 최적화 시작 스크립트
# 600+ 동시 사용자 대응을 위한 JVM 튜닝 및 GC 최적화

echo "Starting OnlyOne Backend with optimized settings..."
echo "Target: 600+ concurrent users, DAU 50,000"
echo "========================================="

# JVM 기본 메모리 설정 (600+ 동시 사용자 대응)
JAVA_OPTS="-Xms3g -Xmx6g"  # 초기 3GB, 최대 6GB (기존 4g-8g에서 조정)
JAVA_OPTS="$JAVA_OPTS -XX:NewRatio=2"  # Old:Young = 2:1 (안정적인 비율)

# G1GC 최적화 설정 (대량 동시 접속 최적화)
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC"
JAVA_OPTS="$JAVA_OPTS -XX:MaxGCPauseMillis=100"      # 200ms → 100ms (더 빠른 응답)
JAVA_OPTS="$JAVA_OPTS -XX:G1HeapRegionSize=16m"      # 16MB 리전 유지
JAVA_OPTS="$JAVA_OPTS -XX:G1NewSizePercent=30"       # Young Gen 30%
JAVA_OPTS="$JAVA_OPTS -XX:G1MaxNewSizePercent=50"    # Young Gen 최대 50%
JAVA_OPTS="$JAVA_OPTS -XX:G1MixedGCCountTarget=8"    # Mixed GC 횟수 유지
JAVA_OPTS="$JAVA_OPTS -XX:InitiatingHeapOccupancyPercent=45"  # GC 시작 임계값

# 메모리 관리 최적화 (600+ 사용자 대응 강화)
JAVA_OPTS="$JAVA_OPTS -XX:+UnlockExperimentalVMOptions"
JAVA_OPTS="$JAVA_OPTS -XX:+UseStringDeduplication"        # 문자열 중복 제거
JAVA_OPTS="$JAVA_OPTS -XX:+ParallelRefProcEnabled"       # 병렬 참조 처리
JAVA_OPTS="$JAVA_OPTS -XX:+OptimizeStringConcat"         # 문자열 연결 최적화

# 컴파일러 최적화 (JIT)
JAVA_OPTS="$JAVA_OPTS -XX:+UseCompressedOops"           # 압축된 포인터 사용
JAVA_OPTS="$JAVA_OPTS -XX:+UseCompressedClassPointers"
JAVA_OPTS="$JAVA_OPTS -XX:+TieredCompilation"           # 계층형 컴파일
JAVA_OPTS="$JAVA_OPTS -XX:TieredStopAtLevel=4"          # C2 컴파일러까지

# 스레드 및 네트워킹 최적화 (600+ 동시 사용자)
JAVA_OPTS="$JAVA_OPTS -Djava.awt.headless=true"
JAVA_OPTS="$JAVA_OPTS -Djava.net.preferIPv4Stack=true"
JAVA_OPTS="$JAVA_OPTS -Dnetworkaddress.cache.ttl=60"
JAVA_OPTS="$JAVA_OPTS -Dnetworkaddress.cache.negative.ttl=10"

# Tomcat 최적화 (application.yml 설정과 동기화)
JAVA_OPTS="$JAVA_OPTS -Dserver.tomcat.accept-count=2000"     # 2000 (증가)
JAVA_OPTS="$JAVA_OPTS -Dserver.tomcat.max-connections=12000" # 12000 (증가)
JAVA_OPTS="$JAVA_OPTS -Dserver.tomcat.threads.max=150"      # 150 (증가)

# 보안 최적화
JAVA_OPTS="$JAVA_OPTS -Djava.security.egd=file:/dev/./urandom"

# 스레드 최적화
JAVA_OPTS="$JAVA_OPTS -XX:+UseBiasedLocking"              # 편향 잠금 사용
JAVA_OPTS="$JAVA_OPTS -XX:BiasedLockingStartupDelay=0"

# GC 로깅 및 모니터링 (성능 분석용)
JAVA_OPTS="$JAVA_OPTS -XX:+UnlockDiagnosticVMOptions"
JAVA_OPTS="$JAVA_OPTS -Xlog:gc*:logs/gc-performance.log:time,level,tags"

# JMX 모니터링 (운영 환경 모니터링)
JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote"
JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.port=9999"
JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.authenticate=false"
JAVA_OPTS="$JAVA_OPTS -Dcom.sun.management.jmxremote.ssl=false"

# 로그 디렉토리 생성
mkdir -p logs

echo "JVM Options:"
echo "$JAVA_OPTS"
echo "========================================="

echo "Starting application with optimized JVM settings..."
export JAVA_OPTS
./gradlew bootRun

echo ""
echo "OnlyOne Backend started with optimized performance settings"
echo "Monitoring available at JMX port 9999"
echo "GC logs: logs/gc-performance.log"
echo "Performance log: logs/onlyone-performance.log"