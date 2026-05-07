# SuperBizAgent (OnCall Agent)

> An intelligent Q&A and AIOps system built with Spring Boot + AI Agent framework.

## Overview

SuperBizAgent is an enterprise-grade intelligent agent system with two core modules:

### 1. RAG-based Q&A
Integrates Milvus vector database and Alibaba Cloud DashScope to deliver retrieval-augmented generation (RAG) capabilities, supporting multi-turn conversations and streaming output.

### 2. AIOps (AI for IT Operations)
An AI Agent-driven automated operations system built on a Planner-Executor-Replanner architecture, enabling alert analysis, log querying, intelligent diagnosis, and report generation.

## Features

- **RAG Q&A**: Vector retrieval + multi-turn conversation + streaming output
- **AIOps**: Intelligent diagnosis + multi-agent collaboration + auto-generated reports
- **Tool Integration**: Document retrieval, alert querying, log analysis, date/time utilities
- **Session Management**: Context maintenance, history management, auto-cleanup
- **Web Interface**: Built-in test UI and RESTful API

## Tech Stack

| Technology | Version | Description |
|------------|---------|-------------|
| Java | 17 | Language |
| Spring Boot | 3.2.0 | Application framework |
| Spring AI | 1.1.0 | AI Agent framework |
| DashScope | 2.17.0 | Alibaba Cloud AI service |
| Milvus | 2.6.10 | Vector database |

## Architecture

```
super-biz-agent/
├── src/main/java/org/example/
│   ├── controller/
│   │   └── ChatController.java        # Unified API controller
│   ├── service/
│   │   ├── ChatService.java           # Chat service
│   │   ├── AiOpsService.java          # AIOps service
│   │   ├── RagService.java            # RAG service
│   │   └── Vector*.java               # Vector service classes
│   ├── agent/tool/                    # Agent tool set
│   │   ├── DateTimeTools.java         # Date/time utilities
│   │   ├── InternalDocsTools.java     # Document retrieval
│   │   ├── QueryMetricsTools.java     # Alert querying
│   │   └── QueryLogsTools.java        # Log querying
│   └── config/                        # Config classes
├── src/main/resources/
│   ├── static/                        # Web frontend
│   └── application.yml                # App configuration
├── aiops-docs/                        # Operations document library
├── docs/                              # Additional docs
└── vector-database.yml                # Docker Compose for Milvus
```

## Quick Start

### Prerequisites

- **Java 17** (required, Java 8 is not supported)
- **Maven** 3.6+
- **Docker** & **Docker Compose** (for Milvus vector database)
- **DashScope API Key** from [Alibaba Cloud](https://help.aliyun.com/document_detail/2712195.html)

### 1. Set Environment Variables

```bash
# Required: DashScope API Key for LLM and embedding services
export DASHSCOPE_API_KEY=your-api-key-here
```

### 2. Start Milvus Vector Database

```bash
docker compose -f vector-database.yml up -d
```

This starts Milvus, MinIO, and Attu (W​eb UI at http://localhost:8000).

### 3. Start the Application

```bash
mvn clean install
mvn spring-boot:run
```

> First startup downloads Maven dependencies and may take a few minutes.

### 4. Access

- **Web UI**: http://localhost:9900
- **Attu (Milvus Management)**: http://localhost:8000

### Optional: Upload Documents

Upload operation documents to the vector store for RAG-based Q&A:

```bash
make upload
```

### One-Command Setup

```bash
make init
```

This automates: start Milvus → compile → start service → upload documents.

## API Reference

All API endpoints are accessible without authentication (intended for internal network use).

### 1. Smart Q&A

**Streaming Chat (recommended)**

```bash
curl -N -X POST http://localhost:9900/api/chat_stream \
  -H "Content-Type: application/json" \
  -d '{"Id": "session-123", "Question": "What is a vector database?"}'
```

SSE streaming output, automatic tool invocation, multi-turn conversation.

**Standard Chat**

```bash
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id": "session-123", "Question": "What is a vector database?"}'
```

Returns the complete response in one shot.

### 2. AIOps

```bash
curl -N -X POST http://localhost:9900/api/ai_ops
```

Triggers the alert analysis pipeline and generates an operations report (SSE streaming).

### 3. Session Management

- `POST /api/chat/clear` - Clear session history
- `GET /api/chat/session/{sessionId}` - Get session info

### 4. File Management

- `POST /api/upload` - Upload and vectorize a document
- `GET /milvus/health` - Milvus health check

## Configuration

### application.yml (key sections)

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

rag:
  top-k: 3
  model: "qwen3-max"

document:
  chunk:
    max-size: 800
    overlap: 100
```

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `DASHSCOPE_API_KEY` | Yes | DashScope API key for LLM/embedding |

## Troubleshooting

### Compilation fails with "找不到符号"
Ensure Java 17 is set as the default JDK:
```bash
# Check current version
java -version  # Must be 17.x

# On macOS, set Java 17 explicitly:
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
```

### Docker image pull fails in China
Docker Hub is frequently blocked. Use a mirror registry:
```bash
# Pull images via a reliable mirror
docker pull docker.1ms.run/milvusdb/milvus:v2.5.10
docker pull docker.1ms.run/minio/minio:RELEASE.2023-03-20T20-16-18Z
docker pull docker.1ms.run/zilliz/attu:v2.5
docker pull quay.io/coreos/etcd:v3.5.18

# Tag them with original names
docker tag docker.1ms.run/milvusdb/milvus:v2.5.10 milvusdb/milvus:v2.5.10
docker tag docker.1ms.run/minio/minio:RELEASE.2023-03-20T20-16-18Z minio/minio:RELEASE.2023-03-20T20-16-18Z
docker tag docker.1ms.run/zilliz/attu:v2.5 zilliz/attu:v2.5

# Then start Milvus
docker compose -f vector-database.yml up -d
```

### MCP Client fails on startup (optional)
MCP client connections (tavily, postgres, tencent-cls) are **disabled by default** in `application.yml`. To enable them, set `spring.ai.mcp.client.enabled: true` and configure the necessary API keys and endpoints.

## License

MIT
