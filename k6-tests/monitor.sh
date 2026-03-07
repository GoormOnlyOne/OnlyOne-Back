#!/bin/bash
# 피드 성능 테스트 종합 모니터링 스크립트
# APP_HOST, INFRA_HOST, DURATION(초) 환경변수 필요
# 사용: ./monitor.sh <app_private_ip> <infra_private_ip> <duration_seconds>

APP_IP="${1:-172.31.54.84}"
INFRA_IP="${2:-172.31.54.1}"
DURATION="${3:-360}"
INTERVAL=10
OUT_DIR="/tmp/feed-monitor-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT_DIR"

echo "=== Feed Performance Monitor ==="
echo "App: $APP_IP | Infra: $INFRA_IP | Duration: ${DURATION}s | Interval: ${INTERVAL}s"
echo "Output: $OUT_DIR"
echo ""

# ── BEFORE 스냅샷 ──
echo "[BEFORE] Capturing baseline..."

# MySQL status before
mysql -h "$INFRA_IP" -u root -proot onlyone -e "
  SHOW GLOBAL STATUS WHERE Variable_name IN (
    'Questions','Slow_queries','Threads_running','Threads_connected',
    'Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests',
    'Innodb_row_lock_waits','Innodb_row_lock_time',
    'Innodb_buffer_pool_pages_total','Innodb_buffer_pool_pages_data',
    'Innodb_buffer_pool_pages_free','Innodb_buffer_pool_pages_dirty',
    'Com_select','Com_insert','Com_update','Com_delete',
    'Created_tmp_tables','Created_tmp_disk_tables',
    'Select_full_join','Select_scan'
  );
" 2>/dev/null > "$OUT_DIR/mysql_before.txt"

# Redis info before
redis-cli -h "$INFRA_IP" info stats 2>/dev/null | grep -E "keyspace_hits|keyspace_misses|total_commands|used_memory|connected_clients" > "$OUT_DIR/redis_before.txt"
redis-cli -h "$INFRA_IP" info memory 2>/dev/null | grep -E "used_memory:|used_memory_peak:|used_memory_rss:" >> "$OUT_DIR/redis_before.txt"
redis-cli -h "$INFRA_IP" dbsize 2>/dev/null >> "$OUT_DIR/redis_before.txt"

# JVM/App before (actuator)
curl -s "http://$APP_IP:8080/actuator/metrics/jvm.memory.used" > "$OUT_DIR/jvm_mem_before.json" 2>/dev/null
curl -s "http://$APP_IP:8080/actuator/metrics/jvm.gc.pause" > "$OUT_DIR/jvm_gc_before.json" 2>/dev/null
curl -s "http://$APP_IP:8080/actuator/metrics/hikaricp.connections.active" > "$OUT_DIR/hikari_before.json" 2>/dev/null

echo "[BEFORE] Done."

# ── 실시간 샘플링 (백그라운드) ──
echo "[SAMPLING] Starting ${DURATION}s monitoring..."

# CPU/Memory 샘플링
(
  echo "timestamp,cpu_user,cpu_sys,cpu_idle,mem_used_mb,mem_free_mb,load_1m" > "$OUT_DIR/os_samples.csv"
  END=$(($(date +%s) + DURATION))
  while [ $(date +%s) -lt $END ]; do
    TS=$(date +%H:%M:%S)
    CPU=$(top -bn1 | grep "Cpu(s)" | awk '{printf "%.1f,%.1f,%.1f", $2, $4, $8}')
    MEM=$(free -m | awk '/Mem:/{printf "%d,%d", $3, $4}')
    LOAD=$(cat /proc/loadavg | awk '{print $1}')
    echo "$TS,$CPU,$MEM,$LOAD" >> "$OUT_DIR/os_samples.csv"
    sleep $INTERVAL
  done
) &
OS_PID=$!

# MySQL Threads_running 샘플링
(
  echo "timestamp,threads_running,threads_connected,innodb_rows_read,slow_queries" > "$OUT_DIR/mysql_samples.csv"
  END=$(($(date +%s) + DURATION))
  while [ $(date +%s) -lt $END ]; do
    TS=$(date +%H:%M:%S)
    ROW=$(mysql -h "$INFRA_IP" -u root -proot -N -e "
      SELECT CONCAT(
        (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_running'), ',',
        (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected'), ',',
        (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_rows_read'), ',',
        (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Slow_queries')
      );
    " 2>/dev/null)
    echo "$TS,$ROW" >> "$OUT_DIR/mysql_samples.csv"
    sleep $INTERVAL
  done
) &
MYSQL_PID=$!

# HikariCP + JVM 샘플링
(
  echo "timestamp,hikari_active,hikari_idle,hikari_pending,jvm_heap_mb,gc_count,gc_time_ms" > "$OUT_DIR/jvm_samples.csv"
  END=$(($(date +%s) + DURATION))
  while [ $(date +%s) -lt $END ]; do
    TS=$(date +%H:%M:%S)
    HA=$(curl -s "http://$APP_IP:8080/actuator/metrics/hikaricp.connections.active" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(int(d['measurements'][0]['value']))" 2>/dev/null || echo 0)
    HI=$(curl -s "http://$APP_IP:8080/actuator/metrics/hikaricp.connections.idle" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(int(d['measurements'][0]['value']))" 2>/dev/null || echo 0)
    HP=$(curl -s "http://$APP_IP:8080/actuator/metrics/hikaricp.connections.pending" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(int(d['measurements'][0]['value']))" 2>/dev/null || echo 0)
    JM=$(curl -s "http://$APP_IP:8080/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(int(d['measurements'][0]['value']/1048576))" 2>/dev/null || echo 0)
    GC=$(curl -s "http://$APP_IP:8080/actuator/metrics/jvm.gc.pause" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); ms=d['measurements']; print(str(int(ms[0]['value']))+','+str(int(ms[1]['value']*1000)))" 2>/dev/null || echo "0,0")
    echo "$TS,$HA,$HI,$HP,$JM,$GC" >> "$OUT_DIR/jvm_samples.csv"
    sleep $INTERVAL
  done
) &
JVM_PID=$!

# Redis 샘플링
(
  echo "timestamp,connected_clients,used_memory_mb,keyspace_hits,keyspace_misses,ops_per_sec" > "$OUT_DIR/redis_samples.csv"
  END=$(($(date +%s) + DURATION))
  while [ $(date +%s) -lt $END ]; do
    TS=$(date +%H:%M:%S)
    INFO=$(redis-cli -h "$INFRA_IP" info stats 2>/dev/null)
    MEM=$(redis-cli -h "$INFRA_IP" info memory 2>/dev/null | grep "used_memory:" | cut -d: -f2 | tr -d '\r')
    MEM_MB=$((${MEM:-0} / 1048576))
    CLIENTS=$(echo "$INFO" | grep "connected_clients:" | cut -d: -f2 | tr -d '\r')
    HITS=$(echo "$INFO" | grep "keyspace_hits:" | cut -d: -f2 | tr -d '\r')
    MISSES=$(echo "$INFO" | grep "keyspace_misses:" | cut -d: -f2 | tr -d '\r')
    OPS=$(echo "$INFO" | grep "instantaneous_ops_per_sec:" | cut -d: -f2 | tr -d '\r')
    echo "$TS,${CLIENTS:-0},${MEM_MB},${HITS:-0},${MISSES:-0},${OPS:-0}" >> "$OUT_DIR/redis_samples.csv"
    sleep $INTERVAL
  done
) &
REDIS_PID=$!

# 대기
wait $OS_PID $MYSQL_PID $JVM_PID $REDIS_PID 2>/dev/null

# ── AFTER 스냅샷 ──
echo ""
echo "[AFTER] Capturing final state..."

mysql -h "$INFRA_IP" -u root -proot onlyone -e "
  SHOW GLOBAL STATUS WHERE Variable_name IN (
    'Questions','Slow_queries','Threads_running','Threads_connected',
    'Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests',
    'Innodb_row_lock_waits','Innodb_row_lock_time',
    'Innodb_buffer_pool_pages_total','Innodb_buffer_pool_pages_data',
    'Innodb_buffer_pool_pages_free','Innodb_buffer_pool_pages_dirty',
    'Com_select','Com_insert','Com_update','Com_delete',
    'Created_tmp_tables','Created_tmp_disk_tables',
    'Select_full_join','Select_scan'
  );
" 2>/dev/null > "$OUT_DIR/mysql_after.txt"

redis-cli -h "$INFRA_IP" info stats 2>/dev/null | grep -E "keyspace_hits|keyspace_misses|total_commands|used_memory|connected_clients" > "$OUT_DIR/redis_after.txt"
redis-cli -h "$INFRA_IP" info memory 2>/dev/null | grep -E "used_memory:|used_memory_peak:|used_memory_rss:" >> "$OUT_DIR/redis_after.txt"
redis-cli -h "$INFRA_IP" dbsize 2>/dev/null >> "$OUT_DIR/redis_after.txt"

curl -s "http://$APP_IP:8080/actuator/metrics/jvm.memory.used" > "$OUT_DIR/jvm_mem_after.json" 2>/dev/null
curl -s "http://$APP_IP:8080/actuator/metrics/jvm.gc.pause" > "$OUT_DIR/jvm_gc_after.json" 2>/dev/null
curl -s "http://$APP_IP:8080/actuator/metrics/hikaricp.connections.active" > "$OUT_DIR/hikari_after.json" 2>/dev/null

echo "[AFTER] Done."

# ── 요약 리포트 ──
echo ""
echo "═══════════════════════════════════════════════════════"
echo "  종합 모니터링 리포트"
echo "═══════════════════════════════════════════════════════"

echo ""
echo "── OS (CPU/Memory) ──"
echo "  Samples:"
tail -5 "$OUT_DIR/os_samples.csv" | column -t -s,
echo ""

echo "── MySQL ──"
echo "  Before/After diff:"
paste "$OUT_DIR/mysql_before.txt" "$OUT_DIR/mysql_after.txt" 2>/dev/null | head -25
echo ""

echo "── JVM/HikariCP ──"
echo "  Samples:"
tail -5 "$OUT_DIR/jvm_samples.csv" | column -t -s,
echo ""

echo "── Redis ──"
echo "  Samples:"
tail -5 "$OUT_DIR/redis_samples.csv" | column -t -s,
echo ""

echo "Full data: $OUT_DIR"
echo "═══════════════════════════════════════════════════════"
