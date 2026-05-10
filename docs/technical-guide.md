# SuperBizAgent 技术文档

## 目录

1. [项目简介](#1-项目简介)
2. [技术栈](#2-技术栈)
3. [系统架构](#3-系统架构)
4. [核心执行流程](#4-核心执行流程)
5. [详细设计](#5-详细设计)
6. [配置参数说明](#6-配置参数说明)
7. [API 接口列表](#7-api-接口列表)
8. [项目结构](#8-项目结构)

---

## 1. 项目简介

SuperBizAgent（OnCall Agent）是一个基于 **Spring Boot 3.2** + **Spring AI 1.1** 框架构建的企业级智能运维助手系统。它通过大语言模型（LLM）与检索增强生成（RAG）技术，结合多智能体协作架构，为企业 IT 运维提供智能化的告警分析、日志查询、知识库问答和报告生成能力。

### 核心能力

| 模块 | 能力 |
|------|------|
| **RAG 智能问答** | 基于 Milvus 向量数据库的文档检索增强问答，支持多轮对话与流式输出 |
| **AIOps 运维诊断** | 基于 Planner-Executor-Replanner 架构的多智能体协同，实现告警自动分析和诊断报告生成 |
| **Webhook 集成** | 接收 Prometheus Alertmanager 告警推送，自动触发诊断流程 |
| **会话管理** | 基于内存 L1 + Redis L2 的两级缓存，支持会话持久化与多轮对话上下文维护 |
| **文件管理** | 支持 .md/.txt 文档上传、切片、向量化索引 |

---

## 2. 技术栈

### 2.1 基础框架

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言，必需版本 |
| Spring Boot | 3.2.0 | 应用主框架 |
| Maven | 3.6+ | 项目构建管理 |

### 2.2 AI / 大模型

| 技术 | 版本 | 说明 |
|------|------|------|
| Spring AI | 1.1.0 | AI 应用框架，提供 Agent 抽象 |
| Spring AI Alibaba DashScope | 1.1.0 | 阿里云 DashScope 大模型集成 |
| DashScope SDK | 2.17.0 | DashScope 原生 Java SDK（直接调用 Embedding、Generation API） |

**模型配置：**

| 用途 | 模型 | 参数 |
|------|------|------|
| 聊天/问答 | qwen3-max | temperature=0.7, maxToken=2000, topP=0.9 |
| AIOps 分析 | qwen3-max | temperature=0.3, maxToken=8000, topP=0.9 |
| 文本向量化 | text-embedding-v4 | 1024 维 |
| 重排序 | gte-rerank | 通过 DashScope Rerank API |

### 2.3 数据库 / 存储

| 技术 | 版本 | 用途 |
|------|------|------|
| Milvus | 2.5.10 | 向量数据库，存储文档向量和用户记忆 |
| Redis | 7-alpine | 会话持久化缓存（L2 存储） |
| MinIO | 2023-03-20 | Milvus 底层对象存储 |
| etcd | v3.5.18 | Milvus 元数据存储 |

### 2.4 外部服务

| 服务 | 用途 |
|------|------|
| Prometheus | 告警数据源（可选），默认 Mock 模式 |
| 腾讯云 CLS | 日志服务（可选），默认 Mock 模式 |
| Tavily | 互联网搜索 MCP 工具（可选，默认禁用） |

### 2.5 前端

| 技术 | 说明 |
|------|------|
| 原生 HTML/CSS/JS | 单页应用，无前端框架 |
| marked.js | Markdown 渲染 |
| highlight.js | 代码语法高亮 |
| SSE (Server-Sent Events) | 流式输出支持 |

### 2.6 代码质量

| 工具 | 用途 |
|------|------|
| SpotBugs | 字节码缺陷检测 |
| Checkstyle | 编码规范检查 |
| PMD | 静态代码分析 |
| Lombok | 简化样板代码 |

---

## 3. 系统架构

### 3.1 整体架构图

```
┌──────────────────────────────────────────────────────────────────────┐
│                        前端 (static/)                                │
│            index.html + styles.css + app.js                         │
│            (Chat UI / SSE 流式 / Markdown 渲染)                     │
└──────────────────────────┬───────────────────────────────────────────┘
                           │ HTTP / SSE
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     Controller 层                                   │
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────┐          │
│  │ ChatController│  │WebhookControl│  │FileUploadControl │          │
│  │              │  │    ler       │  │      ler         │          │
│  │ /api/chat    │  │ /api/webhook │  │ /api/upload      │          │
│  │ /api/chat_str│  │   /alert     │  └──────────────────┘          │
│  │   eam        │  └──────┬───────┘                                │
│  │ /api/chat/cl │         │                                         │
│  │   ear        │         │                                         │
│  │ /api/ai_ops  │         │                                         │
│  └──────┬───────┘         │                                         │
└─────────┼─────────────────┼─────────────────────────────────────────┘
          │                 │
          ▼                 ▼
┌──────────────────────────────────────────────────────────────────────┐
│                      Service 层                                     │
│                                                                     │
│  ┌────────────┐   ┌────────────┐   ┌────────────────────┐          │
│  │ ChatService │   │AiOpsService│   │VectorIndexService  │          │
│  │ - 构建Agent  │   │- 编排多Agent│   │- 文档索引          │          │
│  │ - 执行对话   │   │- 生成报告   │   │- 增量更新          │          │
│  └──────┬──────┘   └──────┬─────┘   └────────┬───────────┘          │
│         │                 │                    │                     │
│         ▼                 ▼                    ▼                     │
│  ┌─────────────────────────────────────────────────────┐            │
│  │                   Agent 框架                        │            │
│  │                                                     │            │
│  │  ┌──────────┐  ┌──────────────────┐                 │            │
│  │  │ReactAgent │  │ SupervisorAgent  │                 │            │
│  │  │(工具调用)  │  │(Planner→Executor │                 │            │
│  │  │           │  │ →Replanner)     │                 │            │
│  │  └─────┬─────┘  └────────┬─────────┘                 │            │
│  │        │                 │                           │            │
│  │        ▼                 ▼                           │            │
│  │  ┌──────────────────────────────────────┐            │            │
│  │  │          Agent 工具集                │            │            │
│  │  │  ┌──────────┐ ┌──────────┐          │            │            │
│  │  │  │DateTime  │ │Internal  │          │            │            │
│  │  │  │Tools     │ │DocsTools │          │            │            │
│  │  │  ├──────────┤ ├──────────┤          │            │            │
│  │  │  │QueryMetri│ │QueryLogs │          │            │            │
│  │  │  │csTools   │ │Tools     │          │            │            │
│  │  │  └──────────┘ └──────────┘          │            │            │
│  │  └──────────────────────────────────────┘            │            │
│  └─────────────────────────────────────────────────────┘            │
│                                                                     │
│  ┌────────────┐   ┌────────────┐   ┌────────────────────┐          │
│  │RagService   │   │MemoryExtra│   │VectorSearchService │          │
│  │- RAG 流式   │   │ctionService│   │- 混合搜索          │          │
│  │- 上下文构建  │   │- 长期记忆  │   │- RRF 融合排序      │          │
│  └────────────┘   └────────────┘   └────────┬───────────┘          │
│                                            │                       │
│  ┌────────────┐   ┌────────────┐            │                       │
│  │VectorEmbed│   │VectorRerank│            │                       │
│  │dingService│   │Service     │            │                       │
│  │- 文本向量化 │   │- 重排序    │            │                       │
│  └────────────┘   └────────────┘            │                       │
│                                            ▼                       │
│  ┌─────────────────────────────────────────────────────┐            │
│  │                SessionManager                       │            │
│  │     L1: ConcurrentHashMap (内存)                    │            │
│  │     L2: Redis (持久化, TTL=1h)                      │            │
│  │     最大窗口: 6 对消息 (12条)                        │            │
│  └─────────────────────────────────────────────────────┘            │
└──────────────────────────────────────────────────────────────────────┘
                           │
                           ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     基础设施层                                       │
│                                                                     │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐            │
│  │  Milvus  │  │  Redis   │  │Prometheus│  │ 腾讯云   │            │
│  │ 向量数据库│  │ 会话缓存  │  │ 告警源   │  │ CLS 日志 │            │
│  │ port:19530│  │ port:6379│  │ port:9090│  │ (可选)   │            │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘            │
└──────────────────────────────────────────────────────────────────────┘
```

### 3.2 架构分层说明

#### Controller 层
- **ChatController**: 统一聊天接口，处理标准/流式聊天、AIOps 触发、会话管理
- **WebhookController**: 接收 Prometheus Alertmanager 的 webhook 推送
- **FileUploadController**: 处理文档上传与向量化
- **MilvusCheckController**: Milvus 健康检查
- **GlobalExceptionHandler**: 全局异常处理，统一返回 `ApiResponse` 格式

#### Service 层
- **ChatService**: 封装 Agent 构建、工具注册、对话执行的公共逻辑
- **AiOpsService**: 多智能体编排核心，管理 Planner/Executor/Supervisor 的协同
- **RagService**: RAG 查询服务，直接调用 DashScope Generation API 进行流式输出
- **VectorEmbeddingService**: 文本向量化，调用 DashScope Text Embedding API
- **VectorSearchService**: 混合检索（稠密向量 + 稀疏向量 + RRF 融合排序）
- **VectorRerankService**: 检索结果重排序，调用 DashScope GTE-Rerank API
- **VectorIndexService**: 文档索引管线（切片 → 向量化 → 存入 Milvus）
- **DocumentChunkService**: 智能文档切片（按 Markdown 标题/段落分割）
- **SessionManager**: 会话管理（L1 内存在线缓存 + L2 Redis 持久化）
- **MemoryExtractionService**: 异步记忆提炼，从历史对话中提取长期记忆存入 Milvus

#### Agent 层

基于 Spring AI Alibaba Agent 框架，提供两种 Agent 模式：

| Agent 类型 | 说明 | 使用场景 |
|-----------|------|---------|
| **ReactAgent** | ReAct 模式，思考→行动→观察循环 | 普通聊天问答、AIOps 中的子任务执行 |
| **SupervisorAgent** | 多智能体编排器，管理 Planner 和 Executor 交互 | AIOps 完整诊断流程 |

#### 工具层

通过 `@Tool` 注解注册，Agent 自动发现和调用：

| 工具 | 方法 | 用途 |
|------|------|------|
| DateTimeTools | getCurrentDateTime() | 获取当前日期时间 |
| InternalDocsTools | queryInternalDocs(query) | 知识库文档检索 |
| QueryMetricsTools | queryPrometheusAlerts() | Prometheus 告警查询 |
| QueryLogsTools | getAvailableLogTopics() / queryLogs() | 日志服务查询 |

#### 基础设施层
- **Milvus**: 向量存储（HNSW 索引，COSINE 度量，1024 维），集合名 `biz`
- **Redis**: 会话持久化，Key 前缀 `session:`，TTL 1 小时
- **Prometheus**: 告警源（可选），默认 Mock 模式
- **腾讯云 CLS**: 日志服务（可选），默认 Mock 模式

---

## 4. 核心执行流程

### 4.1 聊天问答流程（/api/chat 和 /api/chat_stream）

```
用户请求
  │
  ▼
ChatController 接收请求 (Id + Question)
  │
  ├── sessionManager.getOrCreateSession(id)   ← 获取/创建会话
  │    │
  │    ├── L1 缓存 (ConcurrentHashMap) 命中 → 直接返回
  │    ├── L2 Redis 命中 → 加载到 L1 后返回
  │    └── 均未命中 → 创建新 SessionInfo
  │
  ├── chatService.createReactAgent(model, prompt)
  │    │
  │    ├── 构建 System Prompt（含历史消息）
  │    ├── 注册工具：DateTimeTools, InternalDocsTools,
  │    │   QueryMetricsTools, QueryLogsTools (条件注册)
  │    └── 注册 MCP 工具（如启用）
  │
  ├── 执行 Agent
  │    │
  │    ├── 非流式: agent.call(question) → 返回完整答案
  │    └── 流式: agent.stream(question) → SSE 逐块推送
  │         │
  │         ├── AGENT_MODEL_STREAMING → content_chunk
  │         ├── AGENT_TOOL_FINISHED → tool_use 事件
  │         ├── AGENT_MODEL_FINISHED → 回答结束
  │         └── AGENT_HOOK_FINISHED → 流程结束
  │
  └── session.addMessage(question, answer, manager)
       │
       ├── 写入内存 messageHistory
       ├── 检查是否超过 6 对 → 移除最旧消息
       ├── 持久化到 Redis ← 新增消息即时保存
       └── 异步触发记忆提炼（被淘汰的消息）
```

### 4.2 AIOps 诊断流程（/api/ai_ops）

```
触发 AIOps（手动调用或 Webhook 接收告警）
  │
  ▼
AiOpsService.executeAiOpsAnalysis()
  │
  ├── 1. 构建 Planner Agent（ReactAgent）
  │    │    System Prompt 中包含分析指令：
  │    │    - 分析当前告警
  │    │    - 访问知识库、日志、指标
  │    │    - 生成 Markdown 诊断报告
  │    │
  ├── 2. 构建 Executor Agent（ReactAgent）
  │    │    - 执行 Planner 制定的步骤
  │    │    - 调用具体工具获取数据
  │    │
  ├── 3. 构建 SupervisorAgent
  │    │    编排流程：
  │    │    a. Planner 制定计划
  │    │    b. Executor 执行计划
  │    │    c. 反馈循环（必要时 replan）
  │    │    d. 完成 → 输出最终报告
  │    │
  ├── 4. 提取报告
  │    │    extractFinalReport(state) → 读取 planner_plan 输出
  │    │
  └── 5. 流式输出报告
       │    SSE 逐行推送报告文本
       └── 更新会话历史
```

AIOps Planner 的 System Prompt 核心指令：

```
你的任务包括：
1. 理解用户的问题或告警信息
2. 分析后续执行结果
3. 使用专业知识，结合执行结果进行分析诊断
4. 按要求的 Markdown 模板输出诊断报告

报告模板要求：
- 标题 ## 诊断报告
- 告警概述（表格）
- 原因分析（列表）
- 处置建议（列表）
- 结论
```

### 4.3 RAG 检索流程

```
文档上传流程（文件 → 向量）:
  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
  │  上传文件  │───▶│ 文档切片  │───▶│ 向量化   │───▶│ 存入 Milvus│
  │ .md/.txt  │    │ max=800  │    │ 1024维   │    │ collection│
  │           │    │ overlap  │    │ text-    │    │ "biz"     │
  │           │    │ =100     │    │ embedding│    │           │
  │           │    │          │    │ -v4      │    │           │
  └──────────┘    └──────────┘    └──────────┘    └──────────┘

问答检索流程（问题 → 答案）:
  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
  │  用户问题  │───▶│ 向量检索  │───▶│ 重排序   │───▶│  LLM 生成 │
  │           │    │ dense:   │    │ GTE-     │    │ qwen3-max│
  │           │    │ COSINE   │    │ Rerank   │    │ + 上下文  │
  │           │    │ sparse:  │    │ topK=3   │    │          │
  │           │    │ IP       │    │          │    │          │
  │           │    │ RRF 融合 │    │          │    │          │
  └──────────┘    └──────────┘    └──────────┘    └──────────┘
```

### 4.4 会话生命周期

```
创建会话
  │
  ├── Client 发送请求（含 sessionId）
  ├── SessionManager.getOrCreateSession(id)
  │    ├── 检查 L1 ConcurrentHashMap
  │    ├── 未命中 → 检查 L2 Redis
  │    └── 均未命中 → 新建 SessionInfo
  │
对话交互
  │
  ├── 每轮对话保存到内存 messageHistory
  ├── 持久化到 Redis（新增后即时保存）
  ├── TTL 刷新（每次访问续期 1 小时）
  │
淘汰策略
  │
  ├── 超过 6 对（12 条）消息时
  ├── 淘汰最旧的一对消息
  └── 异步触发记忆提炼 → 存入 Milvus

清空会话
  │
  ├── session.clearHistory(manager)
  ├── 清空内存列表
  └── 保存空列表到 Redis
```

### 4.5 Webhook 告警处理流程

```
Prometheus Alertmanager
  │
  ├── 配置 webhook 地址指向 /api/webhook/alert
  │
  ▼
WebhookController.receiveAlert()
  │
  ├── 接收 Alertmanager JSON Payload
  │    ├── receiver, status, alerts[]
  │    ├── groupLabels, commonLabels
  │    └── 每个 alert 含 status, labels, annotations, startsAt
  │
  ├── 异步触发 AiOpsService.executeAiOpsAnalysis()
  │    ├── 构建 Planner → Executor → Supervisor 流程
  │    └── 生成诊断报告
  │
  └── 日志记录最终报告
```

---

## 5. 详细设计

### 5.1 Milvus 向量数据库设计

**集合信息：**

| 项目 | 值 |
|------|------|
| 集合名称 | `biz` |
| 数据库 | `default` |
| 分片数 | 2 |
| 索引类型 | HNSW |
| 距离度量 | COSINE |
| 向量维度 | 1024 |

**Schema 定义：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VarChar (max 256) | 主键，唯一标识 |
| `vector` | FloatVector (1024) | 文本向量 |
| `content` | VarChar (max 8192) | 文本内容 |
| `metadata` | JSON | 元数据（来源、文件名、章节索引等） |

**检索策略：**

采用混合检索（Hybrid Search），结合稠密向量检索和稀疏向量检索，通过 RRF（Reciprocal Rank Fusion）融合排序：

```
Score = 1 / (k + rank_dense) + 1 / (k + rank_sparse)
```

其中 k 为 RRF 常数（默认 60）。

检索流程：
1. **候选召回**：hybridSearch 获取 candidateK（默认 10）条候选文档
2. **重排序**：DashScope GTE-Rerank API 重新计算相关性
3. **最终结果**：取 topK（默认 3）条最相关文档作为 LLM 上下文

### 5.2 Redis 会话设计

| 项目 | 值 |
|------|------|
| Key 格式 | `session:{sessionId}` |
| Value 格式 | JSON，含 sessionId, createTime, messageHistory[] |
| TTL | 3600 秒（1 小时），每次访问刷新 |
| 序列化 | StringRedisSerializer（全部字段） |

**两级缓存策略：**

```
请求 → L1 (ConcurrentHashMap)
  ├── 命中 → 返回，异步续期 Redis TTL
  └── 未命中 → L2 (Redis)
       ├── 命中 → 加载到 L1，返回
       └── 未命中 → 创建新会话，保存到 L1 + L2
```

Redis 连接失败时自动降级为仅内存模式（`@Autowired(required=false)`）。

### 5.3 文档切片策略

| 参数 | 默认值 | 说明 |
|------|--------|------|
| maxSize | 800 | 每个切片最大字符数 |
| overlap | 100 | 切片间重叠字符数 |

切片算法：
1. 按 Markdown 标题（`#` / `##` / `###`）分割为章节
2. 章节内按段落（双换行）继续分割
3. 对超过 maxSize 的段落，在标点边界截断
4. 重叠部分在句子边界断开（优先中文句号、问号、感叹号）

### 5.4 限流设计

基于 Guava RateLimiter 的 Token Bucket 算法：

| 维度 | 速率 | 范围 |
|------|------|------|
| 单 IP | 5 req/s | 所有 /api/** 请求 |
| 全局重流量接口 | 2 req/s | /api/chat_stream, /api/ai_ops |

- IP 提取顺序：`X-Forwarded-For` → `X-Real-IP` → `getRemoteAddr()`
- 过期 IP 映射每 5 分钟清理一次（1 分钟无活动视为过期）
- 触发限流时返回 HTTP 429 + `ApiResponse`

### 5.5 线程池配置

| 参数 | 值 |
|------|------|
| Core Pool Size | 10 |
| Max Pool Size | 50 |
| Queue Capacity | 100 |
| Rejection Policy | CallerRunsPolicy |
| Bean 名称 | `chatTaskExecutor` |

### 5.6 HTTP 客户端配置（OkHttp）

| 参数 | 值 |
|------|------|
| Connect Timeout | 15 秒 |
| Read Timeout | 30 秒 |
| Write Timeout | 30 秒 |
| Connection Pool | 50 连接，空闲 5 分钟回收 |

### 5.7 模型参数

#### 聊天模型（DashScopeChatModel - primary）

| 参数 | 值 |
|------|------|
| 模型 | qwen3-max |
| Temperature | 0.7 |
| Max Tokens | 2000 |
| Top P | 0.9 |

#### AIOps 模型（DashScopeChatModel - aiOps）

| 参数 | 值 |
|------|------|
| 模型 | qwen3-max |
| Temperature | 0.3 |
| Max Tokens | 8000 |
| Top P | 0.9 |

### 5.8 会话消息淘汰机制

```
MAX_WINDOW_SIZE = 6  (消息对)

对话示例：
 消息1: user "你好"                ← 最旧
 消息2: assistant "你好！"
 消息3: user "今天天气如何？"
 消息4: assistant "今天晴天"
 消息5: user "帮我查个文档"
 消息6: assistant "查到结果..."
 消息7: user "再来一个问题"          ← 最新
 消息8: assistant "回答..."
 消息9: user "第7个问题"            ← 新插入
 消息10: assistant "回答..."
 消息11: user "第8个问题"
 消息12: assistant "回答..."

 插入第13-14条时 → 淘汰消息1-2
 被淘汰的消息 → 异步记忆提炼
```

---

## 6. 配置参数说明

### 6.1 完整配置项

```yaml
# ====== 服务器 ======
server:
  port: 9900                                # 服务端口
  servlet:
    encoding:
      charset: UTF-8                        # 请求编码
      enabled: true
      force: true

# ====== 文件上传 ======
file:
  upload:
    path: ./uploads                          # 文件存储路径
    allowed-extensions: txt,md               # 允许的文件类型

# ====== Milvus 向量数据库 ======
milvus:
  host: localhost                            # Milvus 主机
  port: 19530                                # Milvus 端口
  username: ""                               # 用户名（默认空）
  password: ""                               # 密码（默认空）
  database: default                          # 数据库名
  timeout: 10000                             # 连接超时(ms)

# ====== Redis ======
spring:
  redis:
    host: ${REDIS_HOST:localhost}            # Redis 主机（默认 localhost）
    port: ${REDIS_PORT:6379}                 # Redis 端口（默认 6379）
    timeout: 3000                            # 连接超时(ms)
    lettuce:
      pool:
        max-active: 8                        # 最大活跃连接数
        max-idle: 8                          # 最大空闲连接数
        min-idle: 0                          # 最小空闲连接数

# ====== DashScope AI 服务 ======
spring:
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY}          # API Key（必需）
      chat:
        options:
          timeout: 180000                    # API 超时(ms)
      retry:
        max-attempts: 3                      # 最大重试次数
        backoff:
          initial-interval: 2000             # 初始重试间隔(ms)
          multiplier: 2                      # 间隔倍数
          max-interval: 10000                # 最大重试间隔(ms)

# ====== DashScope 原生配置 ======
dashscope:
  api:
    key: ${DASHSCOPE_API_KEY}                # API Key
  base-url: https://dashscope.aliyuncs.com/api/v1
  rerank:
    url: https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
    model: gte-rerank                        # 重排序模型
  embedding:
    model: text-embedding-v4                 # 向量化模型

# ====== 文档切片 ======
document:
  chunk:
    max-size: 800                            # 每片最大字符
    overlap: 100                             # 重叠字符数

# ====== RAG 检索 ======
rag:
  candidate-k: 10                            # 候选文档数（粗排）
  top-k: 3                                   # 最终返回文档数（精排）
  model: qwen3-max                           # 问答模型

# ====== Prometheus 告警 ======
prometheus:
  base-url: http://localhost:9090            # Prometheus 地址
  timeout: 10                                # 查询超时(秒)
  mock-enabled: false                        # Mock 模式（调试用）

# ====== CLS 日志服务 ======
cls:
  mock-enabled: false                        # Mock 模式（调试用）

# ====== MCP 客户端 ======
spring:
  ai:
    mcp:
      client:
        enabled: false                       # MCP 工具开关
```

### 6.2 环境变量

| 变量名 | 必需 | 默认值 | 说明 |
|--------|------|--------|------|
| `DASHSCOPE_API_KEY` | 是 | — | 阿里云百炼 API Key |
| `REDIS_HOST` | 否 | `localhost` | Redis 主机地址 |
| `REDIS_PORT` | 否 | `6379` | Redis 端口 |

---

## 7. API 接口列表

### 7.1 智能问答

#### POST /api/chat

标准聊天，非流式返回。

**Request Body：**
```json
{
    "Id": "session-xxx",     // 会话 ID，为空自动生成
    "Question": "你好"       // 用户问题
}
```

**Response：**
```json
{
    "code": 200,
    "message": "success",
    "data": {
        "success": true,
        "answer": "你好！有什么可以帮助你的吗？",
        "errorMessage": null
    }
}
```

#### POST /api/chat_stream

流式聊天，SSE 格式输出。

**Event 类型：**
```
event: content_chunk     → AI 回复文本片段
event: tool_use          → 工具调用通知
event: done              → 回答结束
event: error             → 错误信息
```

**Request Body：**
```json
{
    "Id": "session-xxx",
    "Question": "什么是向量数据库？"
}
```

### 7.2 AIOps

#### POST /api/ai_ops

触发 AIOps 诊断流程，SSE 流式输出报告。

**请求：** 无需 Body

**响应：** SSE 流，逐行推送诊断报告 Markdown 文本

### 7.3 Webhook

#### POST /api/webhook/alert

接收 Prometheus Alertmanager 告警推送。

**Request Body：** Alertmanager JSON 格式

```json
{
    "receiver": "webhook",
    "status": "firing",
    "alerts": [
        {
            "status": "firing",
            "labels": { "alertname": "HighCPUUsage" },
            "annotations": { "summary": "CPU usage > 90%" },
            "startsAt": "2024-01-01T00:00:00Z"
        }
    ],
    "groupLabels": {},
    "commonLabels": {},
    "commonAnnotations": {}
}
```

### 7.4 会话管理

#### POST /api/chat/clear

清空会话历史。

**Request Body：**
```json
{ "sessionId": "session-xxx" }
```

#### GET /api/chat/session/{sessionId}

获取会话信息。

**Response：**
```json
{
    "code": 200,
    "message": "success",
    "data": {
        "sessionId": "session-xxx",
        "messagePairCount": 3,
        "createTime": 1700000000000
    }
}
```

### 7.5 文件管理

#### POST /api/upload

上传文档并向量化。

- Content-Type: `multipart/form-data`
- 文件字段名: `file`
- 支持格式: `.txt`, `.md`

#### GET /milvus/health

Milvus 健康检查。

**Response：**
```json
{
    "collections": ["biz"],
    "message": "ok"
}
```

### 7.6 错误码

| HTTP 状态码 | code | 说明 |
|------------|------|------|
| 200 | 200 | 成功 |
| 400 | 400 | 参数错误 |
| 429 | 429 | 请求频率过高 |
| 413 | 413 | 文件过大 |
| 500 | 500 | 服务器内部错误 |

---

## 8. 项目结构

```
super-biz-agent/
│
├── pom.xml                                    # Maven 构建文件
├── Dockerfile                                 # Docker 构建文件
├── docker-compose.yml                         # Docker Compose（含应用）
├── vector-database.yml                        # Docker Compose（仅 Milvus）
├── Makefile                                   # 自动化命令
├── checkstyle.xml                             # Checkstyle 规则
├── pmd-ruleset.xml                            # PMD 规则
├── spotbugs-exclude.xml                       # SpotBugs 排除规则
│
├── src/
│   ├── main/
│   │   ├── java/org/example/
│   │   │   ├── Main.java                      # 应用入口
│   │   │   │
│   │   │   ├── config/                        # 配置类
│   │   │   │   ├── DashScopeConfig.java       # DashScope HTTP 客户端
│   │   │   │   ├── DashScopeModelConfig.java  # ChatModel Bean 定义
│   │   │   │   ├── DocumentChunkConfig.java   # 文档切片参数
│   │   │   │   ├── FileUploadConfig.java      # 文件上传参数
│   │   │   │   ├── HttpClientConfig.java      # OkHttp 客户端
│   │   │   │   ├── MilvusConfig.java          # Milvus 客户端生命周期
│   │   │   │   ├── MilvusProperties.java      # Milvus 连接参数
│   │   │   │   ├── RateLimitInterceptor.java  # 限流拦截器
│   │   │   │   ├── RedisConfig.java           # Redis 模板配置
│   │   │   │   ├── ThreadPoolConfig.java      # 线程池
│   │   │   │   └── WebConfig.java             # Web MVC 配置
│   │   │   │
│   │   │   ├── controller/                    # 控制器
│   │   │   │   ├── ChatController.java        # 聊天/AIOps API
│   │   │   │   ├── WebhookController.java     # Alertmanager Webhook
│   │   │   │   ├── FileUploadController.java  # 文件上传
│   │   │   │   ├── MilvusCheckController.java # 健康检查
│   │   │   │   └── GlobalExceptionHandler.java# 异常处理
│   │   │   │
│   │   │   ├── service/                       # 业务服务
│   │   │   │   ├── ChatService.java           # 聊天逻辑
│   │   │   │   ├── AiOpsService.java          # AIOps 编排
│   │   │   │   ├── RagService.java            # RAG 流式查询
│   │   │   │   ├── SessionManager.java        # 会话管理
│   │   │   │   ├── MemoryExtractionService.java   # 记忆提炼
│   │   │   │   ├── VectorEmbeddingService.java    # 向量化
│   │   │   │   ├── VectorSearchService.java       # 向量检索
│   │   │   │   ├── VectorRerankService.java       # 重排序
│   │   │   │   ├── VectorIndexService.java        # 文档索引
│   │   │   │   └── DocumentChunkService.java      # 文档切片
│   │   │   │
│   │   │   ├── agent/tool/                    # Agent 工具
│   │   │   │   ├── DateTimeTools.java         # 日期时间
│   │   │   │   ├── InternalDocsTools.java     # 知识库检索
│   │   │   │   ├── QueryMetricsTools.java     # 告警查询
│   │   │   │   └── QueryLogsTools.java        # 日志查询
│   │   │   │
│   │   │   ├── client/
│   │   │   │   └── MilvusClientFactory.java   # Milvus 客户端工厂
│   │   │   │
│   │   │   ├── dto/                           # 数据传输对象
│   │   │   │   ├── ApiResponse.java           # 统一响应
│   │   │   │   ├── AlertPayload.java          # 告警负载
│   │   │   │   ├── AIOpsRequest.java          # AIOps 请求
│   │   │   │   ├── DocumentChunk.java         # 文档切片
│   │   │   │   └── FileUploadRes.java         # 上传响应
│   │   │   │
│   │   │   ├── constant/
│   │   │   │   └── MilvusConstants.java       # Milvus 常量
│   │   │   │
│   │   │   └── util/
│   │   │       └── ToolUtils.java             # 工具注册工具
│   │   │
│   │   └── resources/
│   │       ├── application.yml                # 应用配置
│   │       ├── prompts/
│   │       │   └── chat-system-prompt.txt     # Agent 系统提示词
│   │       └── static/                        # 前端静态资源
│   │           ├── index.html                 # 主页面
│   │           ├── styles.css                 # 样式
│   │           └── app.js                     # 应用逻辑
│   │
│   └── test/java/org/example/
│       ├── service/
│       │   ├── SessionManagerTest.java        # 会话管理测试
│       │   ├── VectorSearchServiceTest.java   # 向量检索测试
│       │   ├── RagServiceTest.java            # RAG 测试
│       │   └── AiOpsServiceTest.java          # AIOps 测试
│       └── controller/
│           └── ChatControllerTest.java        # 控制器测试
│
├── aiops-docs/                                # 运维文档库
│   ├── cpu_high_usage.md
│   ├── disk_high_usage.md
│   ├── memory_high_usage.md
│   ├── service_unavailable.md
│   └── slow_response.md
│
├── docs/                                      # 文档
│   └── technical-guide.md                     # 本技术文档
│
├── volumes/                                   # Docker 数据卷
├── target/                                    # Maven 编译输出
└── uploads/                                   # 上传文件存储
```
