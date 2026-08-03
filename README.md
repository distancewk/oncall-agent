# SuperBizAgent (OnCall Agent)

SuperBizAgent 是一个基于 Spring Boot、Spring AI Alibaba、DashScope、Milvus 和 Redis 的智能 OnCall 助手。当前项目支持知识库问答、多轮对话、告警聚合、AI 运维诊断、诊断证据记录、指标趋势分析和相似历史故障召回。

## 核心能力

| 模块 | 说明 |
|------|------|
| RAG 知识库问答 | 上传 `.md/.txt` 文档后写入 Milvus，聊天和知识库检索只召回普通文档数据 |
| 多轮对话 | 近期上下文保存在内存/Redis，完整聊天历史持久化到 PostgreSQL，重启后仍可查看历史会话 |
| 私人记忆 | 被窗口淘汰的历史对话可提炼为 `chat_memory`，写入和检索时强制按 `tenant_id + session_id` 隔离 |
| AIOps 诊断 | Webhook 或手动触发 Incident 诊断，生成 DiagnosisRun 和最终报告 |
| 证据链 | 每次工具调用保存为 DiagnosisEvidence，报告需要引用 evidence id |
| 指标趋势 | `queryMetricTrend` 支持 CPU、内存、错误率、P99、重启次数的 15m/1h/6h 趋势查询；无数据告警不臆造指标，按指标目录选择查询 |
| 相似历史故障 | 已完成诊断可写入 `incident_case`，新故障诊断前自动召回相似案例 |
| 前端控制台 | 提供聊天、知识库状态/检索、事故历史筛选、事故详情、诊断进度、证据链工作台和工具证据分组展示 |
| 安全边界 | 支持 API 鉴权、Webhook 时间戳/nonce/HMAC 防重放、独立会话签名 Secret、CORS 白名单和模拟告警开发开关 |

## 技术栈

| 技术 | 版本/配置 | 用途 |
|------|-----------|------|
| Java | 17 | 运行时与编译目标 |
| Spring Boot | 3.2.0 | Web 应用框架 |
| Spring AI | 1.1.0 | AI 工具和 Agent 编排基础 |
| Spring AI Alibaba | 1.1.0.0-RC2 | DashScope ChatModel 与 Agent 框架 |
| DashScope SDK | 2.17.0 | Embedding、Rerank、Generation API |
| Milvus | 2.5.10 | 向量数据库 |
| Milvus Java SDK | 2.6.10 | Java 客户端 |
| Redis | 7-alpine | 会话热缓存 |
| PostgreSQL | 16-alpine | Incident、告警、诊断、证据、聊天、索引状态和后台任务持久化 |
| MinIO / etcd | Compose 内置 | Milvus 依赖 |
| 前端 | 原生 HTML/CSS/JS | 单页控制台，使用 marked、DOMPurify、highlight.js |

## 快速启动

### 环境要求

- Java 17
- Maven 3.6+
- Node.js 20+（仅启用 MCP profile 时需要，用于 `npx` 启动 Tavily/DBHub MCP）
- Docker 和 Docker Compose
- DashScope API Key

### 1. 设置环境变量

```bash
export DASHSCOPE_API_KEY=your-api-key
# 默认开启 API 鉴权；生产/Compose 环境请设置强随机令牌
export APP_API_TOKEN=your-operator-api-token
export APP_ADMIN_TOKEN=your-admin-api-token  # 管理员令牌；生产环境必填
export APP_WEBHOOK_SECRET=your-webhook-secret
export APP_WEBHOOK_SIGNING_SECRET=your-different-webhook-signing-secret
export APP_SECURITY_SESSION_SIGNING_SECRET=your-different-session-signing-secret
# 可选：启用短期机器令牌；生产环境启用时必须配置独立 Secret 和 Redis 撤销存储
export APP_SECURITY_MACHINE_TOKEN_ENABLED=true
export APP_SECURITY_MACHINE_TOKEN_SIGNING_SECRET=your-different-machine-token-secret
```

本地开发建议同时打开 mock 数据，避免没有 Prometheus 或 CLS 时诊断工具失败：

```bash
export SPRING_PROFILES_ACTIVE=dev
export PROMETHEUS_MOCK_ENABLED=true
export CLS_MOCK_ENABLED=true
export APP_ALERT_SIMULATE_ENABLED=true
```

### 2. 启动基础设施

本地 Maven 启动应用时，只需要先启动依赖容器：

```bash
docker compose up -d etcd minio milvus redis postgres attu
```

确认依赖健康：

```bash
docker compose ps
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

访问地址：

- Web UI: http://localhost:9900
- Attu (Milvus 管理界面): http://localhost:8000
- Milvus 健康检查: http://localhost:9900/milvus/health
- 应用健康/指标: `GET /actuator/health`、`GET /actuator/health/liveness`、`GET /actuator/health/readiness`、`GET /actuator/metrics`；readiness 会分别反映应用状态、数据库和 Redis；与 API 一样需要 `X-API-Key` 或浏览器会话

### 3.1 可选：启用 MCP

默认启动不启用 MCP。需要联网查询时，使用 `mcp` profile：

```bash
export SPRING_PROFILES_ACTIVE=dev,mcp
export TAVILY_API_KEY=your-tavily-api-key
mvn spring-boot:run
```

`application-mcp.yml` 会通过 stdio 启动 Tavily MCP server：

- `tavily`: `npx -y ${MCP_TAVILY_PACKAGE}`，默认 `tavily-mcp@0.2.9`，用于公开互联网搜索。

需要数据库 MCP 时，再额外启用 `mcp-db` profile，并把 `MCP_DBHUB_CONFIG` 指向真实数据库配置：

```bash
export SPRING_PROFILES_ACTIVE=dev,mcp,mcp-db
export TAVILY_API_KEY=your-tavily-api-key
export MCP_DBHUB_CONFIG=/path/to/dbhub.toml
mvn spring-boot:run
```

- `dbhub`: `npx -y ${MCP_DBHUB_PACKAGE} --transport stdio --config ${MCP_DBHUB_CONFIG}`，默认 `@bytebase/dbhub@0.21.2`，用于多数据库只读查询。

默认 [config/dbhub.toml](config/dbhub.toml) 是模板文件，不包含活跃数据源。不要把内存 SQLite 作为默认启动源：DBHub 的 SQLite connector 依赖可选原生包 `better-sqlite3`，在 `npx` 临时安装场景下可能不存在，并导致应用启动失败。实际使用时建议把 PostgreSQL、MySQL、MariaDB、SQL Server 等真实数据库配置放到仓库外部文件，并全部使用只读账号。

### 4. 容器化启动

如果希望连应用一起用 Docker Compose 启动：

```bash
export DASHSCOPE_API_KEY=your-api-key
docker compose up -d --build
```

`docker-compose.yml` 中的 app 会等待 Milvus、Redis 和 PostgreSQL 健康后再启动，并将上传文件、聊天历史、旧 Incident JSON 导入目录和 PostgreSQL 数据挂载到本地目录。

## 常用命令

| 命令 | 说明 |
|------|------|
| `mvn spring-boot:run` | 本地启动应用 |
| `mvn test` | 运行全部 Java 测试 |
| `mvn -Ppostgres-it verify` | 运行完整 Maven 门禁和 PostgreSQL Testcontainers 集成测试；Docker 不可用时集成测试会跳过 |
| `node --check src/main/resources/static/app.js` | 检查前端 JS 语法 |
| `node --test src/test/js/evidenceRendering.test.mjs` | 检查事故详情、证据工作台和事故历史筛选栏渲染 |
| `node --test src/test/js/incidentFrontendActions.test.mjs` | 检查事故前端操作 URL 与动作绑定 |
| `docker compose up -d etcd minio milvus redis postgres attu` | 启动本地依赖 |
| `docker compose config` | 校验 Compose 配置 |
| `docker compose up -d --build` | 构建并启动完整容器栈 |
| `docker compose down` | 停止 Compose 服务 |
| `make upload` | 上传 `aiops-docs/` 下的文档 |
| `make check` | 检查应用健康状态 |

## 知识库使用

### 上传文档

```bash
curl -X POST http://localhost:9900/api/upload \
  -F "file=@aiops-docs/cpu_high_usage.md" \
  -H "Accept: application/json" \
  -H "X-API-Key: ${APP_ADMIN_TOKEN:-$APP_API_TOKEN}"
```

上传成功只表示文件已落盘并提交索引任务，返回中会包含：

```json
{
  "indexTaskId": "task-...",
  "indexStatus": "INDEXING",
  "message": "文件已接收，索引处理中"
}
```

查询索引任务：

```bash
curl http://localhost:9900/api/upload/status/{indexTaskId} \
  -H "X-API-Key: ${APP_API_TOKEN}"
curl http://localhost:9900/api/knowledge/index-tasks \
  -H "X-API-Key: ${APP_API_TOKEN}"
```

测试知识库检索：

```bash
curl "http://localhost:9900/api/knowledge/search?query=cpu&topK=5" \
  -H "X-API-Key: ${APP_API_TOKEN}"
```

知识库普通检索只会搜索 `metadata.doc_type=document` 的数据，不会返回私人记忆或历史故障案例。

## 聊天与记忆

### 流式聊天

```bash
curl -N -X POST http://localhost:9900/api/chat_stream \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-123","Question":"CPU 使用率过高怎么排查？"}'
```

### 非流式聊天

```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"session-123","Question":"什么是 Milvus？"}'
```

### 会话管理

| 接口 | 说明 |
|------|------|
| `GET /api/chat/sessions` | 列出已持久化的聊天会话 |
| `GET /api/chat/session/{sessionId}` | 获取会话摘要 |
| `GET /api/chat/session/{sessionId}/messages` | 获取完整聊天历史 |
| `POST /api/chat/clear` | 清空指定会话 |
| `DELETE /api/chat/session/{sessionId}` | 删除指定会话 |

当前记忆策略：

- 近期上下文窗口最多保留 6 对用户/助手消息，用于下一轮提示词。
- 完整聊天历史写入 PostgreSQL 的 `chat_sessions` / `chat_messages` 表。
- Redis 是热缓存，默认 TTL 1 小时；重启后可从 PostgreSQL 恢复最近窗口。
- `APP_CHAT_HISTORY_PATH` 仅用于首次启动时导入旧 JSON 聊天文件，导入成功后源文件保留。
- 被窗口淘汰的消息会异步提炼为 `chat_memory`，查询时必须同时匹配当前 `tenant_id + session_id`。

## AIOps 诊断流程

### 告警接入

Prometheus Alertmanager webhook 地址：

```text
http://localhost:9900/api/webhook/alert
```

告警进入系统后的流程：

1. 按 fingerprint 或归一化后的关键标签归并为一个 Incident。
2. 创建一次 DiagnosisRun，初始状态为 `QUEUED`。
3. 诊断开始后进入 `RUNNING` 或 `WAITING_TOOL`。
4. 诊断前自动召回相似历史故障案例。
5. 对 CPU、内存、错误率、P99、慢 SQL、依赖、重启和多告警关联类告警自动预取指标趋势证据；识别出的 Runbook 按声明的指标/窗口顺序串行预取，未知告警仍使用可配置并发预取。无数据告警进入独立 Runbook，先确认活动告警，再由执行器从指标目录选择指标并核查系统日志；多告警按完整告警集合生成顺序无关关联键，并在诊断上下文中保留关联数量和分组信息。
6. Agent 调用知识库、指标、日志等工具，工具结果写入 DiagnosisEvidence。
7. 最终报告只保存在 `DiagnosisRunRecord.report`，并追加证据校验段。

无 fingerprint 时，系统会将 `CPUUsageHigh / HighCPUUsage / CpuHigh` 等同义告警名归一化，再结合服务、实例和级别生成聚合键。不同实例默认仍拆成不同 Incident，避免误合并。

### 手动触发诊断

```bash
curl -X POST http://localhost:9900/api/incidents/{incidentId}/diagnose
```

正式事故诊断入口是 `POST /api/incidents/{incidentId}/diagnose`。该路径会创建 `DiagnosisRun`、记录工具 evidence、执行报告校验和质量评分。

`POST /api/ai_ops` 仅保留为演示入口，不持久化 `DiagnosisRun` 证据链，不应作为生产诊断入口。

### Incident 接口

| 接口 | 说明 |
|------|------|
| `GET /api/incidents` | 事故列表 |
| `GET /api/incidents/{incidentId}` | 事故详情，含告警、诊断运行、证据和报告 |
| `GET /api/incidents/{incidentId}/runs` | 诊断运行列表 |
| `POST /api/incidents/{incidentId}/diagnose` | 重新执行诊断 |
| `POST /api/incidents/{incidentId}/archive-case` | 将已完成诊断写入历史案例库 |
| `GET /api/incidents/{incidentId}/similar-cases?topK=3` | 查询相似历史故障案例 |

### 诊断状态

| 状态 | 含义 |
|------|------|
| `QUEUED` | 已创建诊断任务，等待执行 |
| `RUNNING` | 正在拆解任务或处理工具结果 |
| `WAITING_TOOL` | 正在等待某个工具返回 |
| `COMPLETED` | 已生成最终诊断报告 |
| `FAILED` | 诊断失败，`errorMessage` 保存失败原因 |
| `CANCELLED` | 用户或系统取消诊断 |

事故详情页支持对活跃诊断执行取消操作，后端会将运行标记为 `CANCELLED` 并记录取消 evidence。

### 工具证据

每个工具调用会记录：

- `id`: evidence id，报告引用格式为 `[evidence: ev-xxxx]`
- `toolName`: 工具名
- `queryParams`: 查询参数
- `timeRange`: 时间范围或工具范围
- `summary`: 返回摘要
- `rawFragment`: 原始片段
- `success/errorMessage`: 成功状态和错误信息
- `attemptCount/durationMs/retryable`: 实际尝试次数、耗时和是否属于可重试工具

前端事故详情会将工具证据分组展示为：相似历史案例、指标趋势、日志查询、知识库检索、活动告警、时间工具、其他工具。证据区还会显示成功数、失败数、重试数和总耗时，便于判断诊断质量受模型、工具还是外部依赖影响。模型调用还会通过 Micrometer 记录按操作/模型/结果聚合的调用数、耗时、Token（若 provider 返回 usage）和估算成本；Prompt、报告和原始日志不会进入指标标签。

`DiagnosisReportService` 注入给 Agent 的证据表同样包含 `attemptCount`、`durationMs` 和 `retryable`。因此最终报告不仅能引用“哪个工具证据”，也能看到该证据是否经过重试、是否因为熔断或依赖异常导致缺失。

诊断工具调用会做基础治理：同一 DiagnosisRun 内相同 `toolName + queryParams` 会去重；`queryLogs` 默认最多调用 3 次，超过后返回 `TOOL_BUDGET_EXCEEDED`，重复调用返回 `TOOL_DUPLICATE_SKIPPED`，报告必须如实说明证据不足。

Planner、Executor、最终报告 system prompt 和动态收口模板位于 `src/main/resources/prompts/`，由 `AiOpsPromptCatalog` 启动时校验并提供独立版本号；诊断启动日志会记录四类 Prompt 版本。修改提示词必须递增对应版本，并重新运行离线评测。

### 前端事故工作台

事故历史面板支持按关键字、事故状态、级别、诊断状态和人工确认状态筛选。筛选栏在桌面端拆成两行：第一行放搜索、事故状态和级别，第二行放诊断状态、人工确认和操作按钮；移动端自动降为单列，避免筛选栏宽于事故卡片列表。

事故详情页以“证据链工作台”为核心视图：

- 顶部摘要卡片展示事故级别、事故状态、告警次数、最新诊断状态、人工确认状态、证据数量、失败证据、重试证据和总耗时。
- 左侧诊断时间线聚合事故创建、最新告警、诊断创建/开始/完成/失败、工具成功/失败、熔断等事件。
- 主体区域展示工具证据分组，保留每条 evidence 的参数、趋势图、耗时、重试和错误信息。
- 下方将 AI 诊断报告、质量评分、人工确认动作和历史案例写入动作分区展示。

前端渲染逻辑集中在 `src/main/resources/static/app.js`，样式集中在 `src/main/resources/static/styles.css`。改动事故详情或事故历史筛选时，应同步运行：

```bash
node --test src/test/js/evidenceRendering.test.mjs
node --test src/test/js/incidentFrontendActions.test.mjs
```

## Agent 工具

| 工具 | 方法 | 说明 |
|------|------|------|
| `DateTimeTools` | `getCurrentDateTime` | 获取当前时间 |
| `InternalDocsTools` | `queryInternalDocs` | 检索普通知识库文档 |
| `QueryMetricsTools` | `queryPrometheusAlerts` | 查询 Prometheus 当前活动告警 |
| `QueryMetricsTools` | `queryMetricTrend` | 查询核心指标趋势 |
| `QueryLogsTools` | `getAvailableLogTopics` | 查询可用日志主题 |
| `QueryLogsTools` | `queryLogs` | 查询 CLS 或 mock 日志 |
| `IncidentCaseService` | `searchSimilarIncidentCases` | 诊断前内部召回相似历史案例，并作为工具证据保存 |

`queryMetricTrend` 支持：

- `cpu_usage`
- `memory_usage`
- `error_rate`
- `p99_latency`
- `restart_count`

`window` 支持 `15m`、`1h`、`6h`；`step` 不传时默认分别为 `30s`、`1m`、`5m`。

## 安全与配置

默认启用鉴权。运行时由 Spring Security FilterChain 处理 API Key 和签名会话，浏览器通过 `HttpOnly + SameSite` Cookie 登录，机器客户端可使用 `X-API-Key`；生产环境必须使用强随机密钥。
启动 `prod` profile 时会执行生产配置校验：必须提供 DashScope Key、API Token、独立的 Webhook 签名 Secret、独立的会话签名 Secret 和非本地 CORS 白名单；同时禁止开启 Prometheus/CLS mock 和模拟告警接口。

| 场景 | Header |
|------|--------|
| 普通 `/api/**` 请求 | `X-API-Key: ${APP_API_TOKEN}` |
| `/api/webhook/alert` 请求 | `X-Webhook-Timestamp`、`X-Webhook-Nonce`、`X-Webhook-Signature` |

内置前端首次访问时会提示输入 `APP_API_TOKEN`，登录后使用 HttpOnly Cookie；SSE 连接会自动携带该 Cookie。

浏览器写请求由前端从 `SB_CSRF` Cookie 复制令牌到 `X-CSRF-Token` 请求头；缺少匹配令牌的会话请求会被 Spring Security 拒绝。显式设置 `APP_SECURITY_ENABLED=false` 只适用于本地开发，会使用单独的开放安全链。

本文中的机器端 `/api/**` 示例在鉴权开启时都需要 `X-API-Key`。Webhook 请求使用独立签名 Secret，签名内容为 `timestamp.nonce.rawBody`，并以 `sha256=<hex>` 放入 `X-Webhook-Signature`；超过时间窗口或重复 nonce 的请求会被拒绝。

配置 `APP_ADMIN_TOKEN` 后，普通 API 令牌仅拥有操作员权限；知识库文件上传等管理操作必须使用管理员令牌登录。生产环境未配置独立管理员令牌时会失败关闭，禁止普通 API 令牌隐式获得管理员权限。鉴权开启时，所有 `/api/**` 请求以及诊断触发、取消、确认、驳回、案例归档、文件上传和机器令牌操作都会写入低敏感度安全审计表；审计只保存主体、角色、令牌类型/ID、路径、结果和请求关联 ID，不保存 Token、Cookie、Prompt 或原始日志。管理员可通过 `GET /api/system/security-audit?limit=50` 查询最近记录。

启用 `APP_SECURITY_MACHINE_TOKEN_ENABLED` 后，已认证的静态 API Key 可通过 `POST /api/auth/token` 换取短期 Bearer 令牌；令牌默认 5 分钟有效，管理员可通过 `POST /api/auth/token/revoke` 按 `tokenId` 和 `expiresAt` 撤销。机器令牌必须按资源 scope 使用：`incidents:read` 读取事故/诊断证据，`diagnosis:trigger` 触发诊断，`diagnosis:cancel` 取消诊断，`diagnosis:review` 确认或驳回结果，`cases:write` 归档历史案例，`documents:write` 配合管理员角色上传知识库文件；缺少 scope 的请求会返回 403，旧 API Key/session 调用保持现有角色兼容。生产环境必须配置独立的 `APP_SECURITY_MACHINE_TOKEN_SIGNING_SECRET` 或轮换列表，并启用 Redis 撤销存储。

机器令牌可在签发请求中指定 `tenantId`，租户身份会写入签名令牌并由每次请求恢复到租户上下文；普通操作员只能为当前兼容租户签发，管理员才可为其他租户签发。可选 OIDC 使用 `APP_SECURITY_OIDC_ENABLED=true` 开启，并配置 `APP_SECURITY_OIDC_ISSUER_URI`、`APP_SECURITY_OIDC_CLIENT_ID`、`APP_SECURITY_OIDC_CLIENT_SECRET`、`APP_SECURITY_OIDC_REDIRECT_URI` 和 `APP_SECURITY_OIDC_SCOPES`；应用会通过标准 Spring Security OIDC discovery 建立 `enterprise` client registration，并从可信 `tenant_id` claim 恢复租户；生产环境缺少租户 claim 会拒绝登录。Webhook 则从签名告警的 `commonLabels.tenant_id`、`commonLabels.tenant` 或告警分组标签解析租户。Incident、告警子表、聊天历史、诊断运行/证据、索引任务、后台任务和安全审计会按当前租户隔离；Redis 会话热缓存、私人记忆和普通文档向量的写入/检索也会带租户边界，后台异步记忆提炼和文档索引会恢复租户上下文。已有未标记的旧向量不会自动归属租户，可通过管理员只读 `/api/system/milvus-vectors/inventory` 和 `make vector-inventory` 盘点；后续只能重新索引或执行受控迁移。

模拟告警接口 `/api/alerts/simulate` 只有在 `APP_ALERT_SIMULATE_ENABLED=true` 时可用。CORS 使用 `APP_CORS_ALLOWED_ORIGINS` 白名单，不再默认放开 `*`。

### 主要环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `DASHSCOPE_API_KEY` | 无 | DashScope API Key，必需 |
| `MILVUS_HOST` | `localhost` | Milvus 主机 |
| `MILVUS_PORT` | `19530` | Milvus 端口 |
| `REDIS_HOST` | `localhost` | Redis 主机 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `PROMETHEUS_BASE_URL` | `http://localhost:9090` | Prometheus 地址 |
| `APP_DEPENDENCY_PROBES_ENABLED` | `true` | 是否启用显式依赖连通性探针 |
| `APP_DEPENDENCY_PROBE_TIMEOUT_MILLIS` | `2000` | 单个依赖探针超时时间（毫秒） |
| `PROMETHEUS_API_KEY` | 空 | Prometheus 探针可选认证令牌；探针还要求返回 `status=success` 和版本字段 |
| `DASHSCOPE_PROBE_URL` | `https://dashscope.aliyuncs.com/api/v1` | DashScope 探针 Base URL |
| `DASHSCOPE_PROBE_PATH` | `/deployments/models?page_no=1&page_size=1&version=v1.0&model_source=base` | DashScope 只读业务探针路径；要求返回 `output.models` 结构 |
| `PROMETHEUS_MOCK_ENABLED` | `false` | Prometheus mock 开关，dev profile 默认为 true |
| `CLS_MOCK_ENABLED` | `false` | CLS mock 开关，dev profile 默认为 true |
| `CLS_BASE_URL` | 空 | 真实日志查询网关地址，配置后 `queryLogs` 会访问 `${CLS_BASE_URL}${CLS_QUERY_PATH}` |
| `CLS_PROBE_PATH` | `/` | CLS 只读探针路径；启用原生签名后使用 TC3-HMAC-SHA256 请求头 |
| `CLS_QUERY_PATH` | `/api/v1/logs/query` | 日志查询路径 |
| `CLS_API_KEY` | 空 | 日志网关 API Key，请求头为 `X-API-Key` |
| `CLS_NATIVE_SIGNING_ENABLED` | `false` | 是否使用 CLS 原生 TC3-HMAC-SHA256 签名 |
| `CLS_SECRET_ID` / `CLS_SECRET_KEY` | 空 | 原生 CLS 签名凭证；不得写入仓库或日志 |
| `CLS_REGION` / `CLS_SERVICE` | `ap-guangzhou` / `cls` | 原生 CLS 签名范围 |
| `CLS_TIMEOUT` | `10` | 日志查询超时秒数 |
| `FILE_UPLOAD_PATH` | `./uploads` | 上传文件目录 |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:9900,http://127.0.0.1:9900` | CORS 白名单 |
| `APP_SECURITY_ENABLED` | `true` | API 鉴权开关，关闭仅限隔离测试环境 |
| `APP_API_TOKEN` | 必填 | 普通 API 令牌 |
| `APP_ADMIN_TOKEN` | 空 | 管理员 API 令牌；生产环境必填，并与普通令牌隔离管理权限 |
| `APP_WEBHOOK_SECRET` | 兼容 | 旧版 Webhook Secret 配置；新部署应使用 `APP_WEBHOOK_SIGNING_SECRET` |
| `APP_WEBHOOK_SIGNING_SECRET` | 兼容（prod/Compose） | Webhook HMAC 单值签名 Secret；若使用 `APP_WEBHOOK_SIGNING_SECRETS` 轮换列表，可由列表提供唯一密钥来源，且必须与 API/会话 Secret 分离 |
| `APP_WEBHOOK_SIGNING_SECRETS` | 可选 | 轮换过渡窗口，按“新密钥,旧密钥”排列；切换完成后移除旧密钥即可撤销 |
| `APP_SECURITY_SESSION_SIGNING_SECRET` | 必填（prod/Compose） | 浏览器会话签名 Secret，必须与 API 和 Webhook Secret 分离 |
| `APP_SECURITY_MACHINE_TOKEN_ENABLED` | `false`（prod 开启） | 是否启用短期机器 Bearer 令牌 |
| `APP_SECURITY_MACHINE_TOKEN_SIGNING_SECRET` | 启用时必填 | 机器令牌签名 Secret，必须独立于 API、会话和 Webhook Secret |
| `APP_SECURITY_MACHINE_TOKEN_SIGNING_SECRETS` | 可选 | 机器令牌轮换过渡窗口，按“新密钥,旧密钥”排列 |
| `APP_SECURITY_MACHINE_TOKEN_TTL_SECONDS` | `300` | 机器令牌最长有效期 |
| `APP_SECURITY_MACHINE_TOKEN_CLOCK_SKEW_SECONDS` | `30` | 机器令牌允许的时钟偏差 |
| `APP_SECURITY_MACHINE_TOKEN_REQUIRE_REDIS` | `false`（prod 强制 `true`） | 是否要求 Redis 提供跨实例撤销存储 |
| `APP_SECURITY_DEFAULT_TENANT_ID` | `default` | 未携带租户身份的旧 API Key/session 和旧数据使用的兼容租户；生产多租户部署应使用签名机器令牌或带租户标签的 Webhook |
| `APP_WEBHOOK_MAX_AGE_SECONDS` | `300` | Webhook 签名允许的最大时间偏差 |
| `APP_WEBHOOK_REPLAY_REQUIRE_REDIS` | `false`（prod 强制 `true`） | 是否要求使用 Redis 做跨实例 nonce 防重放；生产环境必须启用 |
| `APP_SECURITY_SESSION_TTL_SECONDS` | `28800` | 浏览器登录会话有效期（秒） |
| `APP_SECURITY_COOKIE_SECURE` | `false` | 是否要求登录 Cookie 仅通过 HTTPS 发送；prod profile 默认 true |
| `APP_SECURITY_OIDC_ENABLED` | `false` | 是否启用企业 OIDC；生产启用时必须配置 issuer、client 和可信租户 claim |
| `APP_SECURITY_OIDC_ISSUER_URI` | 空 | OIDC issuer URI，用于标准 discovery |
| `APP_SECURITY_OIDC_CLIENT_ID` / `APP_SECURITY_OIDC_CLIENT_SECRET` | 空 | OIDC client 凭据 |
| `APP_SECURITY_OIDC_REDIRECT_URI` | `{baseUrl}/login/oauth2/code/enterprise` | OIDC 回调地址 |
| `APP_SECURITY_OIDC_SCOPES` | `openid,profile,email` | OIDC 请求 scope |
| `APP_SECURITY_OIDC_TENANT_CLAIM` | `tenant_id` | OIDC 租户 claim 名称 |
| `APP_SECURITY_OIDC_GROUPS_CLAIM` | `groups` | OIDC 角色组 claim 名称 |
| `APP_MODEL_NAME` | `qwen3-max` | 模型调用观测使用的默认模型标签；真实响应元数据可覆盖该标签 |
| `APP_MODEL_USAGE_ENABLED` | `true` | 是否记录 provider 返回的 Prompt/Completion Token 用量 |
| `APP_MODEL_COST_CURRENCY` | `CNY` | 估算模型成本的货币标签 |
| `APP_MODEL_INPUT_COST_PER_1K_TOKENS` | `0` | 输入 Token 每 1,000 个的估算单价；`0` 表示未配置价格 |
| `APP_MODEL_OUTPUT_COST_PER_1K_TOKENS` | `0` | 输出 Token 每 1,000 个的估算单价；`0` 表示未配置价格 |
| `APP_TRACE_EXPORT_ENABLED` | `false` | 是否启用 HTTP span 导出；默认关闭 |
| `APP_TRACE_EXPORT_ENDPOINT` | 空 | Zipkin v2 JSON `/api/v2/spans` 接收地址；启用时必填 |
| `APP_TRACE_EXPORT_API_KEY` | 空 | 可选 exporter Bearer token，不会写入日志或 span |
| `APP_TRACE_SERVICE_NAME` | `superbizagent` | 导出 span 的服务名 |
| `APP_TRACE_EXPORT_SAMPLING_PROBABILITY` | `0.1` | HTTP span 采样比例，范围 0–1 |
| `APP_TRACE_EXPORT_TIMEOUT_MILLIS` | `1000` | 单次 exporter 请求超时 |
| `APP_TRACE_EXPORT_MAX_PENDING` | `100` | exporter 最大待处理请求数，超出后丢弃新 span |
| `APP_LIFECYCLE_ENABLED` | `false` | 是否启用后台数据生命周期清理 |
| `APP_LIFECYCLE_TERMINAL_JOB_RETENTION_DAYS` | `0` | 已完成/失败/取消后台任务保留天数；`0` 表示不清理 |
| `APP_LIFECYCLE_INDEX_TASK_RETENTION_DAYS` | `0` | 已完成/失败/取消知识库索引任务保留天数；`0` 表示不清理 |
| `APP_LIFECYCLE_CHAT_SESSION_RETENTION_DAYS` | `0` | 聊天会话保留天数；`0` 表示不清理 |
| `APP_LIFECYCLE_SECURITY_AUDIT_RETENTION_DAYS` | `0`（prod 默认 365） | 安全审计保留天数；`0` 表示不清理 |
| `APP_TRUSTED_PROXIES` | 空 | 受信任反向代理地址，用于安全地解析转发请求信息 |
| `APP_ALERT_SIMULATE_ENABLED` | `false` | 模拟告警接口开关 |

| `APP_CHAT_HISTORY_PATH` | `./data/chat-history` | 完整聊天历史目录 |
| `APP_INCIDENTS_PATH` | `./data/incidents` | 旧 JSON Incident 导入目录，仅用于从历史文件迁移到 JDBC |
| `APP_INCIDENT_JDBC_URL` | `jdbc:postgresql://localhost:5432/superbizagent` | Incident JDBC 数据库地址 |
| `APP_INCIDENT_JDBC_USERNAME` | `superbizagent` | JDBC 用户名 |
| `APP_INCIDENT_JDBC_PASSWORD` | 必填 | JDBC 密码 |
| `APP_INCIDENT_JDBC_DRIVER_CLASS_NAME` | 空 | 可选 JDBC Driver 类名 |
| `APP_INCIDENT_JDBC_MAX_POOL_SIZE` | `10` | Incident JDBC 连接池最大连接数 |
| `APP_INCIDENT_JDBC_MIN_IDLE` | `1` | Incident JDBC 连接池最小空闲连接数 |
| `APP_INCIDENT_JDBC_CONNECTION_TIMEOUT_MILLIS` | `5000` | Incident JDBC 获取连接超时时间 |
| `APP_INCIDENT_JDBC_INITIALIZATION_FAIL_TIMEOUT_MILLIS` | `-1` | Incident JDBC 初始化失败超时；本地默认懒连接，prod profile 默认 30000 |
| `APP_JOBS_ENABLED` | `true` | durable job worker 总开关 |
| `APP_JOB_POLL_DELAY_MILLIS` | `1000` | 后台任务领取间隔 |
| `APP_JOB_LEASE_DURATION_MILLIS` | `60000` | 运行任务租约时长 |
| `APP_JOB_HEARTBEAT_INTERVAL_MILLIS` | `15000` | 运行任务心跳间隔 |
| `APP_JOB_RECOVERY_DELAY_MILLIS` | `15000` | 失败重试延迟和过期租约扫描间隔 |
| `APP_JOB_WORKER_CONCURRENCY` | `4` | 后台任务执行并发数 |
| `APP_JOB_DIAGNOSIS_MAX_ATTEMPTS` | `2` | 诊断任务最大尝试次数 |
| `APP_JOB_DIAGNOSIS_PREFETCH_CONCURRENCY` | `4` | 诊断任务预取证据的并发数 |
| `APP_JOB_INDEX_MAX_ATTEMPTS` | `3` | 文档索引任务最大尝试次数 |
| `APP_JOB_ARCHIVE_MAX_ATTEMPTS` | `3` | 历史案例归档任务最大尝试次数 |
| `APP_DIRECT_MODEL_ROUTING_ENABLED` | `true` | 是否启用直接模型路由 |
| `APP_DIAGNOSIS_REUSE_ENABLED` | `true` | 同一 Incident 内重复告警是否复用最近完成报告 |
| `APP_DIAGNOSIS_REUSE_WINDOW_MILLIS` | `3600000` | 诊断报告复用时间窗口 |
| `APP_TOOL_CALL_DEDUPLICATION_ENABLED` | `true` | 同一 DiagnosisRun 内工具调用去重开关 |
| `APP_QUERY_LOGS_MAX_CALLS_PER_RUN` | `3` | 单次 DiagnosisRun 中 `queryLogs` 最大调用次数 |
| `APP_QUERY_LOGS_MAX_ATTEMPTS_PER_RUN` | `5` | 单次 DiagnosisRun 中 `queryLogs` 最大尝试次数 |
| `APP_QUERY_INTERNAL_DOCS_MAX_CALLS_PER_RUN` | `3` | 单次 DiagnosisRun 中内部文档工具最大调用次数 |
| `APP_TAVILY_MAX_CALLS_PER_RUN` | `2` | 单次 DiagnosisRun 中 Tavily 最大调用次数 |
| `APP_DBHUB_MAX_CALLS_PER_RUN` | `2` | 单次 DiagnosisRun 中 DBHub 最大调用次数 |
| `APP_MAX_TOOL_CALLS_PER_RUN` | `12` | 单次 DiagnosisRun 最大工具调用预算 |
| `APP_MAX_TOOL_ATTEMPTS_PER_RUN` | `16` | 单次 DiagnosisRun 最大工具尝试预算 |
| `APP_MAX_SUPERVISOR_ROUNDS` | `8` | 单次 DiagnosisRun 最大 Planner/Executor 编排轮数 |
| `APP_STALE_RUN_TIMEOUT_MILLIS` | `600000` | 活跃诊断 run 超时判定窗口 |
| `APP_STALE_RUN_SWEEP_DELAY_MILLIS` | `60000` | 超时诊断 run 扫描间隔 |
| `APP_RESILIENCE_ENABLED` | `true` | 依赖熔断/重试总开关 |
| `APP_RESILIENCE_FAILURE_RATE_THRESHOLD` | `50` | 熔断失败率阈值 |
| `APP_RESILIENCE_SLOW_CALL_RATE_THRESHOLD` | `50` | 慢调用比例阈值 |
| `APP_RESILIENCE_SLOW_CALL_DURATION` | `5s` | 默认慢调用判定耗时 |
| `APP_RESILIENCE_MINIMUM_CALLS` | `5` | 熔断统计窗口最小调用数 |
| `APP_RESILIENCE_HALF_OPEN_CALLS` | `2` | 半开状态允许探测调用数 |
| `APP_RESILIENCE_OPEN_DURATION` | `30s` | 熔断打开后等待恢复时间 |
| `APP_RESILIENCE_RETRY_MAX_ATTEMPTS` | `1` | 默认依赖重试次数，1 表示不重试 |
| `APP_RESILIENCE_RETRY_WAIT_DURATION` | `300ms` | 默认依赖重试等待时间 |
| `APP_PROMETHEUS_RETRY_MAX_ATTEMPTS` | `2` | Prometheus 只读查询最大尝试次数 |
| `APP_PROMETHEUS_RETRY_WAIT_DURATION` | `300ms` | Prometheus 查询重试等待时间 |
| `APP_CLS_RETRY_MAX_ATTEMPTS` | `2` | CLS 日志查询最大尝试次数 |
| `APP_CLS_RETRY_WAIT_DURATION` | `300ms` | CLS 日志查询重试等待时间 |
| `APP_MCP_TAVILY_RETRY_MAX_ATTEMPTS` | `2` | Tavily MCP 查询最大尝试次数 |
| `APP_MCP_TAVILY_RETRY_WAIT_DURATION` | `500ms` | Tavily MCP 查询重试等待时间 |
| `APP_MCP_DBHUB_RETRY_MAX_ATTEMPTS` | `2` | DBHub MCP 只读查询最大尝试次数 |
| `APP_MCP_DBHUB_RETRY_WAIT_DURATION` | `500ms` | DBHub MCP 查询重试等待时间 |
| `APP_PRIVATE_MEMORY_RECALL_ENABLED` | `true` | 私人记忆召回开关 |
| `APP_PRIVATE_MEMORY_RECALL_GATING_ENABLED` | `true` | 是否启用私人记忆相关性门控 |
| `APP_PRIVATE_MEMORY_RECALL_TOP_K` | `3` | 私人记忆召回数量 |
| `APP_PRIVATE_MEMORY_RECALL_MIN_SCORE` | `0.0` | 私人记忆召回最低相关性分数 |
| `APP_PRIVATE_MEMORY_RECALL_MAX_PROMPT_CHARS` | `1200` | 注入提示词的私人记忆最大字符数 |
| `APP_MEMORY_EXTRACTION_DEBOUNCE_MILLIS` | `5000` | 记忆提炼防抖时间（毫秒） |
| `APP_MEMORY_EXTRACTION_BATCH_SIZE` | `6` | 单批记忆提炼消息数 |
| `APP_MEMORY_EXTRACTION_MAX_QUEUE_MESSAGES` | `100` | 记忆提炼队列最大消息数 |
| `APP_MEMORY_EXTRACTION_MAX_PROMPT_CHARS` | `6000` | 记忆提炼提示词最大字符数 |
| `APP_MEMORY_EXTRACTION_MAX_FACTS` | `8` | 单批最多提炼事实数 |
| `RAG_SEARCH_EF` | `64` | Milvus HNSW 搜索 ef 参数 |
| `MCP_CLIENT_ENABLED` | `true` | MCP profile 下 MCP Client 开关 |
| `MCP_REQUEST_TIMEOUT` | `60s` | MCP 工具请求超时 |
| `MCP_TAVILY_COMMAND` | `npx` | Tavily MCP 启动命令 |
| `MCP_TAVILY_PACKAGE` | `tavily-mcp@0.2.9` | Tavily MCP npm 包版本 |
| `TAVILY_API_KEY` | 空 | Tavily API Key，启用 Tavily MCP 时必需 |
| `MCP_DBHUB_COMMAND` | `npx` | DBHub MCP 启动命令 |
| `MCP_DBHUB_PACKAGE` | `@bytebase/dbhub@0.21.2` | DBHub MCP npm 包版本 |
| `MCP_DBHUB_CONFIG` | `./config/dbhub.toml` | DBHub 多数据库配置文件路径 |

DashScope 默认探针使用官方的只读[可部署模型列表接口](https://help.aliyun.com/zh/model-studio/list-deployable-models-api)，不会发起模型推理；如果 API Key 没有该列表接口的权限，探针会显示 `AUTH_FAILED`，这只说明探针凭据不足，不能单独推断 Chat/Embedding 调用必然失败。需要时可用 `DASHSCOPE_PROBE_PATH` 改为账号实际允许的只读部署查询路径。

运行时业务状态只写 PostgreSQL，不再写 Incident 或聊天 JSON。Flyway 脚本位于 `src/main/resources/db/migration/incidents`，覆盖 normalized operational tables 和 `background_jobs`。应用启动时会幂等导入旧 `incidents.payload`、`APP_INCIDENTS_PATH` 和 `APP_CHAT_HISTORY_PATH` 数据，并在 `legacy_import_markers` 记录完成标记；源行和源文件不会删除。升级前应先备份 PostgreSQL 和旧数据目录。

诊断和文档索引请求只创建 durable job。诊断 run 与对应 job 在同一个数据库事务内创建；文档索引 task 与对应 job 也在同一个数据库事务内创建，任一写入失败都会整体回滚，不留下孤立 run、task 或 job。Worker 原子领取任务，持有带 `lease_token` 的租约并定期刷新；进程中断后，过期租约会转为 `RETRY` 或在尝试耗尽后转为 `FAILED`，旧 worker 的 heartbeat、完成和失败回写会被 fencing 条件拒绝。取消先在数据库事务中把 DiagnosisRun 置为 `CANCELLED` 并标记 job 的 `cancel_requested`，事务提交后再尽力中断本实例正在执行的 Future；终态不会被后续完成/失败回写覆盖。

所有 job 时间、并发和重试配置都必须为正数，并在启动时校验。`APP_JOB_HEARTBEAT_INTERVAL_MILLIS` 必须小于 `APP_JOB_LEASE_DURATION_MILLIS / 2`，否则应用拒绝启动，避免心跳过慢导致运行中任务被错误回收。

### Compose 专用变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `POSTGRES_DB` | `superbizagent` | Compose 内 PostgreSQL 数据库名 |
| `POSTGRES_USER` | `superbizagent` | Compose 内 PostgreSQL 用户 |
| `POSTGRES_PASSWORD` | 必填 | Compose 内 PostgreSQL 密码 |
| `POSTGRES_PORT` | `5433` | PostgreSQL 仅绑定宿主机 `127.0.0.1`，不对外网卡暴露 |
| `DOCKER_VOLUME_DIRECTORY` | `.` | Compose 数据、上传和历史目录挂载根路径 |

## MCP 工具

项目通过 Spring AI MCP Client 接入外部 MCP 工具。默认关闭；启用对应 MCP profile 后，`ToolCallbackProvider` 会把 MCP server 暴露的工具注入到 Chat Agent 和 AIOps Agent。

当前预置两个 stdio MCP server：

| MCP Server | Profile | 用途 | 默认命令 |
|------------|---------|------|----------|
| Tavily | `mcp` | 联网查公开资料、官方文档、错误码和版本差异 | `npx -y tavily-mcp@0.2.9` |
| DBHub | `mcp-db` | 多数据库 schema / 只读 SQL 查询 | `npx -y @bytebase/dbhub@0.21.2 --transport stdio --config ${MCP_DBHUB_CONFIG}` |

诊断流程中的约束：

- Tavily 结果只作为外部参考，不覆盖 Incident、指标、日志和内部知识库事实。
- DBHub 只允许只读查询，禁止写入和结构变更 SQL。
- MCP 工具调用会作为 `DiagnosisEvidence` 记录，最终报告需要引用对应 evidence id。
- 只读外部工具通过 `DependencyGuard` 做有限重试和熔断；证据中会记录 `attemptCount`、`durationMs` 和 `retryable`。

## 常用接口补充

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/incidents/{incidentId}/runs/{runId}/evidence` | 查询某次诊断 run 的工具证据 |
| `POST` | `/api/incidents/{incidentId}/runs/{runId}/cancel` | 取消活跃诊断 run |
| `POST` | `/api/incidents/{incidentId}/runs/{runId}/confirm` | 人工确认诊断，并尝试写入历史案例库 |
| `POST` | `/api/incidents/{incidentId}/runs/{runId}/reject` | 人工驳回诊断 |
| `GET` | `/api/system/dependencies` | 查看被熔断治理的外部依赖健康快照 |
| `GET` | `/api/system/dependencies/probe` | 按需执行 Prometheus、CLS、DashScope、Milvus 和 MCP 连通性探针 |
| `GET` | `/api/system/security-audit` | 管理员查询有界的低敏感度安全审计记录 |

## 项目结构

```text
super-biz-agent/
├── src/main/java/org/example/
│   ├── agent/tool/                  # Agent 工具
│   ├── client/                      # Milvus 客户端工厂
│   ├── config/                      # 配置、鉴权、限流、CORS、线程池
│   ├── controller/                  # Chat、Webhook、Alert、Incident、Knowledge、Upload API
│   ├── dto/                         # API、Incident、Diagnosis、Evidence、会话等 DTO
│   └── service/                     # RAG、索引、诊断、证据、记忆、历史案例服务
├── src/main/resources/
│   ├── static/                      # 前端单页应用
│   ├── application.yml              # 默认配置
│   ├── application-dev.yml          # 开发 profile
│   └── application-prod.yml         # 生产 profile
├── aiops-docs/                      # 示例运维知识库文档
├── docker-compose.yml               # Milvus、Redis、PostgreSQL、Attu、应用
├── Dockerfile                       # 多阶段构建镜像
├── Makefile                         # 本地辅助命令
└── pom.xml                          # Maven 构建与质量门禁
```

### 文档与知识库文件

- `README.md` 是仓库入口文档，保留在 GitHub，用于说明部署、配置、接口和验证方式。
- `aiops-docs/` 是随仓库发布的示例运维知识库，上传后可用于本地 RAG 索引和检索。
- `docs/` 仅保存本地技术资料，不上传 GitHub。
- `OPTIMIZATION_PLAN.md` 是本地规划文件，保留在开发机但不纳入 Git 跟踪。

## 测试与质量

除了 Java、前端、Compose 和容器检查外，项目提供一个不调用外部模型的诊断评测契约 smoke 基线，以及一个从运行中应用采集真实持久化报告/证据的在线适配器：

```bash
make eval
```

结果会写入 `target/diagnosis-eval.json` 和 `target/diagnosis-eval.md`，并包含数据集、适配器、模型、Prompt、知识库版本和随机种子元数据。该 smoke 基线只验证场景格式、指标计算、版本记录和声明式门禁；真实上线门禁仍需要脱敏事故集、专家标注和实际诊断结果适配器，不能把 smoke 分数当成模型准确率。

评测输入可通过 `EVAL_SCENARIOS`、`EVAL_RESULTS`、`EVAL_METADATA`、`EVAL_GATE_CONFIG` 覆盖。发布前使用 `make eval-release`；该命令会拒绝 smoke/fixture 元数据，要求 `dataset.source=deidentified-incident`、真实运行信息、专家标注复核/脱敏审批元数据、与场景数匹配的 `annotation.scenarioCount`，以及 `approval.approvedBy`、`approval.approvedAt` 审批字段，并自动拦截明显邮箱、常见令牌和敏感字段中的未脱敏值。该扫描只是安全网，不能替代人工脱敏审批。诊断 Planner/Executor 调用会启用 DashScope 原生 JSON object 输出模式，精确 schema、证据 ID 绑定和失败关闭仍由应用代码校验。

真实运行采集使用：

```bash
make eval-live EVAL_LIVE_SCENARIOS=path/to/approved-scenarios.json
```

适配器会通过签名 Webhook 或已有 `incidentId` 触发/跟踪 durable DiagnosisRun，并把实际报告、持久化 evidence、工具次数和时延写成 JSONL；之后仍需使用专家批准的场景、metadata 和 gate 运行 `make eval-release`。`rootCausePatterns`、`claimMatchers` 和非空 `requiredClaimIds` 是评测标注的一部分，适配器不会从自身输出推断正确性。聊天 RAG 会保留原始问题并追加受控运维术语别名；来源引用必须属于本次检索返回的稳定 ID，伪造 ID 会在完成阶段降级。

完整测试分层、PR 门禁、故障处理和发布前检查见 [TESTING.md](TESTING.md)。

```bash
mvn test
mvn -Ppostgres-it verify
node --check src/main/resources/static/app.js
node --test src/test/js/evidenceRendering.test.mjs src/test/js/incidentFrontendActions.test.mjs
docker compose config
```

Maven Surefire 已预加载 Byte Buddy agent，默认 `mvn test` 不需要额外传 `-DargLine`。`mvn -Ppostgres-it verify` 会通过 Testcontainers 覆盖 PostgreSQL 生产 SQL 和 durable workflow；Docker 不可用时 PostgreSQL 集成测试会跳过，仍会执行普通单元测试和 Maven 质量门禁。项目同时配置了 SpotBugs、PMD 和 Checkstyle；其中 SpotBugs/PMD 用于阻断高风险问题，Checkstyle 当前不阻断风格类问题。

## 常见问题

### 1. 知识库输入关键词检索不到

先检查上传任务是否完成：

```bash
curl http://localhost:9900/api/knowledge/index-tasks
```

只有 `COMPLETED` 的文档会进入检索结果。普通知识库检索过滤条件是 `doc_type=document`，私人记忆 `chat_memory` 和历史故障 `incident_case` 不会混入普通结果。

### 2. 本地诊断工具连接 Prometheus 失败

本地没有 Prometheus 时请打开 mock：

```bash
export PROMETHEUS_MOCK_ENABLED=true
export CLS_MOCK_ENABLED=true
```

或者使用 dev profile：

```bash
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

### 3. 应用启动时报 Milvus 连接失败

确认 Milvus 容器健康，并且 `MILVUS_HOST/MILVUS_PORT` 指向正确地址：

```bash
docker compose ps
curl http://localhost:9091/healthz
```

本地 Maven 启动时通常使用 `MILVUS_HOST=localhost`；容器内 app 使用 `MILVUS_HOST=milvus`。

### 4. 生产环境请求返回 401

生产 profile 默认启用鉴权。普通 API 需要 `X-API-Key`；Webhook 需要时间戳、nonce 和 HMAC 签名请求头。

### 5. Docker 镜像拉取失败

如果 Docker Hub 访问不稳定，可先从可用镜像源拉取 Milvus、MinIO、Attu、Redis，再重新打 tag 后执行 `docker compose up -d`。

## License

MIT
