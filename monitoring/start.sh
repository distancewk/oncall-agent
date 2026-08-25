#!/usr/bin/env bash
# 启动本地监控接入栈，并展示当前 firing 的告警。
# 注意：本脚本只负责监控栈本身（Prometheus/Alertmanager/node_exporter）。
# 要让告警真正推送到 SuperBizAgent，还需在宿主机把 App 跑在 9900，
# 且启动时设置 APP_WEBHOOK_SECRET=dev-webhook-secret-2026（与 alertmanager.yml 一致）。
set -euo pipefail
cd "$(dirname "$0")"

echo ">>> 启动监控栈 (prometheus + alertmanager + node_exporter) ..."
docker compose up -d

echo ">>> 等待 Prometheus 就绪 ..."
for i in $(seq 1 30); do
  if curl -s -o /dev/null --max-time 3 http://localhost:9090/-/ready; then
    echo "    Prometheus 已就绪"
    break
  fi
  sleep 2
done

echo ">>> 当前 firing 告警："
curl -s http://localhost:9090/api/v1/alerts | \
  python3 -c "import sys,json; d=json.load(sys.stdin); al=d['data']['alerts'];
print('  无 firing 告警' if not al else '');
[print(f\"  - {a['labels']['alertname']} [{a['labels']['severity']}] {a['state']}\") for a in al]" \
  2>/dev/null || curl -s http://localhost:9090/api/v1/alerts

echo ""
echo ">>> 访问入口："
echo "    Prometheus  UI : http://localhost:9090"
echo "    Alertmanager UI: http://localhost:9093"
echo ""
echo ">>> 下一步：把 SuperBizAgent 跑在本地 9900（APP_WEBHOOK_SECRET=dev-webhook-secret-2026），"
echo ">>> 上述 firing 告警会自动 POST 到 http://localhost:9900/api/webhook/alert"
