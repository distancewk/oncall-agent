# 简历三项指标的代码测试方法

本文档说明如何把简历里的三个数字用**可复现的代码评测**坐实，而不是停留在估算。
三条指标分别对应：历史案例召回 Top-3 80%+ / 诊断端到端耗时 30–45s / 工具调用成功率 95%+。

> 核心思路：评测器（算分）与检索器（产出结果）分离。本项目已有真实检索路径
> `IncidentCaseService.findSimilarCases` → `VectorSearchService.searchIncidentCases(query, topK)`，
> 返回 `List<SearchResult>`，其中 `SearchResult.id` 即案例 ID；工具调用由
> `DiagnosisEvidenceRecorder.recordToolCall` 逐次记录 `success` / `durationMs`。
> 测试代码直接复用这些真实数据源，避免算出"另一套"数字。

---

## 指标 2（最优先落地）：历史案例 Top-3 召回 80%+

### 已交付（零依赖、可立即运行）
- `eval/case_recall_eval.py`：纯 Python 标准库实现的评测器，算 Recall@K / Precision@K / MRR@K。
- `eval/case_recall_dataset.example.json`：标注集（qrels）格式示例。
- `eval/run.example.json`：检索结果（run）格式示例。

### 运行
```bash
# 1) 验证评测算法本身正确（无需任何数据/环境）
python eval/case_recall_eval.py --self-test
# -> SELF-TEST PASSED: recall@3=0.6667 mrr=0.5000 precision@3=0.2500

# 2) 用真实标注集 + 真实检索导出结果算分
python eval/case_recall_eval.py --qrels qrels.json --run run.json --k 3 --target 0.8
```

### 如何产出真实的 run.json（复用项目检索代码）
新建一个 Spring 集成测试，从标注集读取 incident，调用**完全相同的**检索路径，导出 run 文件：

```java
// src/test/java/org/example/eval/CaseRecallEvalTest.java
@SpringBootTest
@Disabled("需要 Milvus + DashScope embedding 环境；有环境时移除 @Disabled")
class CaseRecallEvalTest {
    @Autowired IncidentCaseService incidentCaseService;
    @Autowired IncidentService incidentService;

    @Test
    void exportRunAndEvaluate() throws Exception {
        // 标注集：incidentId -> 期望相关的历史 caseId 列表（来自脱敏事故集，人工标注）
        Map<String, List<String>> dataset = loadDataset("eval/case_recall_dataset.json");
        Map<String, List<String>> run = new LinkedHashMap<>();
        for (String incidentId : dataset.keySet()) {
            IncidentRecord inc = incidentService.getIncident(incidentId);
            List<SearchResult> top = incidentCaseService.findSimilarCases(inc, 3); // 真实路径
            run.put(incidentId, top.stream().map(SearchResult::getId).toList());
        }
        writeJson("eval/run.json", run);
        // 之后交给 case_recall_eval.py 算分，或在此直接复用同样的 Recall@K 逻辑
    }
}
```

### 标注集怎么来（面试被追问"样本多大"时的答案）
案例本身带 `root_cause` / `alertname` / `service` 元数据，可据此构建 ground-truth：
- 同源（同 `alertname` + 同 `service`）的历史 CONFIRMED 诊断互为相关；
- 或按根因聚类，同根因的案例互为相关。
建议标注 **≥30 条**脱敏 incident 再下结论，并在报告中写明样本量。

---

## 指标 1：诊断端到端耗时 30–45s

依赖 LLM 真实推理，需 DashScope API key + 完整基础设施（Milvus/Redis/Postgres）。
设计成基准测试，统计 P50 / P95，避免只报"平均"。

```java
@SpringBootTest
@Disabled("需要 DashScope API key + 完整基础设施")
class DiagnosisLatencyBenchmark {
    @Autowired AlertService alertService;
    // 提交 N 条代表性告警，记录：告警接入时刻 -> 报告完成时刻 的端到端耗时
    // 统计 P50 / P95，并打印分布；断言落在 30-45s 量级区间
    // 注意：本环境 Docker 不可用，Testcontainers 测试会被跳过，需本地起依赖后运行
}
```

报告措辞建议（拿到真实数字后）："典型告警端到端耗时 P50=Xs、P95=Ys（N=xx 条样本），
主要由异步调度 + 并发预取证据达成。" —— 比"30–45s"更经得起追问。

---

## 指标 3：工具调用成功率 95%+

数据源已存在：`DiagnosisEvidenceRecorder.recordToolCall` 每次调用都记录 `success` 与 `durationMs`，
存于 `diagnosis_evidence` 表，可由 `DiagnosisEvidenceRepository.findByRunId(runId)` 聚合。

### (a) 真实成功率聚合（需运行期数据）
```java
List<DiagnosisEvidence> ev = evidenceRepository.findByRunId(runId);
long ok = ev.stream().filter(DiagnosisEvidence::isSuccess).count();
double rate = (double) ok / ev.size();
// 跨多次 run 聚合，得出核心工具整体成功率
```

### (b) 机制测试（证明"为什么能到 95%"）
用 `DependencyGuard`（resilience4j 熔断/重试）注入工具故障，验证在短暂抖动下系统能恢复、
不把瞬时失败计为终态失败：
```java
// 模拟 Prometheus/CLS 工具前几次超时，验证熔断打开 -> 重试/降级，最终恢复
// 断言：可重试工具在故障恢复后调用成功；不可重试工具被预算/熔断拦截且记入 evidence（不臆造）
```

报告措辞建议（拿到真实统计后）："通过对各工具 evidence 的成功/失败聚合，核心工具调用成功率
稳定在 95%+（N=xx 次调用），由调用去重、预算上限与熔断重试保障。"

---

## 落地优先级建议
1. **先做指标 2**：纯检索、零 LLM 成本、最容易产出真实数字，且直接对应简历"Top-3 命中 80%+"。
2. **再做指标 3 的 (a) 聚合**：只要跑过真实诊断，就能从 evidence 表直接算出成功率。
3. **最后做指标 1**：依赖 LLM 调用，有成本，建议样本量 ≥20 条再下结论。

> 当前仓库此前缺失 `eval/` 评测脚本（上一轮已指出）。本目录补全后，简历三项数字即从
> "经验值"升级为"可被 CI / 本地复现的评测结果"，面试追问时可直接展示 run 文件与报告。
