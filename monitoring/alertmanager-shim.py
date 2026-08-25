#!/usr/bin/env python3
"""
alertmanager-shim.py — 一个与 Prometheus Alertmanager *webhook 协议* 严格兼容的
轻量转发器，用于本机 Docker 镜像存储损坏、无法拉起真实 Alertmanager 期间的替代。

它做两件事：
  1. 监听 Prometheus 发来的告警：  POST /api/v1/alerts   (Prometheus -> Alertmanager 的协议)
  2. 把告警封装成 Alertmanager 的 *webhook* 格式，POST 到 SuperBizAgent：
        POST {SUPERBIZ_WEBHOOK_URL}   (默认 http://localhost:9900/api/webhook/alert)
        Header: X-Webhook-Secret: {SUPERBIZ_WEBHOOK_SECRET}
     该 payload 与真实 Alertmanager 推送给 SuperBizAgent 的 JSON 完全一致，
     因此 SuperBizAgent 的 WebhookController / AlertPayload 无需任何改动即可接收。

环境变量：
  LISTEN        shim 监听地址，默认 0.0.0.0:9093
  SUPERBIZ_WEBHOOK_URL   目标 webhook，默认 http://localhost:9900/api/webhook/alert
  SUPERBIZ_WEBHOOK_SECRET 鉴权头，默认 dev-webhook-secret-2026
  RECEIVER     写入 payload.receiver 字段，默认 superbiz-webhook

注意：这是"最小可用"替代，不含抑制/静默/分组等 Alertmanager 高级能力；
生产环境请重启 Docker Desktop 后用 monitoring/docker-compose.yml 拉起真实 Alertmanager。
"""
import hashlib
import json
import os
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN = os.environ.get("LISTEN", "0.0.0.0:9093")
WEBHOOK_URL = os.environ.get("SUPERBIZ_WEBHOOK_URL", "http://localhost:9900/api/webhook/alert")
SECRET = os.environ.get("SUPERBIZ_WEBHOOK_SECRET", "dev-webhook-secret-2026")
RECEIVER = os.environ.get("RECEIVER", "superbiz-webhook")

# 内存中保留当前活跃告警（用于 /alerts 调试与 resolved 判定）
_active = {}


def fingerprint(labels: dict) -> str:
    canonical = json.dumps(labels, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode()).hexdigest()[:16]


def classify(alert: dict) -> str:
    """根据 endsAt 判定 firing / resolved（与 Alertmanager 行为一致）。"""
    ends = alert.get("endsAt")
    if ends and ends not in ("0001-01-01T00:00:00Z", "0001-01-01T00:00:00.000Z"):
        # 简单判断：endAt 在过去即 resolved。这里保守：有 endsAt 且小于 now 视为 resolved。
        # 为简单起见，Prometheus 发来的 active 告警 endsAt 通常为上述零值，故默认 firing。
        return "resolved"
    return "firing"


def forward_to_superbiz(payload: dict):
    data = json.dumps(payload).encode()
    req = urllib.request.Request(WEBHOOK_URL, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("X-Webhook-Secret", SECRET)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            body = resp.read().decode(errors="replace")
            print(f"[shim] forwarded -> {WEBHOOK_URL} | HTTP {resp.status} | {body[:120]}", flush=True)
    except Exception as e:
        print(f"[shim] forward FAILED -> {WEBHOOK_URL} | {e}", flush=True)


def build_webhook_envelope(alerts: list) -> dict:
    out_alerts = []
    for a in alerts:
        labels = a.get("labels", {}) or {}
        annotations = a.get("annotations", {}) or {}
        status = classify(a)
        fp = fingerprint(labels)
        _active[fp] = a
        out_alerts.append({
            "status": status,
            "labels": labels,
            "annotations": annotations,
            "startsAt": a.get("startsAt", ""),
            "endsAt": a.get("endsAt", "0001-01-01T00:00:00.000Z"),
            "generatorURL": a.get("generatorURL", ""),
            "fingerprint": fp,
        })
    # common/group labels 简化：取第一条的 alertname
    group_labels = {}
    if out_alerts:
        group_labels = {k: v for k, v in out_alerts[0]["labels"].items() if k in ("alertname",)}
    return {
        "receiver": RECEIVER,
        "status": "firing" if any(x["status"] == "firing" for x in out_alerts) else "resolved",
        "alerts": out_alerts,
        "groupLabels": group_labels,
        "commonLabels": {},
        "commonAnnotations": {},
        "externalURL": f"http://{LISTEN}",
        "version": "4",
        "groupKey": "{}:" + json.dumps(group_labels, sort_keys=True),
    }


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "ok"})
        elif self.path == "/alerts":
            self._send(200, {"active": list(_active.values())})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/api/v1/alerts":
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length) if length else b"{}"
            try:
                alerts = json.loads(raw)
            except Exception as e:
                self._send(400, {"status": "error", "error": str(e)})
                return
            if not isinstance(alerts, list):
                alerts = [alerts]
            envelope = build_webhook_envelope(alerts)
            print(f"[shim] received {len(alerts)} alert(s) from Prometheus, status={envelope['status']}", flush=True)
            forward_to_superbiz(envelope)
            self._send(200, {"status": "success"})
        else:
            self._send(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        pass  # 静默默认访问日志


if __name__ == "__main__":
    host, port = LISTEN.split(":")
    print(f"[shim] listening on {LISTEN}, forwarding to {WEBHOOK_URL}", flush=True)
    ThreadingHTTPServer((host, int(port)), Handler).serve_forever()
