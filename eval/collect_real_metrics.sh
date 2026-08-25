#!/usr/bin/env bash
# 汇总真实历史诊断的指标1(端到端耗时) + 指标3(工具调用成功率)
# 数据来自 /api/incidents/{id}/runs（真实跑过的诊断）与每条 run 的 evidence。
set -u
BASE="http://localhost:9900"
INCIDENT_ID="inc-204f25c9-d85"
PY="/Users/distancewk/.workbuddy/binaries/python/envs/default/bin/python3"
curl -s -m10 "$BASE/api/incidents/$INCIDENT_ID/runs" > /tmp/runs.json

echo "=== 指标1: 端到端诊断耗时 (startedAt -> completedAt) ==="
$PY -c "
import json,statistics
d=json.load(open('/tmp/runs.json'))
runs=d if isinstance(d,list) else d.get('data',[])
def sec(r):
    sa,ca=r.get('startedAt',0),r.get('completedAt',0)
    return (ca-sa)/1000 if sa and ca else None
completed=sorted(s for s in (sec(r) for r in runs if r.get('status')=='COMPLETED') if s)
gaps=sorted(s for s in (sec(r) for r in runs if r.get('status')=='COMPLETED_WITH_GAPS') if s)
print(f'COMPLETED runs: n={len(completed)} min={min(completed):.1f} max={max(completed):.1f} avg={sum(completed)/len(completed):.1f} median={statistics.median(completed):.1f}')
if completed:
    p95=completed[min(len(completed)-1,int(len(completed)*0.95))]
    print(f'  P95={p95:.1f}s')
if gaps:
    print(f'COMPLETED_WITH_GAPS runs: n={len(gaps)} values={[round(x,1) for x in gaps]}')
allok=completed+gaps
if allok:
    print(f'全部成功完成(COMPLETED+GAPS) n={len(allok)} avg={sum(allok)/len(allok):.1f}s')
print(f'FAILED runs (不计耗时): {sum(1 for r in runs if r.get(\"status\")==\"FAILED\")}')
"

echo "=== 指标3: 工具调用成功率 (跨所有 run 的 evidence 聚合) ==="
runIds=$($PY -c "import json;d=json.load(open('/tmp/runs.json'));runs=d if isinstance(d,list) else d.get('data',[]);print(' '.join(r['runId'] for r in runs))")
tot=0; ok=0
for rid in $runIds; do
  ev=$(curl -s -m10 "$BASE/api/incidents/$INCIDENT_ID/runs/$rid/evidence")
  ro=$($PY -c "import sys,json;d=json.load(sys.stdin);ev=d['data'] if isinstance(d,dict) and 'data' in d else d;ev=ev if isinstance(ev,list) else [];t=len(ev);o=sum(1 for e in ev if e.get('success'));print(t,o)")
  set -- $ro; t=$1; o=$2
  tot=$((tot+t)); ok=$((ok+o))
done
echo "TOOL calls success: $ok / $tot"
$PY -c "print(f'overall tool success rate = {($ok/$tot if $tot else 0):.4f}')"
