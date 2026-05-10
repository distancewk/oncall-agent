# SuperBizAgent (OnCall Agent)

> 基于 Spring Boot + AI Agent 框架的智能问答与 AIOps 系统。
> 支持 RAG 知识库问答、多轮对话、智能运维诊断告警与报告生成。

## 简介

SuperBizAgent 包含两大核心模块：

- **RAG 智能问答**：集成 Milvus 向量数据库 + 阿里云 DashScope 大模型，支持文档检索增强生成、多轮对话、流式输出
- **AIOps 运维助手**：基于 Planner-Executor-Replanner 架构的自动化运维系统，支持告警分析、日志查询、智能诊断、报告生成

## 功能特性

- RAG 知识库问答（向量检索 + 多轮对话 + 流式输出）
- AIOps 智能诊断（多智能体协作 + 自动生成报告）
- 工具集成（文档检索、告警查询、日志分析、日期工具）
- 会话管理（上下文维护 + Redis 持久化，刷新页面/重启不丢失）
- Web 管理界面（内置测试 UI + RESTful API）

## 技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| Java | 17 | 开发语言 |
| Spring Boot | 3.2.0 | 应用框架 |
| Spring AI | 1.1.0 | AI Agent 框架 |
| DashScope | 2.17.0 | 阿里云大模型服务 |
| Milvus | 2.5.10 | 向量数据库 |
| Redis | 7-alpine | 会话持久化缓存 |

## 本地部署

### 环境要求

- **Java 17**（必需，Java 8 不支持）
- **Maven** 3.6+
- **Docker** & **Docker Compose**（用于启动 Milvus 向量数据库和 Redis）
- **DashScope API Key** 从 [阿里云百炼](https://help.aliyun.com/document_detail/2712195.html) 获取

### 1. 下载项目

```bash
# 解压下载的文件后，进入项目目录
cd SuperBizAgent-release-2026-01-02
```

### 2. 设置 API Key

```bash
# 必需：DashScope API Key，用于大模型和向量化服务
export DASHSCOPE_API_KEY=your-api-key-here
```

### 3. 启动基础设施（数据库、缓存等）

```bash
# 一键启动所有依赖服务：Milvus + Redis + MinIO + Attu 管理界面
docker compose up -d
```

> 如果 Docker Hub 拉取镜像失败（国内网络问题），请参考下方的"Docker 镜像拉取失败"章节使用镜像源。

验证服务是否正常：

```bash
docker ps --format "table {{.Names}}\t{{.Status}}"
```

所有容器状态为 `healthy` 即可继续。

### 4. 编译并启动应用

```bash
# 编译项目
mvn clean install -DskipTests

# 启动应用（首次启动会下载依赖，耗时较长）
export DASHSCOPE_API_KEY=your-api-key-here && mvn spring-boot:run
```

> 启动失败的常见原因及解决方法请参考"常见问题"章节。

### 5. 访问服务

- **Web UI**: http://localhost:9900
- **Attu（Milvus 管理界面）**: http://localhost:8000
- **API 健康检查**: `curl http://localhost:9900/milvus/health`

### 6. 上传文档到知识库（可选）

```bash
make upload
```

这会自动将 `aiops-docs/` 目录下的运维文档向量化后存入 Milvus。

### 一键初始化

```bash
make init
```

自动完成：启动 Docker → 编译项目 → 启动服务 → 上传文档。

## 常用命令

### 应用管理

| 命令 | 说明 |
|------|------|
| `mvn spring-boot:run` | 启动应用（前台运行） |
| `make start` | 启动应用（后台运行，日志写入 server.log） |
| `make stop` | 停止应用 |
| `make restart` | 重启应用 |
| `make check` | 检查应用运行状态 |
| `tail -f server.log` | 查看实时日志 |

### 基础设施管理

| 命令 | 说明 |
|------|------|
| `docker compose up -d` | 启动所有依赖服务 |
| `docker compose down` | 停止所有依赖服务 |
| `make status` | 查看 Docker 容器状态 |
| `docker compose logs milvus` | 查看 Milvus 日志 |
| `docker compose logs redis` | 查看 Redis 日志 |

### 文档管理

| 命令 | 说明 |
|------|------|
| `make upload` | 上传 aiops-docs 目录下所有文档到向量库 |
| `make list-docs` | 查看文档列表 |

### 查看 Redis 数据

```bash
# 查看所有 session
docker exec session-redis redis-cli KEYS "session:*"

# 查看指定会话的完整内容
docker exec session-redis redis-cli GET "session:test-session-redis" | python3 -m json.tool

# 查看会话过期时间（秒）
docker exec session-redis redis-cli TTL "session:test-session-redis"

# 进入 Redis 交互式命令行
docker exec -it session-redis redis-cli
```

## API 参考

所有 API 无需鉴权（仅限内网使用）。

### 智能问答

**流式聊天（推荐）**

```bash
curl -N -X POST http://localhost:9900/api/chat_stream \
  -H "Content-Type: application/json" \
  -d '{"Id": "session-123", "Question": "什么是向量数据库？"}'
```

SSE 流式输出，自动调用工具，支持多轮对话。

**标准聊天**

```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id": "session-123", "Question": "什么是向量数据库？"}'
```

一次性返回完整回复。

### AIOps 运维诊断

```bash
curl -N -X POST http://localhost:9900/api/ai_ops
```

触发告警分析流水线，生成运维报告（SSE 流式输出）。

### 会话管理

- `POST /api/chat/clear` - 清空会话历史
- `GET /api/chat/session/{sessionId}` - 获取会话信息

### 文件管理

- `POST /api/upload` - 上传文档并向量化
- `GET /milvus/health` - Milvus 健康检查

## 配置说明

### 主要配置项（application.yml）

```yaml
server:
  port: 9900

milvus:
  host: localhost
  port: 19530

spring:
  ai:
    dashscope:
      api-key: "${DASHSCOPE_API_KEY}"
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}

rag:
  top-k: 3
  model: "qwen3-max"

document:
  chunk:
    max-size: 800
    overlap: 100
```

### 环境变量

| 变量 | 必需 | 说明 |
|------|------|------|
| `DASHSCOPE_API_KEY` | 是 | 阿里云百炼 API Key |

## 项目结构

```
super-biz-agent/
├── src/main/java/org/example/
│   ├── controller/
│   │   └── ChatController.java        # 统一 API 控制器
│   ├── service/
│   │   ├── ChatService.java           # 聊天服务
│   │   ├── AiOpsService.java          # AIOps 服务
│   │   ├── RagService.java            # RAG 服务
│   │   ├── SessionManager.java        # 会话管理（内存 + Redis 持久化）
│   │   └── Vector*.java               # 向量服务
│   ├── agent/tool/                    # 智能体工具集
│   │   ├── DateTimeTools.java         # 日期时间
│   │   ├── InternalDocsTools.java     # 文档检索
│   │   ├── QueryMetricsTools.java     # 告警查询
│   │   └── QueryLogsTools.java        # 日志查询
│   └── config/                        # 配置类
├── src/main/resources/
│   ├── static/                        # 前端页面
│   └── application.yml                # 应用配置
├── aiops-docs/                        # 运维文档库
├── docker-compose.yml                 # Docker Compose（含应用）
└── vector-database.yml                # Docker Compose（仅 Milvus）
```

## 常见问题

### 编译报错"找不到符号"

确保使用 Java 17：

```bash
# 检查当前版本
java -version  # 必须是 17.x

# macOS 上设置 Java 17
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
```

### Docker 镜像拉取失败（国内网络）

Docker Hub 在国内访问不稳定时，可使用镜像源：

```bash
# 从镜像源拉取并重新打标签
docker pull docker.1panel.live/milvusdb/milvus:v2.5.10
docker pull docker.1panel.live/minio/minio:RELEASE.2023-03-20T20-16-18Z
docker pull docker.1panel.live/zilliz/attu:v2.5
docker pull quay.io/coreos/etcd:v3.5.18
docker pull docker.1panel.live/library/redis:7-alpine

# 重新打标签
docker tag docker.1panel.live/milvusdb/milvus:v2.5.10 milvusdb/milvus:v2.5.10
docker tag docker.1panel.live/minio/minio:RELEASE.2023-03-20T20-16-18Z minio/minio:RELEASE.2023-03-20T20-16-18Z
docker tag docker.1panel.live/zilliz/attu:v2.5 zilliz/attu:v2.5
docker tag docker.1panel.live/library/redis:7-alpine redis:7-alpine

# 然后启动服务
docker compose up -d
```

### 应用启动报错"API Key 未正确配置"

- 确认已设置 `DASHSCOPE_API_KEY` 环境变量：`echo $DASHSCOPE_API_KEY`
- 启动时需确保环境变量已导出：`export DASHSCOPE_API_KEY=your-key && mvn spring-boot:run`
- DashScope API Key 以 `sk-` 开头属于正常格式

### MCP 客户端启动报错（可选）

MCP 客户端连接（tavily、postgres、tencent-cls）**默认禁用**。如需启用，在 `application.yml` 中设置 `spring.ai.mcp.client.enabled: true` 并配置相应的 API Key 和端点。

## License

MIT
