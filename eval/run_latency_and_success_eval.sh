#!/usr/bin/env bash
# 真实诊断评估：指标1(端到端耗时) + 指标3(工具调用成功率)
# 对指定 incident 连续触发 N 次真实诊断（force=true，复用线上 durable job + 真实 DashScope）。
# 每次：记录 t0 -> diagnose -> 轮询 report 直到 completed -> t1 = 耗时 -> 拉 evidence 统计 success 率。
set -u
BASE="http://localhost:9900"
INCIDENT_ID="inc-204f25c9-d85"
ALERT_ID="alert-f6a8fa445764c074bfecee7f1d97634b670f1212b39cd051"
N="${1:-5}"
PY="/Users/distancewk/.workbuddy/binaries/python/envs/default/bin/python3"
LAT_FILE="eval/_latencies.txt"
: > "$LAT_FILE"

for ((i=1;i<=N;i++)); do
  t0=$($PY -c "import time;print(int(time.time()*1000))")
  resp=$(curl -s -m 10 -X POST "$BASE/api/incidents/$INCIDENT_ID/diagnose?force=true")
  runId=$(echo "$resp" | $PY -c "import sys,json;print(json.load(sys.stdin)['data']['runId'])")
  echo "[$i] triggered run=$runId"
  status=""
  for ((w=1;w<=120;w++)); do
    rep=$(curl -s -m 10 "$BASE/api/alerts/report/$ALERT_ID")
    status=$(echo "$rep" | $PY -c "import sys,json;d=json.load(sys.stdin);print(d.get('status'))" 2>/dev/null)
    if [ "$status" = "completed" ]; then break; fi
    sleep 2
  done
  t1=$($PY -c "import time;print(int(time.time()*1000))")
  lat=$((t1-t0))
  echo "$lat" >> "$LAT_FILE"
  ev=$(curl -s -m 10 "$BASE/api/incidents/$INCIDENT_ID/runs/$runId/evidence")
  stats=$(echo "$ev" | $PY -c "
import sys,json
d=json.load(sys.stdin)
ev=d['data'] if isinstance(d,dict) and 'data' in d else d
ev=ev if isinstance(ev,list) else []
total=len(ev); ok=sum(1 for e in ev if e.get('success'))
print(f'{ok}/{total}', 0.0 if total==0 else round(ok/total,4))
")
  echo "[$i] latency_ms=$lat status=$status evidence(success/total,rate)=$stats"
done

echo "=== SUMMARY (latency ms) ==="
$PY -c "
import statistics
v=[int(x) for x in open('$LAT_FILE').read().split() if x.strip()]
v.sort(); n=len(v)
if n:
    p95=v[min(n-1,int(n*0.95))]
    print(f'samples={n} min={v[0]} max={v[-1]} avg={round(sum(v)/n)} median={statistics.median(v)} p95={p95}')
"
