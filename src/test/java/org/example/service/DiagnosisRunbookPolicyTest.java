package org.example.service;

import org.example.dto.DiagnosisEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisRunbookPolicyTest {

    private final DiagnosisRunbookPolicy policy = new DiagnosisRunbookPolicy();

    @Test
    void select_shouldChooseMemoryRunbookAndRequireTrendBeforeLogs() {
        DiagnosisRunbookPolicy.Runbook runbook = policy.select("OOMKilled: payment-service memory alert");

        assertEquals("memory-pressure-v1", runbook.id());
        assertEquals("queryMetricTrend", runbook.steps().get(0).tool());
        assertEquals("queryLogs", runbook.steps().get(2).tool());
    }

    @Test
    void select_shouldChooseDependencyRunbookForTimeouts() {
        DiagnosisRunbookPolicy.Runbook runbook = policy.select("下游 dependency timeout");

        assertEquals("dependency-timeout-v1", runbook.id());
        assertTrue(policy.promptFor("下游 dependency timeout").contains("application-logs"));
    }

    @Test
    void select_shouldFallbackToGenericRunbook() {
        DiagnosisRunbookPolicy.Runbook runbook = policy.select("未知告警");

        assertEquals("generic-evidence-first-v1", runbook.id());
        assertTrue(runbook.stopCondition().contains("人工复核"));
    }

    @Test
    void select_shouldCoverLatencyErrorRateAndSlowSqlRunbooks() {
        assertEquals("latency-regression-v1", policy.select("P99 latency regression").id());
        assertEquals("error-rate-v1", policy.select("ServiceErrorRate payment-service").id());
        assertEquals("slow-sql-v1", policy.select("数据库慢 SQL").id());
    }

    @Test
    void select_shouldUseNoDataRunbookAndAllowOnlyCatalogMetricForItsFlexibleStep() {
        assertEquals("no-data-v1", policy.select("Prometheus no data alert").id());
        assertEquals(List.of("queryPrometheusAlerts", "queryMetricTrend", "queryLogs"),
                policy.requiredTools("Prometheus no data alert"));
        assertTrue(policy.validateToolCallOrder(
                "Prometheus no data alert",
                List.of(successful("queryPrometheusAlerts")),
                "queryMetricTrend",
                "{\"metric\":\"error_rate\",\"window\":\"15m\"}",
                "15m").isEmpty());
        assertTrue(policy.validateToolCallOrder(
                "Prometheus no data alert",
                List.of(successful("queryPrometheusAlerts")),
                "queryLogs",
                "{\"logTopic\":\"system-metrics\"}",
                "recent logs").orElseThrow().contains("15m"));
    }

    @Test
    void select_shouldUseMultiAlertRunbookAndRequireCompleteCorrelationOrder() {
        String context = "多告警关联: true\n关联告警数量: 2";

        assertEquals("multi-alert-v1", policy.select(context).id());
        assertEquals(List.of("queryPrometheusAlerts", "queryMetricTrend", "queryLogs"),
                policy.requiredTools(context));
        assertTrue(policy.validateToolCallOrder(
                context,
                List.of(successful("queryPrometheusAlerts")),
                "queryMetricTrend",
                "{\"metric\":\"error_rate\",\"window\":\"15m\"}",
                "15m").isEmpty());
        assertTrue(policy.validateToolCallOrder(
                context,
                List.of(successful("queryPrometheusAlerts")),
                "queryLogs",
                "{}",
                "recent").orElseThrow().contains("15m"));
    }

    @Test
    void progress_shouldRequireOrderedSuccessfulSteps() {
        DiagnosisEvidence firstTrend = successful("queryMetricTrend", "memory_usage", "15m");
        DiagnosisEvidence secondTrend = successful("queryMetricTrend",
                "jvm_gc_collection_seconds_count", "1h");
        DiagnosisEvidence logs = successful("queryLogs");

        DiagnosisRunbookPolicy.Progress partial = policy.progressFor(
                "OOMKilled: payment-service memory alert", List.of(firstTrend));
        assertEquals("IN_PROGRESS", partial.status());
        assertEquals(1, partial.completedStep());

        DiagnosisRunbookPolicy.Progress complete = policy.progressFor(
                "OOMKilled: payment-service memory alert",
                List.of(firstTrend, secondTrend, logs));
        assertEquals("COMPLETED", complete.status());
        assertEquals(3, complete.completedStep());
    }

    @Test
    void progress_shouldNotMarkOutOfOrderEvidenceComplete() {
        DiagnosisRunbookPolicy.Progress progress = policy.progressFor(
                "OOMKilled: payment-service memory alert",
                List.of(successful("queryLogs", "", "15m"),
                        successful("queryMetricTrend", "memory_usage", "15m"),
                        successful("queryMetricTrend", "jvm_gc_collection_seconds_count", "1h")));

        assertEquals("NOT_STARTED", progress.status());
        assertEquals(0, progress.completedStep());
    }

    @Test
    void progress_shouldRequireP99TrendBeforeApplicationLogs() {
        DiagnosisEvidence trend = successful("queryMetricTrend", "p99_latency", "15m");
        DiagnosisEvidence baseline = successful("queryMetricTrend", "p99_latency", "1h");
        DiagnosisEvidence logs = successful("queryLogs");

        DiagnosisRunbookPolicy.Progress progress = policy.progressFor(
                "P99 latency regression", List.of(trend, baseline, logs));

        assertEquals("latency-regression-v1", progress.runbookId());
        assertEquals("COMPLETED", progress.status());
        assertEquals(3, progress.completedStep());
    }

    @Test
    void validateToolCallOrder_shouldRejectLogsBeforeTheFirstTrend() {
        assertTrue(policy.validateToolCallOrder(
                "OOMKilled: payment-service memory alert",
                List.of(),
                "queryLogs",
                "{\"logTopic\":\"system-events\"}",
                "recent logs").orElseThrow().contains("memory_usage"));
    }

    @Test
    void validateToolCallOrder_shouldAllowTheNextTrendAndPlanOnlyAvailableMetrics() {
        assertTrue(policy.validateToolCallOrder(
                "HighCPUUsage: payment-service",
                List.of(),
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\",\"window\":\"15m\"}",
                "15m").isEmpty());
        assertEquals(List.of(
                        new DiagnosisRunbookPolicy.MetricQuery("cpu_usage", "15m"),
                        new DiagnosisRunbookPolicy.MetricQuery("cpu_usage", "1h")),
                policy.prefetchQueries("HighCPUUsage: payment-service", Set.of("cpu_usage", "error_rate")));
    }

    private DiagnosisEvidence successful(String toolName) {
        return successful(toolName, "", "15m");
    }

    private DiagnosisEvidence successful(String toolName, String metric, String window) {
        String params = metric.isBlank() ? "{}"
                : "{\"metric\":\"" + metric + "\",\"window\":\"" + window + "\"}";
        return DiagnosisEvidence.toolCall(toolName, params, window, "ok", "ok", true, null,
                System.currentTimeMillis());
    }
}
