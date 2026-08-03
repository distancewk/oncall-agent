package org.example.service;

import org.example.dto.DiagnosisEvidence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Code-owned first-pass runbooks for the incident classes that have stable
 * evidence requirements. The model may explain and refine the result, but it
 * must not omit the deterministic first checks for a recognized alert class.
 */
public final class DiagnosisRunbookPolicy {

    public Runbook select(String alertContext) {
        String normalized = value(alertContext).toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "多告警关联", "multi-alert", "multi alert", "关联告警数量")) {
            return multiAlertRunbook();
        }
        if (containsAny(normalized, "no data", "no-data", "nodata", "无数据", "数据缺失", "数据中断")) {
            return noDataRunbook();
        }
        if (containsAny(normalized, "oom", "outofmemory", "内存", "memory", "oomkilled")) {
            return memoryRunbook();
        }
        if (containsAny(normalized, "slow sql", "slow-sql", "慢sql", "慢 sql", "database-slow-query")) {
            return slowSqlRunbook();
        }
        if (containsAny(normalized, "dependency", "下游", "依赖", "timeout", "超时", "5xx")) {
            return dependencyRunbook();
        }
        if (containsAny(normalized, "restart", "重启", "crashloop", "崩溃", "container_restart")) {
            return restartRunbook();
        }
        if (containsAny(normalized, "cpu", "load", "负载", "处理器")) {
            return cpuRunbook();
        }
        if (containsAny(normalized, "p99", "latency", "slowresponse", "响应时间", "延迟")) {
            return latencyRunbook();
        }
        if (containsAny(normalized, "error rate", "error_rate", "error", "错误率", "失败率", "错误", "errors")) {
            return errorRateRunbook();
        }
        return genericRunbook();
    }

    public String promptFor(String alertContext) {
        Runbook runbook = select(alertContext);
        StringBuilder prompt = new StringBuilder()
                .append("## 代码侧确定性诊断 Runbook\n")
                .append("Runbook: ").append(runbook.id()).append("\n")
                .append("目标: ").append(runbook.goal()).append("\n")
                .append("必须按以下顺序完成或明确记录缺口：\n");
        for (int index = 0; index < runbook.steps().size(); index++) {
            Step step = runbook.steps().get(index);
            prompt.append(index + 1).append(". ")
                    .append(step.objective())
                    .append(" [tool=").append(step.tool())
                    .append(", metric=").append(step.metric())
                    .append(", window=").append(step.window())
                    .append(", topic=").append(step.logTopic()).append("]\n");
        }
        prompt.append("停止条件: ").append(runbook.stopCondition()).append("\n")
                .append("Runbook 只是最低证据要求；任何未被实际成功 evidence 支撑的结论仍必须写成证据不足。\n");
        return prompt.toString();
    }

    public List<String> requiredTools(String alertContext) {
        return switch (select(alertContext).id()) {
            case "multi-alert-v1", "no-data-v1" ->
                    List.of("queryPrometheusAlerts", "queryMetricTrend", "queryLogs");
            case "cpu-saturation-v1", "memory-pressure-v1", "dependency-timeout-v1", "restart-loop-v1",
                    "latency-regression-v1", "error-rate-v1", "slow-sql-v1" ->
                    List.of("queryMetricTrend", "queryLogs");
            default -> List.of();
        };
    }

    /**
     * Returns the deterministic metric queries that can be safely prefetched
     * for a recognized runbook. Each declared step gets its own window while
     * the caller binds the metric to a single target instance.
     */
    public List<MetricQuery> prefetchQueries(String alertContext, Set<String> availableMetrics) {
        if (requiredTools(alertContext).isEmpty() || availableMetrics == null || availableMetrics.isEmpty()) {
            return List.of();
        }
        Set<String> normalizedAvailable = availableMetrics.stream()
                .filter(metric -> metric != null && !metric.isBlank())
                .map(metric -> metric.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<MetricQuery> result = new java.util.ArrayList<>();
        for (Step step : select(alertContext).steps()) {
            if (!QUERY_METRIC_TREND.equals(step.tool()) || step.metric().isBlank()) {
                continue;
            }
            for (String option : step.metric().split("\\|")) {
                String metric = option.trim();
                if (!metric.isBlank()
                        && normalizedAvailable.contains(metric.toLowerCase(Locale.ROOT))) {
                    result.add(new MetricQuery(metric, step.window()));
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns the next required step for a runbook based on persisted
     * successful evidence. Optional documentation steps never block tool
     * execution and are intentionally skipped here.
     */
    public Optional<Step> nextRequiredStep(String alertContext, List<DiagnosisEvidence> evidence) {
        Runbook runbook = select(alertContext);
        if (requiredTools(alertContext).isEmpty()) {
            return Optional.empty();
        }
        List<DiagnosisEvidence> successful = successfulEvidence(evidence);
        int evidenceIndex = 0;
        for (Step step : runbook.steps()) {
            if (isOptional(step)) {
                if (evidenceIndex < successful.size()
                        && evidenceMatches(step, successful.get(evidenceIndex))) {
                    evidenceIndex++;
                }
                continue;
            }
            if (evidenceIndex >= successful.size()
                    || !evidenceMatches(step, successful.get(evidenceIndex))) {
                return Optional.of(step);
            }
            evidenceIndex++;
        }
        return Optional.empty();
    }

    /**
     * Validates a pending tool call against the next required Runbook step.
     * An empty result means the call is allowed or the alert is generic.
     */
    public Optional<String> validateToolCallOrder(String alertContext,
                                                   List<DiagnosisEvidence> evidence,
                                                   String toolName,
                                                   String queryParams,
                                                   String timeRange) {
        Optional<Step> expected = nextRequiredStep(alertContext, evidence);
        if (expected.isEmpty()) {
            return Optional.empty();
        }
        Step step = expected.get();
        if (toolCallMatches(step, toolName, queryParams, timeRange)) {
            return Optional.empty();
        }
        return Optional.of("Runbook " + select(alertContext).id() + " 下一步应先执行："
                + step.objective() + " [tool=" + step.tool()
                + ", metric=" + step.metric() + ", window=" + step.window() + "]");
    }

    /**
     * Calculates persisted progress from successful tool evidence. Failed or
     * skipped calls never advance a runbook step, and steps are consumed in
     * order so an out-of-order model response cannot mark the runbook complete.
     */
    public Progress progressFor(String alertContext, List<DiagnosisEvidence> evidence) {
        Runbook runbook = select(alertContext);
        List<String> requiredTools = requiredTools(alertContext);
        if (requiredTools.isEmpty()) {
            return new Progress(runbook.id(), "NOT_APPLICABLE", 0, List.of(), List.of());
        }

        List<DiagnosisEvidence> successful = successfulEvidence(evidence);
        int completedSteps = 0;
        int evidenceIndex = 0;
        for (Step step : runbook.steps()) {
            if (isOptional(step)) {
                if (evidenceIndex < successful.size()
                        && evidenceMatches(step, successful.get(evidenceIndex))) {
                    evidenceIndex++;
                }
                continue;
            }
            if (evidenceIndex >= successful.size()
                    || !evidenceMatches(step, successful.get(evidenceIndex))) {
                break;
            }
            evidenceIndex++;
            completedSteps++;
        }
        Set<String> completedTools = new LinkedHashSet<>();
        for (DiagnosisEvidence item : successful) {
            if (requiredTools.contains(item.getToolName())) {
                completedTools.add(item.getToolName());
            }
        }
        String status = completedSteps == requiredStepCount(runbook) ? "COMPLETED"
                : completedSteps == 0 ? "NOT_STARTED" : "IN_PROGRESS";
        return new Progress(runbook.id(), status, completedSteps,
                requiredTools, List.copyOf(completedTools));
    }

    private int requiredStepCount(Runbook runbook) {
        return (int) runbook.steps().stream().filter(step -> !isOptional(step)).count();
    }

    private boolean isOptional(Step step) {
        return "queryInternalDocs".equals(step.tool());
    }

    private boolean toolMatches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        for (String option : expected.split("\\|")) {
            if (option.trim().equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private boolean evidenceMatches(Step step, DiagnosisEvidence evidence) {
        return toolCallMatches(step, evidence.getToolName(), evidence.getQueryParams(), evidence.getTimeRange());
    }

    private boolean toolCallMatches(Step step, String toolName, String queryParams, String timeRange) {
        if (!toolMatches(step.tool(), toolName)) {
            return false;
        }
        if (hasSingleMetric(step.metric())
                && !"catalog-dependent".equals(step.metric())
                && !containsIgnoreCase(queryParams, step.metric())) {
            return false;
        }
        if (!step.window().isBlank()
                && !step.window().equalsIgnoreCase(value(timeRange))
                && !containsIgnoreCase(queryParams, "\"window\":\"" + step.window() + "\"")) {
            return false;
        }
        return true;
    }

    private List<DiagnosisEvidence> successfulEvidence(List<DiagnosisEvidence> evidence) {
        return evidence == null ? List.of() : evidence.stream()
                .filter(item -> item != null && item.isSuccess() && item.getToolName() != null)
                .toList();
    }

    private boolean hasSingleMetric(String metric) {
        return metric != null && !metric.isBlank() && !metric.contains("|");
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private Runbook cpuRunbook() {
        return new Runbook("cpu-saturation-v1", "确认 CPU 趋势是否持续并区分资源饱和与偶发尖峰",
                List.of(
                        new Step("先查询 CPU 趋势，再判断是否持续升高", "queryMetricTrend", "cpu_usage", "15m", "system-metrics"),
                        new Step("用较长窗口确认基线和恢复情况", "queryMetricTrend", "cpu_usage", "1h", "system-metrics"),
                        new Step("查询同一服务的应用或系统日志，寻找与尖峰同时发生的错误", "queryLogs", "", "", "system-metrics")
                ), "趋势查询失败或无法区分持续饱和时，不得给出确定根因。");
    }

    private Runbook memoryRunbook() {
        return new Runbook("memory-pressure-v1", "区分内存持续增长、JVM GC 压力和 OOM 终止",
                List.of(
                        new Step("查询内存使用趋势", "queryMetricTrend", "memory_usage", "15m", "system-metrics"),
                        new Step("查询 JVM GC 次数趋势，确认是否存在 GC 压力", "queryMetricTrend", "jvm_gc_collection_seconds_count", "1h", "system-metrics"),
                        new Step("查询 OOM、容器终止或堆错误日志", "queryLogs", "", "", "system-events")
                ), "缺少趋势或 OOM 日志时，只能报告证据不足，不能断言内存耗尽。");
    }

    private Runbook dependencyRunbook() {
        return new Runbook("dependency-timeout-v1", "区分下游延迟、错误率和本服务自身异常",
                List.of(
                        new Step("查询延迟或错误率趋势", "queryMetricTrend", "p99_latency|error_rate", "15m", "application-logs"),
                        new Step("查询应用日志中的超时、连接失败和下游名称", "queryLogs", "", "", "application-logs"),
                        new Step("仅在需要解释错误码或处理流程时查询内部文档", "queryInternalDocs", "", "", "")
                ), "未确认下游目标或没有成功日志时，不得把异常归因于具体依赖。");
    }

    private Runbook restartRunbook() {
        return new Runbook("restart-loop-v1", "确认重启是否真实发生并区分发布、崩溃和资源驱逐",
                List.of(
                        new Step("查询容器重启次数趋势", "queryMetricTrend", "restart_count", "1h", "system-events"),
                        new Step("查询 CrashLoop、OOMKilled、驱逐和发布事件", "queryLogs", "", "", "system-events")
                ), "没有重启趋势和事件日志时，只能报告现象，不能确定重启原因。");
    }

    private Runbook latencyRunbook() {
        return new Runbook("latency-regression-v1", "区分 P99 延迟持续回归、短时尖峰和应用日志异常",
                List.of(
                        new Step("查询 P99 延迟趋势", "queryMetricTrend", "p99_latency", "15m", "application-logs"),
                        new Step("用较长窗口确认延迟基线和恢复情况", "queryMetricTrend", "p99_latency", "1h", "application-logs"),
                        new Step("查询慢请求、超时和下游调用日志", "queryLogs", "", "", "application-logs")
                ), "缺少 P99 趋势或日志时，只能报告延迟现象，不能确定回归根因。");
    }

    private Runbook errorRateRunbook() {
        return new Runbook("error-rate-v1", "确认错误率是否持续并定位应用错误或下游失败",
                List.of(
                        new Step("查询错误率趋势", "queryMetricTrend", "error_rate", "15m", "application-logs"),
                        new Step("查询错误、异常和下游失败日志", "queryLogs", "", "", "application-logs")
                ), "缺少错误率趋势或日志时，不得确定错误率根因。");
    }

    private Runbook slowSqlRunbook() {
        return new Runbook("slow-sql-v1", "确认慢 SQL 现象并从数据库日志定位查询或资源问题",
                List.of(
                        new Step("查询 P99 延迟趋势作为慢 SQL 现象基线", "queryMetricTrend", "p99_latency", "15m", "database-slow-query"),
                        new Step("查询数据库慢查询日志", "queryLogs", "", "", "database-slow-query"),
                        new Step("必要时查询内部文档解释数据库错误码或处理流程", "queryInternalDocs", "", "", "")
                ), "没有 P99 趋势和慢查询日志时，不得把延迟归因于慢 SQL。");
    }

    private Runbook noDataRunbook() {
        return new Runbook("no-data-v1", "区分监控采集缺失、查询范围错误和真实业务无数据",
                List.of(
                        new Step("确认当前活动告警及其服务、实例和时间范围", "queryPrometheusAlerts", "", "", ""),
                        new Step("选择告警目录中的相关指标查询 15m 趋势", "queryMetricTrend", "catalog-dependent", "15m", "system-metrics"),
                        new Step("查询采集失败、目标下线或数据延迟相关系统日志", "queryLogs", "", "", "system-metrics")
                ), "没有活动告警、指标趋势或采集日志时，只能报告数据缺失，不能断言业务指标为零。");
    }

    private Runbook multiAlertRunbook() {
        return new Runbook("multi-alert-v1", "先确认同一批告警的完整范围，再验证共同时间线和共享证据",
                List.of(
                        new Step("确认当前活动告警及每条告警的服务、实例和时间范围", "queryPrometheusAlerts", "", "", ""),
                        new Step("从告警目录选择一个能区分共同时间线的指标查询 15m 趋势",
                                "queryMetricTrend", "catalog-dependent", "15m", "system-metrics"),
                        new Step("查询跨服务、发布、依赖和资源事件的关联日志", "queryLogs", "", "", "system-metrics")
                ), "缺少完整告警列表、共同趋势或关联日志时，只能分别描述现象，不能断言单一共同根因。");
    }

    private Runbook genericRunbook() {
        return new Runbook("generic-evidence-first-v1", "先识别告警类别，再选择最小必要证据",
                List.of(
                        new Step("从告警上下文提取服务、实例、时间范围和症状", "none", "", "", ""),
                        new Step("选择一个与症状直接相关的趋势或日志查询", "queryMetricTrend|queryLogs", "catalog-dependent", "15m", "topic-dependent")
                ), "无法识别可靠证据路径时，必须进入人工复核或证据不足状态。");
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private static final String QUERY_METRIC_TREND = "queryMetricTrend";

    public record Runbook(String id, String goal, List<Step> steps, String stopCondition) {
    }

    public record Step(String objective, String tool, String metric, String window, String logTopic) {
    }

    public record MetricQuery(String metric, String window) {
    }

    public record Progress(String runbookId,
                           String status,
                           int completedStep,
                           List<String> requiredTools,
                           List<String> completedTools) {
    }
}
