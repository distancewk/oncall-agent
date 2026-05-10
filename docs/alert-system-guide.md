# 告警处理双模式系统使用指南

## 概述

本系统实现了**主动查询**与**被动接收**双模式告警处理机制，支持实时告警推送、AI Ops 智能分析、告警历史追溯等功能。

---

## 架构

```
                    +------------------+
                    |  Alertmanager    |  (外部告警系统，可选)
                    +--------+---------+
                             |
                    POST /api/webhook/alert
                             |
                     +------v-------+
                     | AlertService  |  (内存存储)
                     |  - alerts     |
                     |  - reports    |
                     +---+----+-----+
                         |    |
              SSE 推送    |    | REST API
           +-------------v+   v----------------+
           | 前端 SSE 连接  |  /api/alerts/*   |
           | (EventSource)  |                  |
           +----------------+------------------+
```

### 核心组件

| 组件 | 文件 | 说明 |
|------|------|------|
| AlertService | `service/AlertService.java` | 告警和报告的内存存储，支持监听器模式 |
| AlertSseController | `controller/AlertSseController.java` | SSE 推送 + 告警查询 API |
| WebhookController | `controller/WebhookController.java` | 接收外部 Alertmanager 推送 |
| ChatController | `controller/ChatController.java` | AI Ops 分析接口（支持告警上下文） |
| AiOpsService | `service/AiOpsService.java` | 多 Agent 协作告警分析引擎 |

---

## API 接口文档

### 1. SSE 实时告警推送

建立 SSE 长连接，实时接收新告警通知。

```
GET /api/alerts/stream
```

**响应格式（SSE）：**

```
event: message
data: {"type":"new_alert","alertId":"abc12345","status":"firing","receivedAt":1712345678000,"summary":"[critical] CPUUsageHigh; "}
```

**前端连接示例（已内置在 app.js 中）：**

```javascript
const eventSource = new EventSource('/api/alerts/stream');
eventSource.onmessage = (event) => {
    const data = JSON.parse(event.data);
    if (data.type === 'new_alert') {
        // 显示通知
    }
};
```

### 2. 模拟告警

构造一条测试告警并存入系统，触发 SSE 推送。

```
POST /api/alerts/simulate
Content-Type: application/json

{}
```

**响应：**

```json
{
    "success": true,
    "alertId": "abc12345",
    "message": "模拟告警已触发"
}
```

**前置条件：** 无需任何外部依赖。

### 3. 获取告警历史

```
GET /api/alerts/history
```

**响应：**

```json
{
    "alerts": [
        {
            "id": "abc12345",
            "status": "firing",
            "receivedAt": 1712345678000,
            "summary": "[critical] CPUUsageHigh; ",
            "severity": "critical",
            "hasReport": true
        }
    ],
    "total": 1
}
```

### 4. 获取告警详情

```
GET /api/alerts/detail/{alertId}
```

### 5. 获取告警分析报告

```
GET /api/alerts/report/{alertId}
```

**响应：**

```json
{
    "alertId": "abc12345",
    "report": "# 告警分析报告\n\n...",
    "status": "completed"
}
```

可能的状态值：`completed`（已有报告）、`pending`（分析中）。

### 6. 接收外部 Webhook 告警

接收 Prometheus Alertmanager 的 HTTP 推送，自动触发 AI Ops 分析。

```
POST /api/webhook/alert
Content-Type: application/json
```

**请求体格式（兼容 Alertmanager Webhook）：**

```json
{
    "receiver": "webhook",
    "status": "firing",
    "alerts": [
        {
            "status": "firing",
            "labels": {
                "alertname": "CPUUsageHigh",
                "severity": "critical",
                "instance": "prod-server-01",
                "job": "node-exporter"
            },
            "annotations": {
                "summary": "CPU 使用率超过 90%",
                "description": "instance=prod-server-01 CPU 使用率 95%"
            },
            "startsAt": "2026-05-10T10:00:00Z"
        }
    ],
    "groupLabels": {"alertname": "CPUUsageHigh"},
    "commonLabels": {"severity": "critical"},
    "commonAnnotations": {"summary": "CPU 使用率过高"}
}
```

**响应：**

```
Alert received and processing triggered.
```

> 注意：分析是异步执行的，完成后报告将通过 AlertService 存储，可通过 `/api/alerts/report/{alertId}` 查询。

### 7. AI Ops 智能分析

触发多 Agent 协作的告警分析流程，可选传入告警上下文。

```
POST /api/ai_ops
Content-Type: application/json
```

**请求体（可选）：**

```json
{
    "alertId": "abc12345",
    "alertContext": "告警: CPUUsageHigh\n  状态: firing\n  severity: critical\n  instance: prod-server-01\n  summary: CPU超限"
}
```

**响应：** SSE 流式返回分析过程及最终报告。

---

## 前端功能

### 侧边栏告警管理

| 按钮 | 功能 |
|------|------|
| 主动查询告警 | 触发 AI Ops 通用分析 |
| 告警历史 | 打开告警历史面板，查看所有告警记录 |
| 模拟告警 | 发送一条测试告警，验证系统连通性 |

### 告警通知弹出

- 当收到新告警（通过 SSE）时，右上角弹出通知
- 根据告警级别（firing/resolved）显示不同颜色
- 点击"查看详情"跳转到告警详情面板
- 10 秒自动消失

### 告警历史面板

- 卡片式展示所有告警
- 显示告警级别标签（critical/warning）、状态、时间
- 标记是否有分析报告
- 点击"查看详情"进入告警详情面板

### 告警详情面板

- 告警基本信息（ID、状态、接收时间）
- 告警列表（标签、注解）
- **分析报告**（Markdown 渲染）
- **对此告警执行 AI Ops 分析** 按钮

---

## 测试流程

### 方式一：纯前端测试（推荐）

```
1. 启动服务
2. 打开前端页面（http://localhost:9900）
3. 点击侧边栏 "模拟告警"
4. 右上角弹出告警通知 → 点击"查看详情"
5. 告警详情面板显示告警信息
6. 点击"对此告警执行 AI Ops 分析" → 查看分析过程
```

### 方式二：命令行测试

```bash
# 1. 模拟告警
curl -X POST http://localhost:9900/api/alerts/simulate \
  -H "Content-Type: application/json" \
  -d '{}'

# 2. 查看告警历史，获取 alertId
curl http://localhost:9900/api/alerts/history | jq .

# 3. 查看告警详情
curl http://localhost:9900/api/alerts/detail/{alertId} | jq .

# 4. 查看分析报告
curl http://localhost:9900/api/alerts/report/{alertId} | jq .

# 5. 对特定告警执行 AI Ops 分析
curl -X POST http://localhost:9900/api/ai_ops \
  -H "Content-Type: application/json" \
  -d '{"alertId": "{alertId}", "alertContext": "告警: CPUUsageHigh\n  severity: critical"}'
```

### 方式三：模拟 Webhook 推送

```bash
curl -X POST http://localhost:9900/api/webhook/alert \
  -H "Content-Type: application/json" \
  -d '{
    "receiver": "webhook",
    "status": "firing",
    "alerts": [{
      "status": "firing",
      "labels": {
        "alertname": "CPUUsageHigh",
        "severity": "critical",
        "instance": "prod-server-01"
      },
      "annotations": {
        "summary": "CPU 使用率超过 90%",
        "description": "CPU 95%"
      },
      "startsAt": "2026-05-10T10:00:00Z"
    }]
  }'
```

---

## 生产环境接入

### 接入 Prometheus Alertmanager

在 Alertmanager 配置文件 `alertmanager.yml` 中添加：

```yaml
route:
  group_by: ['alertname']
  receiver: 'superbiz-webhook'

receivers:
- name: 'superbiz-webhook'
  webhook_configs:
  - url: 'http://<your-server>:9900/api/webhook/alert'
    send_resolved: true
```

### 持久化存储

当前 `AlertService` 使用内存存储（`ConcurrentHashMap`），服务重启后数据丢失。如需持久化，可：

- **方案一**：替换为 Redis 存储（项目中已有 Redis 配置 `RedisConfig.java`）
- **方案二**：替换为数据库（MySQL/PostgreSQL）
- **方案三**：继承 `AlertService` 并覆写存储方法

---

## 注意事项

1. **SSE 连接**：`/api/alerts/stream` 使用 `Long.MAX_VALUE` 超时（无超时），客户端断开时会自动清理监听器
2. **Webhook 异步处理**：接收告警后立即返回，AI Ops 分析在后台线程池中执行
3. **模拟告警**不会自动触发 AI Ops 分析，可通过详情面板中的按钮手动触发
4. **CORS**：如果前后端分离部署，需在 `WebConfig.java` 中添加 `/api/alerts/**` 的跨域配置
5. **告警上下文**：传递给 AI Ops 的告警上下文长度没有限制，但过多内容可能影响 LLM 分析效果
