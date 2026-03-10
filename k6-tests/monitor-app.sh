#!/bin/bash
# App server monitor — JVM/HikariCP via actuator
command -v python3 >/dev/null 2>&1 || { echo "[ERROR] python3 required"; exit 1; }
DURATION="${1:-1200}"
INTERVAL=10
OUT="/tmp/monitor-app-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT"
APP=http://localhost:8080/actuator/metrics

echo "=== App Monitor === Duration: ${DURATION}s, Output: $OUT"

# Before snapshot
curl -s "$APP/jvm.memory.used?tag=area:heap" > "$OUT/jvm_heap_before.json" 2>/dev/null
curl -s "$APP/jvm.gc.pause" > "$OUT/jvm_gc_before.json" 2>/dev/null

# Sampling
echo "timestamp,hikari_active,hikari_idle,hikari_pending,jvm_heap_mb,gc_count,gc_time_ms,cpu_usage" > "$OUT/app_samples.csv"
END=$(($(date +%s) + DURATION))
while [ $(date +%s) -lt $END ]; do
  TS=$(date +%H:%M:%S)
  HA=$(curl -s "$APP/hikaricp.connections.active" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(int(d['measurements'][0]['value']))" 2>/dev/null || echo 0)
  HI=$(curl -s "$APP/hikaricp.connections.idle" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(int(d['measurements'][0]['value']))" 2>/dev/null || echo 0)
  HP=$(curl -s "$APP/hikaricp.connections.pending" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(int(d['measurements'][0]['value']))" 2>/dev/null || echo 0)
  JM=$(curl -s "$APP/jvm.memory.used?tag=area:heap" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(int(d['measurements'][0]['value']/1048576))" 2>/dev/null || echo 0)
  GC=$(curl -s "$APP/jvm.gc.pause" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); ms=d['measurements']; print(str(int(ms[0]['value']))+','+str(int(ms[1]['value']*1000)))" 2>/dev/null || echo "0,0")
  CPU=$(curl -s "$APP/process.cpu.usage" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(round(d['measurements'][0]['value']*100,1))" 2>/dev/null || echo 0)
  echo "$TS,$HA,$HI,$HP,$JM,$GC,$CPU" >> "$OUT/app_samples.csv"
  sleep $INTERVAL
done

# After snapshot
curl -s "$APP/jvm.memory.used?tag=area:heap" > "$OUT/jvm_heap_after.json" 2>/dev/null
curl -s "$APP/jvm.gc.pause" > "$OUT/jvm_gc_after.json" 2>/dev/null

echo ""
echo "=== App Monitor Summary ==="
echo "-- Last 10 samples --"
tail -10 "$OUT/app_samples.csv" | column -t -s,
echo ""
echo "Data: $OUT"
