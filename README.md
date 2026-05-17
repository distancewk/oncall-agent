# SuperBizAgent (OnCall Agent)

SuperBizAgent 是一个基于 Spring Boot、Spring AI Alibaba、DashScope、Milvus 和 Redis 的智能 OnCall 助手。当前项目支持知识库问答、多轮对话、告警聚合、AI 运维诊断、诊断证据记录、指标趋势分析和相似历史故障召回。

## 核心能力

| 模块 | 说明 |
|------|------|
| RAG 知识库问答 | 上传 `.md/.txt` 文档后写入 Milvus，聊天和知识库检索只召回普通文档数据 |
| 多轮对话 | 近期上下文保存在内存/Redis，完整聊天历史持久化到文件，重启后仍可查看历史会话 |
| 私人记忆 | 被窗口淘汰的历史对话可提炼为 `chat_memory`，检索时强制按 `session_id` 隔离 |
| AIOps 诊断 | Webhook 或手动触发 Incident 诊断，生成 DiagnosisRun 和最终报告 |
| 证据链 | 每次工具调用保存为 DiagnosisEvidence，报告需要引用 evidence id |
| 指标趋势 | `queryMetricTrend` 支持 CPU、内存、错误率、P99、重启次数的 15m/1h/6h 趋势查询 |
| 相似历史故障 | 已完成诊断可写入 `incident_case`，新故障诊断前自动召回相似案例 |
| 前端控制台 | 提供聊天、知识库状态/检索、告警历史、事故详情、诊断进度、工具证据分组展示 |
| 安全边界 | 支持 API 鉴权开关、Webhook 共享密钥、CORS 白名单、模拟告警开发开关 |

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
| MinIO / etcd | Compose 内置 | Milvus 依赖 |
| 前端 | 原生 HTML/CSS/JS | 单页控制台，使用 marked、DOMPurify、highlight.js |

## 快速启动

### 环境要求

- Java 17
- Maven 3.6+
- Node.js 18+（仅启用 MCP profile 时需要，用于 `npx` 启动 Tavily/DBHub MCP）
- Docker 和 Docker Compose
- DashScope API Key

### 1. 设置环境变量

```bash
export DASHSCOPE_API_KEY=your-api-key
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
docker compose up -d etcd minio milvus redis attu
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

### 3.1 可选：启用 Tavily 和数据库 MCP

默认启动不启用 MCP。需要联网查询和数据库查询时，使用 `mcp` profile：

```bash
export SPRING_PROFILES_ACTIVE=dev,mcp
export TAVILY_API_KEY=your-tavily-api-key
export MCP_DBHUB_CONFIG=./config/dbhub.toml
mvn spring-boot:run
```

`application-mcp.yml` 会通过 stdio 启动两个 MCP server：

- `tavily`: `npx -y tavily-mcp@latest`，用于公开互联网搜索。
- `dbhub`: `npx -y @bytebase/dbhub@latest --transport stdio --config ${MCP_DBHUB_CONFIG}`，用于多数据库只读查询。

默认 [config/dbhub.toml](config/dbhub.toml) 只包含一个内存 SQLite 示例源。生产环境应将真实数据库配置放到仓库外部文件，并通过 `MCP_DBHUB_CONFIG` 指向该文件。DBHub 配置中可以同时添加 PostgreSQL、MySQL、MariaDB、SQL Server 等多个 source；建议全部使用只读账号。

### 4. 容器化启动

如果希望连应用一起用 Docker Compose 启动：

```bash
export DASHSCOPE_API_KEY=your-api-key
docker compose up -d --build
```

`docker-compose.yml` 中的 app 会等待 Milvus 和 Redis 健康后再启动，并将上传文件、聊天历史和 Incident 数据挂载到本地目录。

## 常用命令

| 命令 | 说明 |
|------|------|
| `mvn spring-boot:run` | 本地启动应用 |
| `mvn test` | 运行全部 Java 测试 |
| `node --check src/main/resources/static/app.js` | 检查前端 JS 语法 |
| `node src/test/js/evidenceRendering.test.mjs` | 检查工具证据前端渲染 |
| `docker compose up -d etcd minio milvus redis attu` | 启动本地依赖 |
| `docker compose up -d --build` | 构建并启动完整容器栈 |
| `docker compose down` | 停止 Compose 服务 |
| `make upload` | 上传 `aiops-docs/` 下的文档 |
| `make check` | 检查应用健康状态 |

## 知识库使用

### 上传文档

```bash
curl -X POST http://localhost:9900/api/upload \
  -F "file=@aiops-docs/cpu_high_usage.md" \
  -H "Accept: application/json"
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
curl http://localhost:9900/api/upload/status/{indexTaskId}
curl http://localhost:9900/api/knowledge/index-tasks
```

测试知识库检索：

```bash
curl "http://localhost:9900/api/knowledge/search?query=cpu&topK=5"
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
- 完整聊天历史写入 `APP_CHAT_HISTORY_PATH`，默认 `./data/chat-history`。
- Redis 是热缓存，默认 TTL 1 小时；重启后可从文件历史恢复最近窗口。
- 被窗口淘汰的消息会异步提炼为 `chat_memory`，查询时必须匹配当前 `session_id`。

## AIOps 诊断流程

### 告警接入

Prometheus Alertmanager webhook 地址：

```text
http://localhost:9900/api/webhook/alert
```

告警进入系统后的流程：

1. 按 fingerprint 或关键标签归并为一个 Incident。
2. 创建一次 DiagnosisRun，初始状态为 `QUEUED`。
3. 诊断开始后进入 `RUNNING` 或 `WAITING_TOOL`。
4. 诊断前自动召回相似历史故障案例。
5. 对 CPU、内存、错误率、P99、重启类告警自动预取指标趋势证据。
6. Agent 调用知识库、指标、日志等工具，工具结果写入 DiagnosisEvidence。
7. 最终报告只保存在 `DiagnosisRunRecord.report`，并追加证据校验段。

### 手动触发诊断

```bash
curl -X POST http://localhost:9900/api/incidents/{incidentId}/diagnose
```

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

当前代码没有对外的 `CANCELLED` 取消接口。

### 工具证据

每个工具调用会记录：

- `id`: evidence id，报告引用格式为 `[evidence: ev-xxxx]`
- `toolName`: 工具名
- `queryParams`: 查询参数
- `timeRange`: 时间范围或工具范围
- `summary`: 返回摘要
- `rawFragment`: 原始片段
- `success/errorMessage`: 成功状态和错误信息

前端事故详情会将工具证据分组展示为：相似历史案例、指标趋势、日志查询、知识库检索、活动告警、时间工具、其他工具。

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

默认本地开发不启用鉴权。生产 profile 中 `APP_SECURITY_ENABLED=true`，请求需要携带凭证。

| 场景 | Header |
|------|--------|
| 普通 `/api/**` 请求 | `X-API-Key: ${APP_API_TOKEN}` |
| `/api/webhook/**` 请求 | `X-Webhook-Secret: ${APP_WEBHOOK_SECRET}` |

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
| `PROMETHEUS_MOCK_ENABLED` | `false` | Prometheus mock 开关，dev profile 默认为 true |
| `CLS_MOCK_ENABLED` | `false` | CLS mock 开关，dev profile 默认为 true |
| `FILE_UPLOAD_PATH` | `./uploads` | 上传文件目录 |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:9900,http://127.0.0.1:9900` | CORS 白名单 |
| `APP_SECURITY_ENABLED` | `false` | API 鉴权开关，prod profile 为 true |
| `APP_API_TOKEN` | 空 | 普通 API 令牌 |
| `APP_WEBHOOK_SECRET` | 空 | Webhook 共享密钥 |
| `APP_ALERT_SIMULATE_ENABLED` | `false` | 模拟告警接口开关 |
| `APP_CHAT_HISTORY_PATH` | `./data/chat-history` | 完整聊天历史目录 |
| `APP_INCIDENTS_PATH` | `./data/incidents` | Incident JSON 存储目录 |
| `APP_PRIVATE_MEMORY_RECALL_ENABLED` | `true` | 私人记忆召回开关 |
| `APP_PRIVATE_MEMORY_RECALL_TOP_K` | `3` | 私人记忆召回数量 |
| `RAG_SEARCH_EF` | `64` | Milvus HNSW 搜索 ef 参数 |
| `MCP_CLIENT_ENABLED` | `true` | `mcp` profile 下 MCP Client 开关 |
| `MCP_REQUEST_TIMEOUT` | `60s` | MCP 工具请求超时 |
| `MCP_TAVILY_COMMAND` | `npx` | Tavily MCP 启动命令 |
| `TAVILY_API_KEY` | 空 | Tavily API Key，启用 Tavily MCP 时必需 |
| `MCP_DBHUB_COMMAND` | `npx` | DBHub MCP 启动命令 |
| `MCP_DBHUB_CONFIG` | `./config/dbhub.toml` | DBHub 多数据库配置文件路径 |

## MCP 工具

项目通过 Spring AI MCP Client 接入外部 MCP 工具。默认关闭；启用 `mcp` profile 后，`ToolCallbackProvider` 会把 MCP server 暴露的工具注入到 Chat Agent 和 AIOps Agent。

当前预置两个 stdio MCP server：

| MCP Server | 用途 | 默认命令 |
|------------|------|----------|
| Tavily | 联网查公开资料、官方文档、错误码和版本差异 | `npx -y tavily-mcp@latest` |
| DBHub | 多数据库 schema / 只读 SQL 查询 | `npx -y @bytebase/dbhub@latest --transport stdio --config ./config/dbhub.toml` |

诊断流程中的约束：

- Tavily 结果只作为外部参考，不覆盖 Incident、指标、日志和内部知识库事实。
- DBHub 只允许只读查询，禁止写入和结构变更 SQL。
- MCP 工具调用会作为 `DiagnosisEvidence` 记录，最终报告需要引用对应 evidence id。

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
├── docs/                            # 项目文档
├── docker-compose.yml               # Milvus、Redis、Attu、应用
├── Dockerfile                       # 多阶段构建镜像
├── Makefile                         # 本地辅助命令
└── pom.xml                          # Maven 构建与质量门禁
```

## 测试与质量

```bash
mvn test
node --check src/main/resources/static/app.js
node src/test/js/evidenceRendering.test.mjs
```

Maven Surefire 已预加载 Byte Buddy agent，默认 `mvn test` 不需要额外传 `-DargLine`。项目同时配置了 SpotBugs、PMD 和 Checkstyle；其中 SpotBugs/PMD 用于阻断高风险问题，Checkstyle 当前不阻断风格类问题。

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

生产 profile 默认启用鉴权。普通 API 需要 `X-API-Key`，Webhook 需要 `X-Webhook-Secret`。

### 5. Docker 镜像拉取失败

如果 Docker Hub 访问不稳定，可先从可用镜像源拉取 Milvus、MinIO、Attu、Redis，再重新打 tag 后执行 `docker compose up -d`。

## License

MIT
