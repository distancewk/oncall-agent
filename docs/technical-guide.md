# SuperBizAgent 技术指南

本文档描述当前代码库的实际设计和运行方式，面向需要部署、二次开发或排查问题的工程人员。

## 目录

1. [系统定位](#1-系统定位)
2. [总体架构](#2-总体架构)
3. [核心数据模型](#3-核心数据模型)
4. [核心流程](#4-核心流程)
5. [Agent 编排与工具](#5-agent-编排与工具)
6. [RAG、记忆与数据隔离](#6-rag记忆与数据隔离)
7. [诊断证据与报告约束](#7-诊断证据与报告约束)
8. [配置与部署](#8-配置与部署)
9. [API 列表](#9-api-列表)
10. [测试与质量门禁](#10-测试与质量门禁)
11. [项目结构](#11-项目结构)

---

## 1. 系统定位

SuperBizAgent 是一个智能 OnCall 助手，主要解决两类问题：

- 面向业务和运维人员的 RAG 问答：基于内部文档、会话上下文和可选私人记忆回答问题。
- 面向告警处理的 AIOps 诊断：告警进入后聚合为 Incident，生成 DiagnosisRun，调用指标、日志、知识库和历史案例工具，最后产出带证据引用的诊断报告。

当前实现已经从“单次告警生成报告”扩展为“可追踪的事故诊断流程”：

- Incident 表示一次故障或一组聚合告警。
- DiagnosisRun 表示某次 AI 分析。
- DiagnosisEvidence 记录工具调用证据。
- MetricTrend 提供 CPU、内存、错误率、P99、重启次数趋势。
- IncidentCase 将已确认故障沉淀为历史案例，新故障诊断前自动召回。

---

## 2. 总体架构

```text
Browser UI
  |
  | HTTP / SSE
  v
Controller
  |-- ChatController        聊天、RAG、会话接口、旧版 /api/ai_ops
  |-- WebhookController     Alertmanager webhook，自动创建 Incident 和 DiagnosisRun
  |-- AlertSseController    告警 SSE、历史告警、模拟告警
  |-- IncidentController    Incident 详情、诊断运行、历史案例归档/召回
  |-- KnowledgeController   知识库检索解释、索引任务状态
  |-- FileUploadController  文档上传与异步索引
  |-- MilvusCheckController Milvus 健康检查
  |
  v
Service
  |-- ChatService / RagService
  |-- SessionManager / ChatHistoryStore / MemoryExtractionService
  |-- IncidentService / IncidentStore
  |-- AiOpsService / DiagnosisEvidenceRecorder / DiagnosisReportService
  |-- MetricTrendPrefetchService / IncidentCaseService
  |-- VectorIndexService / VectorSearchService / VectorEmbeddingService / VectorRerankService
  |
  v
Infrastructure
  |-- DashScope: ChatModel、Embedding、Rerank
  |-- Milvus: document、chat_memory、incident_case 向量数据
  |-- Redis: 会话热缓存
  |-- File Store: 完整聊天历史与 Incident JSON
  |-- Prometheus: 告警与指标趋势，可 mock
  |-- CLS: 日志查询，可 mock
```

### 2.1 主要依赖

| 组件 | 版本/实现 | 用途 |
|------|-----------|------|
| Java | 17 | 运行时 |
| Spring Boot | 3.2.0 | Web 与配置框架 |
| Spring AI | 1.1.0 | ToolCallback、Agent 基础 |
| Spring AI Alibaba | 1.1.0.0-RC2 | DashScope 与 Agent 编排 |
| DashScope SDK | 2.17.0 | Embedding、Rerank、Generation API |
| Milvus | 2.5.10 | 向量数据库 |
| Milvus Java SDK | 2.6.10 | Milvus 客户端 |
| Redis | 7-alpine | 会话热缓存 |
| MinIO / etcd | Compose 管理 | Milvus 依赖 |
| Tavily MCP | stdio / npx | 公开互联网搜索 |
| DBHub MCP | stdio / npx | 多数据库只读查询 |
| marked / DOMPurify / highlight.js | CDN 引入 | 前端 Markdown 渲染、安全净化和代码高亮 |

---

## 3. 核心数据模型

### 3.1 Milvus 集合

集合名固定为 `biz`，字段定义由 `MilvusClientFactory` 创建：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VarChar | 主键，最大 256 |
| `vector` | FloatVector(1024) | 稠密向量 |
| `sparse_vector` | SparseFloatVector | 稀疏向量 |
| `content` | VarChar | 文本内容，最大 8192 |
| `metadata` | JSON | 元数据 |

`metadata.doc_type` 是当前最重要的隔离字段：

| doc_type | 来源 | 检索入口 |
|----------|------|----------|
| `document` | 上传的 `.md/.txt` 知识库文档 | 普通 RAG 和知识库检索 |
| `chat_memory` | 会话历史淘汰后提炼的私人长期记忆 | 当前 `session_id` 私人记忆召回 |
| `incident_case` | 已完成诊断归档的历史故障案例 | 相似历史故障召回 |

检索侧强制带过滤表达式：

- 普通知识库：`metadata["doc_type"] == "document"`
- 私人记忆：`metadata["doc_type"] == "chat_memory" && metadata["session_id"] == "{sessionId}"`
- 历史案例：`metadata["doc_type"] == "incident_case"`

### 3.2 ChatSessionRecord

完整聊天历史以 JSON 文件保存，目录由 `APP_CHAT_HISTORY_PATH` 配置，默认 `./data/chat-history`。文件名使用 sessionId 的 URL-safe Base64 编码，避免路径穿越。

核心字段：

| 字段 | 说明 |
|------|------|
| `sessionId` | 会话 ID |
| `createTime` | 创建时间 |
| `updateTime` | 最近更新时间 |
| `messageHistory` | 完整历史消息数组，包含 user/assistant |

Redis 中的 `session:{sessionId}` 只作为热缓存，TTL 默认 1 小时。应用重启或 Redis 过期后，可从文件历史恢复最近 6 对消息作为上下文窗口。

### 3.3 IncidentRecord

Incident 表示一次故障或一组归并后的告警，持久化为 JSON 文件，目录由 `APP_INCIDENTS_PATH` 配置，默认 `./data/incidents`。

核心字段：

| 字段 | 说明 |
|------|------|
| `id` | `inc-xxxx` |
| `aggregationKey` | 告警归并键，优先 fingerprint，否则用 alertname/service/instance/severity |
| `title` | 告警标题 |
| `status` | `OPEN` 或 `RESOLVED` |
| `severity` | 告警级别 |
| `alertCount` | 累计告警次数 |
| `alertPayloads` | 原始 Alertmanager payload 列表 |
| `diagnosisRuns` | 诊断运行列表 |

### 3.4 DiagnosisRunRecord

DiagnosisRun 表示某次 AI 分析。

| 字段 | 说明 |
|------|------|
| `runId` | `run-xxxx` |
| `incidentId` | 所属 Incident |
| `status` | 运行状态 |
| `createdAt/startedAt/completedAt` | 生命周期时间 |
| `alertContext` | 注入给 AI 的告警上下文 |
| `report` | 最终诊断报告 |
| `errorMessage` | 失败原因 |
| `currentStep` | 当前步骤 |
| `progressMessage` | 前端展示的进度说明 |
| `currentTool` | 当前等待的工具 |
| `evidence` | 证据列表 |

当前已实现的 run 状态：

| 状态 | 含义 | 入口 |
|------|------|------|
| `QUEUED` | 诊断任务已创建 | `createDiagnosisRun` |
| `RUNNING` | 正在运行或已完成某个工具调用 | `markRunRunning` / `addToolEvidence` |
| `WAITING_TOOL` | 正在等待工具返回 | `markRunWaitingTool` |
| `COMPLETED` | 最终报告已生成 | `completeRun` |
| `FAILED` | 诊断失败 | `failRun` |

当前没有对外的 `CANCELLED` 取消接口。

### 3.5 DiagnosisEvidence

工具调用证据统一记录为 `type=tool_call`。

| 字段 | 说明 |
|------|------|
| `id` | `ev-xxxx`，报告引用格式为 `[evidence: ev-xxxx]` |
| `toolName` | 工具名 |
| `queryParams` | 查询参数 |
| `timeRange` | 查询范围 |
| `summary` | 摘要 |
| `rawFragment` | 原始结果片段，最多保留 6000 字符 |
| `success` | 工具是否成功 |
| `errorMessage` | 错误信息 |

最终诊断报告不再写入 evidence，只保存在 `DiagnosisRunRecord.report`，避免前端“工具证据”和“分析报告”重复展示。

---

## 4. 核心流程

### 4.1 聊天流程

```text
POST /api/chat 或 /api/chat_stream
  |
  |-- SessionManager.getOrCreateSession
  |     |-- L1 内存命中
  |     |-- Redis 命中
  |     |-- 文件历史命中，恢复最近窗口
  |     |-- 新建会话
  |
  |-- 可选：按 session_id 检索私人长期记忆
  |
  |-- ChatService.buildSystemPrompt
  |     |-- 近期 6 对上下文
  |     |-- 私人记忆摘要
  |
  |-- ReactAgent 调用模型和工具
  |
  |-- SessionInfo.addMessage
        |-- 写入近期窗口
        |-- 追加完整聊天历史文件
        |-- 写 Redis 热缓存
        |-- 淘汰消息异步提炼为 chat_memory
```

### 4.2 文档上传与索引流程

```text
POST /api/upload
  |
  |-- 校验空文件、扩展名、路径穿越
  |-- 文件保存到 FILE_UPLOAD_PATH
  |-- 创建 IndexTaskStatus
  |-- HTTP 返回 INDEXING
  |
  |-- 异步索引
        |-- 删除同 _source 的旧数据
        |-- Markdown/段落切片
        |-- 批量生成 dense embedding
        |-- 生成 sparse vector
        |-- 批量写入 Milvus
        |-- 更新任务状态 COMPLETED 或 FAILED
```

上传成功不代表索引完成。前端和调用方应通过 `/api/upload/status/{taskId}` 或 `/api/knowledge/index-tasks` 查看状态。

### 4.3 Webhook 到诊断流程

```text
POST /api/webhook/alert
  |
  |-- IncidentService.recordAlert
  |     |-- fingerprint 优先归并
  |     |-- 无 fingerprint 时按 alertname/service/instance/severity 归并
  |
  |-- AlertService.storeAlert
  |-- IncidentService.createDiagnosisRun(status=QUEUED)
  |
  |-- 异步诊断
        |-- markRunRunning
        |-- IncidentCaseService.prefetchAndAppend
        |     |-- 检索 incident_case
        |     |-- 保存 searchSimilarIncidentCases evidence
        |
        |-- MetricTrendPrefetchService.prefetchAndAppend
        |     |-- 从告警文本推断 cpu/memory/error/p99/restart
        |     |-- 预取 15m 和 1h 趋势
        |     |-- 保存 queryMetricTrend evidence
        |
        |-- AiOpsService.executeAiOpsAnalysis
        |     |-- Planner / Executor / Supervisor
        |     |-- 工具调用全部经过 DiagnosisEvidenceRecorder
        |
        |-- extractFinalReport
        |-- completeRun 或 failRun
```

### 4.4 手动重跑诊断流程

`POST /api/incidents/{incidentId}/diagnose` 会复用当前 Incident 的告警上下文，创建新的 DiagnosisRun，并执行与 Webhook 相同的相似案例召回和 AI 诊断流程。

### 4.5 历史案例沉淀流程

```text
前端事故详情点击“写入历史案例”
  |
  v
POST /api/incidents/{incidentId}/archive-case
  |
  |-- 获取最新 COMPLETED DiagnosisRun
  |-- 从报告中提取“根因结论”和“处理建议”
  |-- 构建历史案例文档
  |-- 删除同 incident_id 的旧 incident_case
  |-- 写入 Milvus，metadata.doc_type=incident_case
```

新故障诊断前会自动调用相似案例召回，并把召回结果作为 `searchSimilarIncidentCases` 工具证据写入当前 DiagnosisRun。

---

## 5. Agent 编排与工具

### 5.1 Agent 编排

普通聊天使用 ReactAgent。AIOps 诊断使用 Planner、Executor 和 Supervisor 的协作模式：

- Planner 负责拆解问题、规划工具调用、输出最终报告。
- Executor 负责执行工具调用和收集观察结果。
- Supervisor 负责调度 Planner 与 Executor 的循环。

`AiOpsService` 会在执行前将已有 evidence 表和报告约束写入上下文。资源类结论必须优先使用 `queryMetricTrend`，日志/异常类结论必须使用日志或对应证据支撑。

### 5.2 工具列表

| 工具类 | 方法 | 说明 |
|--------|------|------|
| `DateTimeTools` | `getCurrentDateTime` | 获取当前日期时间 |
| `InternalDocsTools` | `queryInternalDocs` | 查询内部知识库 |
| `QueryMetricsTools` | `queryPrometheusAlerts` | 查询当前活动告警 |
| `QueryMetricsTools` | `queryMetricTrend` | 查询核心指标趋势 |
| `QueryLogsTools` | `getAvailableLogTopics` | 获取可用日志主题 |
| `QueryLogsTools` | `queryLogs` | 查询日志 |
| `IncidentCaseService` | `searchSimilarIncidentCases` | 内部召回相似历史案例，结果作为 evidence 保存 |

### 5.3 queryMetricTrend

入参：

| 参数 | 说明 |
|------|------|
| `metric` | `cpu_usage`、`memory_usage`、`error_rate`、`p99_latency`、`restart_count` |
| `service` | 服务名，可选但建议传 |
| `instance` | 实例、pod 或主机名，可选 |
| `window` | `15m`、`1h`、`6h`，非法值回退 `1h` |
| `step` | Prometheus query_range step；不传时按窗口默认 |

默认 step：

| window | 默认 step |
|--------|-----------|
| `15m` | `30s` |
| `1h` | `1m` |
| `6h` | `5m` |

返回 JSON 包含：

- `success`
- `metric`
- `window`
- `step`
- `query`
- `points`
- `summary.min/max/avg/latest/direction/anomalous`
- `message`
- `error`

真实模式调用 Prometheus `/api/v1/query_range`。Mock 模式下会返回可复现实验数据，覆盖 CPU 上升、内存上升、错误率突增、P99 升高和重启次数增加。

---

## 6. RAG、记忆与数据隔离

### 6.1 普通知识库

普通文档写入时设置：

```json
{
  "_source": "/path/to/file.md",
  "doc_type": "document"
}
```

`VectorSearchService.searchSimilarDocuments` 和 `explainSimilarDocuments` 只使用 `DOCUMENT_FILTER_EXPR`，因此不会返回聊天记忆或历史故障案例。

### 6.2 私人记忆

当会话窗口超过 6 对消息时，旧消息会被异步送入 `MemoryExtractionService`，提炼后写入 Milvus：

```json
{
  "_source": "chat_memory:{sessionId}",
  "doc_type": "chat_memory",
  "session_id": "{sessionId}"
}
```

召回时必须提供 sessionId。即使两个用户问了相同问题，也不会跨 session 召回对方的私人记忆。

配置：

```yaml
app:
  memory:
    private-recall-enabled: true
    private-recall-top-k: 3
```

### 6.3 历史故障案例

归档后的历史案例写入：

```json
{
  "_source": "incident_case:{incidentId}",
  "doc_type": "incident_case",
  "incident_id": "{incidentId}",
  "run_id": "{runId}",
  "alertname": "...",
  "service": "...",
  "instance": "...",
  "severity": "...",
  "root_cause": "...",
  "archived_at": 1760000000000
}
```

历史案例不会进入普通知识库检索，只由相似故障召回路径使用。

### 6.4 混合检索

Milvus 使用 dense vector 和 sparse vector 进行 hybrid search，再用 RRF 融合，最后通过 DashScope GTE-Rerank 重排序。

关键参数：

| 参数 | 默认 | 说明 |
|------|------|------|
| `rag.candidate-k` | 10 | Milvus 粗排候选数 |
| `rag.top-k` | 3 | 默认最终返回数量 |
| `rag.search-ef` | 64 | HNSW 搜索 ef 参数 |

---

## 7. 诊断证据与报告约束

### 7.1 证据记录

`DiagnosisEvidenceRecorder` 通过 ThreadLocal 绑定当前 `incidentId/runId`。工具调用时流程如下：

```text
recordToolCall
  |
  |-- markRunWaitingTool(status=WAITING_TOOL)
  |-- 执行真实工具
  |-- 解析 success/message/error
  |-- 截断 rawFragment
  |-- addToolEvidence(status=RUNNING)
  |-- 在 JSON 返回中注入 _diagnosisEvidenceId
```

如果工具返回不是 JSON，会在文本末尾追加“诊断证据ID”。

### 7.2 报告约束

`DiagnosisReportService` 在上下文中注入“可用工具证据表”和报告规则：

- 根因、症状、处理建议必须引用 evidence id。
- CPU、内存、错误率、P99、重启类结论必须引用 `queryMetricTrend`。
- 日志、异常、GC、OOM 结论必须引用 `queryLogs` 或包含对应信号的证据。
- 不能引用不存在的 evidence id。
- 证据不足时必须写“证据不足”。

`IncidentService.completeRun` 会对最终报告追加 `## 证据校验`：

- 校验状态：通过、需补证、失败
- 置信度：高、中、低
- 已引用证据
- 未知证据
- 缺失证据
- 可用证据

### 7.3 前端展示

事故详情页只把 `type=tool_call` 或存在 `toolName` 的记录展示为“工具证据”。`alert_context`、旧历史里的 `final_report` 不会展示为工具证据。

工具证据分组：

1. 相似历史案例
2. 指标趋势
3. 日志查询
4. 知识库检索
5. 活动告警
6. 时间工具
7. 其他工具

详情页轮询刷新不会重置滚动位置；诊断进入 `COMPLETED`、`FAILED` 后停止轮询。

---

## 8. 配置与部署

### 8.1 application.yml 关键配置

```yaml
server:
  port: 9900

file:
  upload:
    path: ${FILE_UPLOAD_PATH:./uploads}
    allowed-extensions: txt,md

milvus:
  host: ${MILVUS_HOST:localhost}
  port: ${MILVUS_PORT:19530}
  database: default
  timeout: 10000

spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}

rag:
  candidate-k: 10
  top-k: 3
  search-ef: ${RAG_SEARCH_EF:64}
  model: qwen3-max

prometheus:
  base-url: ${PROMETHEUS_BASE_URL:http://localhost:9090}
  timeout: 10
  mock-enabled: ${PROMETHEUS_MOCK_ENABLED:false}

cls:
  mock-enabled: ${CLS_MOCK_ENABLED:false}

app:
  cors:
    allowed-origins: ${APP_CORS_ALLOWED_ORIGINS:http://localhost:9900,http://127.0.0.1:9900}
  security:
    enabled: ${APP_SECURITY_ENABLED:false}
    api-token: ${APP_API_TOKEN:}
    webhook-secret: ${APP_WEBHOOK_SECRET:}
  alerts:
    simulate-enabled: ${APP_ALERT_SIMULATE_ENABLED:false}
  chat-history:
    path: ${APP_CHAT_HISTORY_PATH:./data/chat-history}
  incidents:
    path: ${APP_INCIDENTS_PATH:./data/incidents}
  memory:
    private-recall-enabled: ${APP_PRIVATE_MEMORY_RECALL_ENABLED:true}
    private-recall-top-k: ${APP_PRIVATE_MEMORY_RECALL_TOP_K:3}
```

### 8.2 Profile 差异

| Profile | 行为 |
|---------|------|
| default | 鉴权关闭，Prometheus/CLS mock 关闭，模拟告警关闭 |
| dev | 模拟告警开启，Prometheus/CLS mock 默认开启 |
| mcp | 启用 Tavily MCP，用于公开互联网检索 |
| mcp-db | 启用 DBHub MCP，用于多数据库只读查询，需要真实数据库配置 |
| prod | `app.security.enabled=true` |

`mcp` profile 使用 [src/main/resources/application-mcp.yml](../src/main/resources/application-mcp.yml)：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: ${MCP_CLIENT_ENABLED:true}
        stdio:
          connections:
            tavily:
              command: ${MCP_TAVILY_COMMAND:npx}
              args:
                - -y
                - "${MCP_TAVILY_PACKAGE:tavily-mcp@0.2.9}"
              env:
                TAVILY_API_KEY: ${TAVILY_API_KEY:}
```

`mcp-db` profile 使用 [src/main/resources/application-mcp-db.yml](../src/main/resources/application-mcp-db.yml)：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: ${MCP_CLIENT_ENABLED:true}
        stdio:
          connections:
            dbhub:
              command: ${MCP_DBHUB_COMMAND:npx}
              args:
                - -y
                - "${MCP_DBHUB_PACKAGE:@bytebase/dbhub@0.21.2}"
                - --transport
                - stdio
                - --config
                - ${MCP_DBHUB_CONFIG:./config/dbhub.toml}
```

DBHub 使用 [config/dbhub.toml](../config/dbhub.toml) 作为模板文件。该文件不包含活跃数据源；生产环境应使用仓库外部配置文件并通过 `MCP_DBHUB_CONFIG` 指定路径。不要把内存 SQLite 作为默认启动源，因为 SQLite connector 依赖 DBHub 可选原生包 `better-sqlite3`，在 `npx` 临时安装场景下可能不可用。

### 8.3 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DASHSCOPE_API_KEY` | 无 | 必需 |
| `MILVUS_HOST` | `localhost` | 本地 Maven 启动通常使用 localhost；容器内使用 milvus |
| `MILVUS_PORT` | `19530` | Milvus gRPC 端口 |
| `REDIS_HOST` | `localhost` | Redis 主机 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `PROMETHEUS_BASE_URL` | `http://localhost:9090` | Prometheus 地址 |
| `PROMETHEUS_MOCK_ENABLED` | `false` | Prometheus mock 开关 |
| `CLS_MOCK_ENABLED` | `false` | CLS mock 开关 |
| `FILE_UPLOAD_PATH` | `./uploads` | 上传目录 |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:9900,http://127.0.0.1:9900` | CORS 白名单 |
| `APP_SECURITY_ENABLED` | `false` | API 鉴权开关 |
| `APP_API_TOKEN` | 空 | 普通 API token |
| `APP_WEBHOOK_SECRET` | 空 | Webhook secret |
| `APP_ALERT_SIMULATE_ENABLED` | `false` | 模拟告警开关 |
| `APP_CHAT_HISTORY_PATH` | `./data/chat-history` | 聊天历史目录 |
| `APP_INCIDENTS_PATH` | `./data/incidents` | Incident 存储目录 |
| `APP_PRIVATE_MEMORY_RECALL_ENABLED` | `true` | 私人记忆召回 |
| `APP_PRIVATE_MEMORY_RECALL_TOP_K` | `3` | 私人记忆召回数量 |
| `RAG_SEARCH_EF` | `64` | HNSW ef |
| `MCP_CLIENT_ENABLED` | `true` | MCP profile 下 MCP Client 开关 |
| `MCP_REQUEST_TIMEOUT` | `60s` | MCP 请求超时 |
| `MCP_TAVILY_COMMAND` | `npx` | Tavily MCP 启动命令 |
| `MCP_TAVILY_PACKAGE` | `tavily-mcp@0.2.9` | Tavily MCP npm 包版本 |
| `TAVILY_API_KEY` | 空 | Tavily API Key |
| `MCP_DBHUB_COMMAND` | `npx` | DBHub MCP 启动命令 |
| `MCP_DBHUB_PACKAGE` | `@bytebase/dbhub@0.21.2` | DBHub MCP npm 包版本 |
| `MCP_DBHUB_CONFIG` | `./config/dbhub.toml` | DBHub 多数据库配置文件 |

### 8.4 安全边界

`ApiSecurityInterceptor` 挂载到 `/api/**`：

- `APP_SECURITY_ENABLED=false` 时放行。
- `OPTIONS` 请求放行。
- `/api/webhook/**` 校验 `X-Webhook-Secret`。
- 其他 `/api/**` 校验 `X-API-Key`。

CORS 由 `APP_CORS_ALLOWED_ORIGINS` 控制，不使用 `*`。

模拟告警 `/api/alerts/simulate` 由 `APP_ALERT_SIMULATE_ENABLED` 控制。生产环境不应开启。

前端 Markdown 使用 DOMPurify 净化，避免 LLM 输出或告警报告中的脚本注入。

MCP 额外边界：

- Tavily MCP 只作为外部公开资料参考，不能覆盖 Incident、指标、日志或内部知识库事实。
- DBHub MCP 必须使用只读账号和 `readonly = true` 工具配置。
- Agent 提示词禁止通过数据库 MCP 执行 `INSERT / UPDATE / DELETE / DROP / ALTER / TRUNCATE / CREATE` 等写入或结构变更语句。
- 数据库查询应限制字段、时间范围和返回行数，避免把业务库当作无界数据导出通道。

### 8.5 Docker Compose

`docker-compose.yml` 包含：

- etcd
- minio
- milvus
- attu
- redis
- app

app 服务：

- 依赖 Milvus 和 Redis 的 healthcheck。
- 设置 `MILVUS_HOST=milvus`、`REDIS_HOST=redis`。
- 默认启用 Prometheus/CLS mock，便于容器演示。
- 挂载 `/app/uploads`、`/app/data/chat-history`、`/app/data/incidents`。

`Dockerfile` 使用多阶段构建，运行时基于 JRE，并通过 Spring Boot Maven 插件排除 devtools。

---

## 9. API 列表

### 9.1 聊天

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 非流式聊天 |
| POST | `/api/chat_stream` | SSE 流式聊天 |
| POST | `/api/chat/clear` | 清空会话 |
| GET | `/api/chat/session/{sessionId}` | 会话摘要 |
| GET | `/api/chat/sessions` | 会话列表 |
| GET | `/api/chat/session/{sessionId}/messages` | 完整会话消息 |
| DELETE | `/api/chat/session/{sessionId}` | 删除会话 |

聊天请求体兼容 `Id/Question`、`id/question`。

### 9.2 知识库与上传

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/upload` | 上传 `.md/.txt` 文件，异步索引 |
| GET | `/api/upload/status/{taskId}` | 查询单个索引任务 |
| GET | `/api/knowledge/search?query=...&topK=5` | 检索知识库并返回粗排/精排 trace |
| GET | `/api/knowledge/index-tasks` | 查看索引任务列表 |
| GET | `/milvus/health` | Milvus 健康检查 |

### 9.3 告警与 Incident

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/alerts/stream` | 告警 SSE |
| GET | `/api/alerts/history` | 告警历史 |
| GET | `/api/alerts/detail/{alertId}` | 告警详情 |
| GET | `/api/alerts/report/{alertId}` | 告警报告 |
| POST | `/api/alerts/simulate` | 模拟告警，仅配置开启时可用 |
| POST | `/api/webhook/alert` | Alertmanager webhook |
| GET | `/api/incidents` | Incident 列表 |
| GET | `/api/incidents/{incidentId}` | Incident 详情 |
| GET | `/api/incidents/{incidentId}/runs` | DiagnosisRun 列表 |
| POST | `/api/incidents/{incidentId}/diagnose` | 手动重跑诊断 |
| POST | `/api/incidents/{incidentId}/archive-case` | 写入历史故障案例 |
| GET | `/api/incidents/{incidentId}/similar-cases?topK=3` | 查询相似历史案例 |

### 9.4 旧版 AIOps 流式接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ai_ops` | 旧版 SSE AIOps 分析接口，可传 `alertContext/alertId` |

新事故诊断建议使用 Incident 相关接口，因为该路径具备 DiagnosisRun 状态、证据记录、趋势预取和历史案例召回。

---

## 10. 测试与质量门禁

### 10.1 推荐验证命令

```bash
mvn test
node --check src/main/resources/static/app.js
node src/test/js/evidenceRendering.test.mjs
```

`mvn test` 默认通过 Surefire 预加载 Byte Buddy agent，不需要手工传 `-DargLine`。

### 10.2 当前测试覆盖重点

| 测试 | 覆盖 |
|------|------|
| `ConfigurationContractTest` | 配置绑定和环境变量 |
| `SecurityInterceptorTest` | API Key、Webhook Secret |
| `FileUploadControllerTest` | 上传校验、异步索引状态 |
| `VectorSearchServiceTest` | RAG、私人记忆、历史案例过滤表达式 |
| `QueryMetricsToolsTest` | 指标趋势 mock、非法参数 |
| `DiagnosisEvidenceRecorderTest` | 工具 evidence 注入 |
| `DiagnosisReportServiceTest` / `DiagnosisReportGuardTest` | 报告证据约束 |
| `IncidentServiceTest` | Incident 与 DiagnosisRun 状态流转 |
| `IncidentCaseServiceTest` | 历史案例归档和召回 |
| `MetricTrendPrefetchServiceTest` | 告警趋势预取 |
| `src/test/js/evidenceRendering.test.mjs` | 前端证据分组渲染 |

### 10.3 静态质量

`pom.xml` 配置了：

- SpotBugs：`failOnError=true`
- PMD：使用 `pmd-ruleset.xml`
- Checkstyle：当前 `failOnViolation=false`，用于暴露风格问题但不阻断
- Spring Boot Maven Plugin：`excludeDevtools=true`

---

## 11. 项目结构

```text
super-biz-agent/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── Makefile
├── aiops-docs/
│   ├── container_crash_loop.md
│   ├── cpu_high_usage.md
│   ├── disk_high_usage.md
│   ├── disk_iowait_high.md
│   ├── high_error_rate.md
│   ├── memory_high_usage.md
│   ├── network_latency.md
│   ├── oom_killed.md
│   ├── service_unavailable.md
│   └── slow_response.md
├── docs/
│   ├── alert-system-guide.md
│   ├── improvement-plan.md
│   └── technical-guide.md
└── src/
    ├── main/java/org/example/
    │   ├── agent/tool/
    │   │   ├── DateTimeTools.java
    │   │   ├── InternalDocsTools.java
    │   │   ├── QueryLogsTools.java
    │   │   └── QueryMetricsTools.java
    │   ├── client/
    │   │   └── MilvusClientFactory.java
    │   ├── config/
    │   │   ├── ApiSecurityInterceptor.java
    │   │   ├── App*Properties.java
    │   │   ├── DashScope*.java
    │   │   ├── Milvus*.java
    │   │   ├── RateLimitInterceptor.java
    │   │   ├── RedisConfig.java
    │   │   ├── ThreadPoolConfig.java
    │   │   └── WebConfig.java
    │   ├── controller/
    │   │   ├── AlertSseController.java
    │   │   ├── ChatController.java
    │   │   ├── FileUploadController.java
    │   │   ├── IncidentController.java
    │   │   ├── KnowledgeController.java
    │   │   ├── MilvusCheckController.java
    │   │   └── WebhookController.java
    │   ├── dto/
    │   │   ├── ChatSessionRecord.java
    │   │   ├── DiagnosisEvidence.java
    │   │   ├── DiagnosisRunRecord.java
    │   │   ├── IncidentRecord.java
    │   │   └── IndexTaskStatus.java
    │   └── service/
    │       ├── AiOpsService.java
    │       ├── ChatHistoryStore.java
    │       ├── DiagnosisEvidenceRecorder.java
    │       ├── DiagnosisReportService.java
    │       ├── IncidentCaseService.java
    │       ├── IncidentService.java
    │       ├── IncidentStore.java
    │       ├── MemoryExtractionService.java
    │       ├── MetricTrendPrefetchService.java
    │       ├── VectorIndexService.java
    │       └── VectorSearchService.java
    ├── main/resources/
    │   ├── application.yml
    │   ├── application-dev.yml
    │   ├── application-prod.yml
    │   └── static/
    │       ├── index.html
    │       ├── styles.css
    │       └── app.js
    └── test/
        ├── java/org/example/
        └── js/evidenceRendering.test.mjs
```
