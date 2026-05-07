# OnCall Agent

> An intelligent Q&A and AIOps system built with Spring Boot + AI Agent framework.

## Overview

OnCall Agent is an enterprise-grade intelligent agent system with two core modules:

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
oncall-agent/
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

## API Reference

### 1. Smart Q&A

**Streaming Chat (recommended)**
```bash
POST /api/chat_stream
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "What is a vector database?"
}
```
SSE streaming output, automatic tool invocation, multi-turn conversation.

**Standard Chat**
```bash
POST /api/chat
Content-Type: application/json

{
  "Id": "session-123",
  "Question": "What is a vector database?"
}
```
Returns the complete response in one shot, with tool invocation and multi-turn support.

### 2. AIOps

```bash
POST /api/ai_ops
```
Triggers the alert analysis pipeline and generates an operations report (SSE streaming).

### 3. Session Management

- `POST /api/chat/clear` - Clear session history
- `GET /api/chat/session/{sessionId}` - Get session info

### 4. File Management

- `POST /api/upload` - Upload and vectorize a document
- `GET /milvus/health` - Milvus health check

## Configuration

### application.yml

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

```bash
export DASHSCOPE_API_KEY=your-api-key
```

## Quick Start

### 1. Prerequisites

```bash
# Set your API key
export DASHSCOPE_API_KEY=your-api-key
```

### 2. Run the Application

**Option A — Manual**
```bash
# 1. Start the vector database
docker compose up -d -f vector-database.yml

# 2. Start the service
mvn clean install
mvn spring-boot:run
```

**Option B — One-command setup**
```bash
make init  # Starts Milvus and uploads operation docs to the vector store
```

### 3. Usage Examples

**Web UI**
```
http://localhost:9900
```

**CLI**
```bash
# Upload a document
curl -X POST http://localhost:9900/api/upload \
  -F "file=@document.txt"

# Smart Q&A
curl -X POST http://localhost:9900/api/chat \
  -H "Content-Type: application/json" \
  -d '{"Id":"test","Question":"What is a vector database?"}'

# Health check
curl http://localhost:9900/milvus/health
```

## License

MIT
