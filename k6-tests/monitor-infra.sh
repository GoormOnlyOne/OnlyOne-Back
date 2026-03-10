#!/bin/bash
# Infra server monitor — MySQL/Redis/Kafka via docker exec
DURATION="${1:-1200}"
INTERVAL=10
OUT="/tmp/monitor-infra-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT"
KAFKA_BIN="/opt/kafka/bin"

echo "=== Infra Monitor === Duration: ${DURATION}s, Output: $OUT"

# Before snapshot
docker exec onlyone-mysql mysql -uroot -proot onlyone -N -e "SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Slow_queries','Threads_running','Threads_connected','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Innodb_row_lock_waits','Innodb_row_lock_time','Com_select','Com_insert','Com_update','Com_delete','Innodb_buffer_pool_pages_free','Innodb_buffer_pool_pages_dirty');" 2>/dev/null > "$OUT/mysql_before.txt"
docker exec onlyone-redis redis-cli info stats 2>/dev/null | grep -E "keyspace_hits|keyspace_misses|total_commands|instantaneous_ops" > "$OUT/redis_before.txt"
docker exec onlyone-redis redis-cli info memory 2>/dev/null | grep -E "used_memory:|used_memory_peak:" >> "$OUT/redis_before.txt"

# MySQL sampling
echo "timestamp,threads_running,threads_connected,slow_queries,lock_waits,lock_time_ms,pages_free,pages_dirty" > "$OUT/mysql_samples.csv"
(
END=$(($(date +%s) + DURATION))
while [ $(date +%s) -lt $END ]; do
  TS=$(date +%H:%M:%S)
  ROW=$(docker exec onlyone-mysql mysql -uroot -proot -N -e "SELECT CONCAT( (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_running'), ',', (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected'), ',', (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Slow_queries'), ',', (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_waits'), ',', (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_time'), ',', (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_pages_free'), ',', (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_pages_dirty'));" 2>/dev/null)
  echo "$TS,$ROW" >> "$OUT/mysql_samples.csv"
  sleep $INTERVAL
done
) &
MYSQL_PID=$!

# Redis sampling
echo "timestamp,connected_clients,used_memory_mb,keyspace_hits,keyspace_misses,ops_per_sec" > "$OUT/redis_samples.csv"
(
END=$(($(date +%s) + DURATION))
while [ $(date +%s) -lt $END ]; do
  TS=$(date +%H:%M:%S)
  INFO=$(docker exec onlyone-redis redis-cli info stats 2>/dev/null)
  MEM_INFO=$(docker exec onlyone-redis redis-cli info memory 2>/dev/null)
  MEM=$(echo "$MEM_INFO" | grep "used_memory:" | head -1 | cut -d: -f2 | tr -d '\r')
  MEM_MB=$((${MEM:-0} / 1048576))
  CLIENTS=$(echo "$INFO" | grep "connected_clients:" | cut -d: -f2 | tr -d '\r')
  HITS=$(echo "$INFO" | grep "keyspace_hits:" | cut -d: -f2 | tr -d '\r')
  MISSES=$(echo "$INFO" | grep "keyspace_misses:" | cut -d: -f2 | tr -d '\r')
  OPS=$(echo "$INFO" | grep "instantaneous_ops_per_sec:" | cut -d: -f2 | tr -d '\r')
  echo "$TS,${CLIENTS:-0},${MEM_MB},${HITS:-0},${MISSES:-0},${OPS:-0}" >> "$OUT/redis_samples.csv"
  sleep $INTERVAL
done
) &
REDIS_PID=$!

# Kafka sampling
echo "timestamp,settle_process_lag,settle_result_lag,settle_process_offset,settle_result_offset" > "$OUT/kafka_samples.csv"
(
END=$(($(date +%s) + DURATION))
while [ $(date +%s) -lt $END ]; do
  TS=$(date +%H:%M:%S)

  # settlement.process.v1 consumer group lag
  SP_RAW=$(docker exec onlyone-kafka $KAFKA_BIN/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group settlement-orchestrator 2>/dev/null | grep "settlement.process.v1")
  SP_LAG=$(echo "$SP_RAW" | awk '{sum+=$6} END{print sum+0}')
  SP_OFF=$(echo "$SP_RAW" | awk '{sum+=$4} END{print sum+0}')

  # user-settlement.result.v1 consumer group lag
  SR_RAW=$(docker exec onlyone-kafka $KAFKA_BIN/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group ledger-writer 2>/dev/null | grep "user-settlement.result.v1")
  SR_LAG=$(echo "$SR_RAW" | awk '{sum+=$6} END{print sum+0}')
  SR_OFF=$(echo "$SR_RAW" | awk '{sum+=$4} END{print sum+0}')

  echo "$TS,${SP_LAG:-0},${SR_LAG:-0},${SP_OFF:-0},${SR_OFF:-0}" >> "$OUT/kafka_samples.csv"
  sleep $INTERVAL
done
) &
KAFKA_PID=$!

wait $MYSQL_PID $REDIS_PID $KAFKA_PID 2>/dev/null

# After snapshot
docker exec onlyone-mysql mysql -uroot -proot onlyone -N -e "SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Slow_queries','Threads_running','Threads_connected','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests','Innodb_row_lock_waits','Innodb_row_lock_time','Com_select','Com_insert','Com_update','Com_delete','Innodb_buffer_pool_pages_free','Innodb_buffer_pool_pages_dirty');" 2>/dev/null > "$OUT/mysql_after.txt"
docker exec onlyone-redis redis-cli info stats 2>/dev/null | grep -E "keyspace_hits|keyspace_misses|total_commands|instantaneous_ops" > "$OUT/redis_after.txt"
docker exec onlyone-redis redis-cli info memory 2>/dev/null | grep -E "used_memory:|used_memory_peak:" >> "$OUT/redis_after.txt"

echo ""
echo "=== Infra Monitor Summary ==="
echo "-- MySQL Before/After --"
echo "BEFORE:"; cat "$OUT/mysql_before.txt"
echo "AFTER:"; cat "$OUT/mysql_after.txt"
echo ""
echo "-- Redis Before/After --"
echo "BEFORE:"; cat "$OUT/redis_before.txt"
echo "AFTER:"; cat "$OUT/redis_after.txt"
echo ""
echo "-- MySQL Last 10 samples --"
tail -10 "$OUT/mysql_samples.csv" | column -t -s,
echo ""
echo "-- Redis Last 10 samples --"
tail -10 "$OUT/redis_samples.csv" | column -t -s,
echo ""
echo "-- Kafka Last 10 samples --"
tail -10 "$OUT/kafka_samples.csv" | column -t -s,
echo ""
echo "Data: $OUT"
