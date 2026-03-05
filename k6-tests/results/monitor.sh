#!/bin/bash
# 15초 간격 시스템 모니터링
LOG_DIR="$(dirname "$0")"
LOG_FILE="$LOG_DIR/monitor_$(date +%Y%m%d_%H%M%S).log"

echo "=== 모니터링 시작 $(date) ===" > "$LOG_FILE"

while true; do
    echo "" >> "$LOG_FILE"
    echo "--- $(date '+%H:%M:%S') ---" >> "$LOG_FILE"

    # 1. MySQL InnoDB row lock
    echo "[MySQL Row Lock]" >> "$LOG_FILE"
    docker exec onlyone-mysql mysql -uroot -proot -e \
        "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_row_lock_current_waits','Innodb_deadlocks','Threads_connected','Threads_running');" 2>/dev/null >> "$LOG_FILE"

    # 2. MySQL active queries
    echo "[MySQL Active Queries]" >> "$LOG_FILE"
    docker exec onlyone-mysql mysql -uroot -proot -e \
        "SELECT COUNT(*) as active_queries FROM information_schema.PROCESSLIST WHERE COMMAND != 'Sleep';" 2>/dev/null >> "$LOG_FILE"

    # 3. HikariCP
    echo "[HikariCP]" >> "$LOG_FILE"
    active=$(curl -sf "http://localhost:8080/actuator/metrics/hikaricp.connections.active" 2>/dev/null | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)
    idle=$(curl -sf "http://localhost:8080/actuator/metrics/hikaricp.connections.idle" 2>/dev/null | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)
    pending=$(curl -sf "http://localhost:8080/actuator/metrics/hikaricp.connections.pending" 2>/dev/null | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)
    echo "  active=$active idle=$idle pending=$pending" >> "$LOG_FILE"

    # 4. JVM Threads
    echo "[JVM Threads]" >> "$LOG_FILE"
    live=$(curl -sf "http://localhost:8080/actuator/metrics/jvm.threads.live" 2>/dev/null | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)
    peak=$(curl -sf "http://localhost:8080/actuator/metrics/jvm.threads.peak" 2>/dev/null | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)
    started=$(curl -sf "http://localhost:8080/actuator/metrics/jvm.threads.started" 2>/dev/null | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)
    echo "  live=$live peak=$peak started=$started" >> "$LOG_FILE"

    # 5. JVM Memory
    echo "[JVM Memory]" >> "$LOG_FILE"
    heap_used=$(curl -sf "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)
    heap_max=$(curl -sf "http://localhost:8080/actuator/metrics/jvm.memory.max?tag=area:heap" 2>/dev/null | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)
    if [ -n "$heap_used" ] && [ -n "$heap_max" ]; then
        heap_used_mb=$(echo "scale=0; $heap_used / 1048576" | bc 2>/dev/null || echo "?")
        heap_max_mb=$(echo "scale=0; $heap_max / 1048576" | bc 2>/dev/null || echo "?")
        echo "  heap_used=${heap_used_mb}MB heap_max=${heap_max_mb}MB" >> "$LOG_FILE"
    fi

    # 6. System CPU
    echo "[CPU]" >> "$LOG_FILE"
    sys_cpu=$(curl -sf "http://localhost:8080/actuator/metrics/system.cpu.usage" 2>/dev/null | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)
    proc_cpu=$(curl -sf "http://localhost:8080/actuator/metrics/process.cpu.usage" 2>/dev/null | grep -o '"value":[0-9.]*' | head -1 | cut -d: -f2)
    echo "  system_cpu=$sys_cpu process_cpu=$proc_cpu" >> "$LOG_FILE"

    # 7. GC
    echo "[GC]" >> "$LOG_FILE"
    gc_pause=$(curl -sf "http://localhost:8080/actuator/metrics/jvm.gc.pause" 2>/dev/null | grep -o '"statistic":"COUNT","value":[0-9.]*' | head -1 | cut -d: -f3)
    gc_time=$(curl -sf "http://localhost:8080/actuator/metrics/jvm.gc.pause" 2>/dev/null | grep -o '"statistic":"TOTAL_TIME","value":[0-9.]*' | head -1 | cut -d: -f3)
    echo "  gc_count=$gc_pause gc_total_time=$gc_time" >> "$LOG_FILE"

    sleep 15
done
