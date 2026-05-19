# 熔断机制设计

## 目标

为 SuperBizAgent 增加依赖级熔断能力，避免外部系统抖动导致应用启动失败、诊断任务卡死或报告误判。熔断后系统必须保持事实边界：只能说明证据缺失和置信度限制，禁止生成虚构的指标、日志、数据库结果、知识库结果或外部搜索结论。

## 范围

第一版只做单体应用内的本地熔断，不引入分布式熔断中心。

纳入熔断的依赖：

- DashScope Chat：聊天与 AIOps 诊断大模型调用。
- DashScope Embedding：文档索引、记忆写入、相似故障召回。
- Prometheus：活动告警、指标趋势查询。
- CLS 日志工具：日志查询。
- Milvus：RAG 检索、记忆检索、相似历史故障检索、向量写入。
- Tavily MCP：公开互联网检索。
- DBHub MCP：数据库 schema 和只读 SQL 查询。

不纳入第一版：

- 分布式状态同步。
- 自动扩缩容联动。
- 复杂 UI 图表，只提供依赖状态列表和诊断详情提示。

## 核心原则

1. Incident 创建不能被外部依赖熔断阻塞。
2. DiagnosisRun 可以失败或降级，但不能静默成功。
3. 工具熔断必须写入 evidence，报告必须引用或说明该 evidence。
4. 熔断后禁止补全不存在的数据。
5. 如果关键证据缺失，最终报告必须降低置信度并列出缺失项。
6. MCP server 不应默认拖垮应用启动，单个 MCP 失败只影响对应工具能力。

## 熔断状态

每个依赖有独立 CircuitBreaker：

```text
CLOSED 正常调用
  -> 连续失败、失败率过高或慢调用过多
OPEN 直接拒绝调用
  -> 冷却时间结束
HALF_OPEN 放少量探测调用
  -> 成功回 CLOSED
  -> 失败回 OPEN
```

推荐实例：

- `dashscope-chat`
- `dashscope-embedding`
- `prometheus`
- `cls-logs`
- `milvus`
- `mcp-tavily`
- `mcp-dbhub`

## 配置

新增配置：

```yaml
app:
  resilience:
    enabled: true
    default:
      failure-rate-threshold: 50
      slow-call-rate-threshold: 50
      slow-call-duration: 5s
      minimum-calls: 5
      permitted-half-open-calls: 2
      open-duration: 30s
    instances:
      prometheus:
        minimum-calls: 3
        open-duration: 20s
      cls-logs:
        minimum-calls: 3
        open-duration: 30s
      dashscope-chat:
        minimum-calls: 5
        slow-call-duration: 30s
        open-duration: 60s
      dashscope-embedding:
        minimum-calls: 5
        open-duration: 60s
      milvus:
        minimum-calls: 3
        open-duration: 30s
      mcp-tavily:
        minimum-calls: 2
        open-duration: 60s
      mcp-dbhub:
        minimum-calls: 2
        open-duration: 60s
```

## 组件设计

### DependencyGuard

统一封装依赖调用：

- 接收依赖名、操作名、调用逻辑、降级逻辑。
- 记录熔断、超时、失败、慢调用。
- 对外暴露当前依赖状态快照。

建议接口：

```java
<T> T execute(String dependency, String operation, Supplier<T> call, Function<Throwable, T> fallback);
DependencyHealthSnapshot snapshot();
```

### Evidence 降级记录

工具调用遇到熔断时，写入 `DiagnosisEvidence`：

```json
{
  "type": "tool_call",
  "toolName": "queryMetricTrend",
  "success": false,
  "errorCode": "CIRCUIT_OPEN",
  "summary": "Prometheus 熔断，未执行指标趋势查询",
  "rawFragment": null
}
```

熔断 evidence 只描述失败事实，不包含推测数据。

### 报告防虚构约束

`DiagnosisReportService` 需要增强报告校验：

- 报告引用了 CPU、内存、错误率、P99、重启趋势结论时，必须存在成功的 `queryMetricTrend` evidence。
- 如果只有失败 evidence，只允许写“趋势证据缺失”，不能写“CPU 持续升高”等结论。
- 报告引用日志结论时，必须存在成功的日志 evidence。
- 报告引用数据库结论时，必须存在成功的 DBHub evidence。
- 校验不通过时，不能保留未被证据支撑的事实结论；系统应将报告改写为“证据不足”版本，或阻止该 DiagnosisRun 标记为可信完成。

## 诊断流程

资源类告警诊断流程：

1. Incident 创建或归并。
2. DiagnosisRun 进入 `RUNNING`。
3. 相似历史故障召回。
4. 指标趋势查询。
5. 日志查询。
6. Runbook / 知识库查询。
7. 条件性调用 Tavily 或 DBHub。
8. 生成报告。
9. 报告守卫检查证据引用。

如果第 4 步 Prometheus 已熔断：

- 跳过真实 Prometheus 请求。
- 写入失败 evidence。
- 继续执行日志、知识库、相似案例查询。
- 最终报告只能说明“指标趋势证据缺失”，不能编造趋势。

## API

新增只读接口：

```text
GET /api/system/dependencies
```

返回示例：

```json
[
  {
    "name": "prometheus",
    "state": "OPEN",
    "failureRate": 66.7,
    "slowCallRate": 20.0,
    "bufferedCalls": 6,
    "lastError": "Connection refused",
    "openedAt": "2026-05-19T15:45:30+08:00"
  }
]
```

## 前端展示

第一版只做轻量展示：

- 系统状态区域显示依赖状态。
- 事故详情中如果存在 `CIRCUIT_OPEN` evidence，显示“某依赖熔断，相关证据缺失”。
- 不做复杂趋势图。

## 测试

单元测试：

- Prometheus 熔断后 `queryMetricTrend` 不发起真实请求。
- 熔断后写入失败 evidence。
- 失败 evidence 不允许支撑趋势结论。
- Milvus 熔断时 RAG 返回空结果而不是抛出到前端。
- DashScope Embedding 熔断时索引任务标记失败并记录原因。

集成测试：

- 模拟 Prometheus 连续失败，CircuitBreaker 进入 `OPEN`。
- 诊断流程在 Prometheus 熔断时仍完成，但报告包含“指标证据缺失”和低置信度。
- `GET /api/system/dependencies` 返回状态。

## 验收标准

- 外部依赖不可用时，应用仍能启动。
- 单个依赖熔断不影响 Incident 创建。
- 诊断报告不包含未被成功 evidence 支撑的事实结论。
- 熔断状态可通过 API 和前端查看。
- 全量测试通过。
