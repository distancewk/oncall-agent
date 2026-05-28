package org.example.service;

import org.example.dto.DiagnosisEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisReportGuardTest {

    private final DiagnosisReportService guard = new DiagnosisReportService();

    @Test
    void augmentAlertContext_shouldAppendCompactEvidenceTableAndReportRules() {
        DiagnosisEvidence trend = evidence("ev-cpu-1", "queryMetricTrend", "15m",
                "cpu_usage 最近 15m 持续上升，latest=94.00");
        DiagnosisEvidence context = DiagnosisEvidence.of("alert_context", "上下文", "原始告警", 1L);

        String result = guard.augmentAlertContext("基础告警上下文", List.of(context, trend));

        assertTrue(result.contains("基础告警上下文"));
        assertTrue(result.contains("## 可用工具证据表"));
        assertTrue(result.contains("ev-cpu-1"));
        assertTrue(result.contains("queryMetricTrend"));
        assertTrue(result.contains("## 报告证据约束"));
        assertFalse(result.contains(context.getId() + " | alert_context"));
    }

    @Test
    void constrainReport_shouldAppendAuditAndFlagUnknownEvidenceIds() {
        DiagnosisEvidence trend = evidence("ev-cpu-1", "queryMetricTrend", "15m",
                "cpu_usage 最近 15m 持续上升，latest=94.00");

        String report = "# 告警分析报告\n\nCPU 持续上升 [evidence: ev-missing]";
        String result = guard.constrainReport(report, List.of(trend));

        assertTrue(result.contains("# 告警分析报告"));
        assertTrue(result.contains("## 证据校验"));
        assertTrue(result.contains("未知证据: ev-missing"));
        assertTrue(result.contains("置信度: 低"));
    }

    @Test
    void constrainReport_shouldMarkHighConfidenceWhenCitationsMatchToolEvidence() {
        DiagnosisEvidence trend = evidence("ev-cpu-1", "queryMetricTrend", "1h",
                "cpu_usage 最近 1h 持续上升，latest=94.00");
        DiagnosisEvidence logs = evidence("ev-log-1", "queryLogs", "recent logs",
                "成功查询到 5 条 ERROR 日志");

        String report = """
                # 告警分析报告

                CPU 持续上升 [evidence: ev-cpu-1]，日志中存在 ERROR [evidence: ev-log-1]。
                """;
        String result = guard.constrainReport(report, List.of(trend, logs));

        assertTrue(result.contains("置信度: 高"));
        assertTrue(result.contains("已引用证据: ev-cpu-1, ev-log-1"));
        assertTrue(result.contains("缺失证据: 无"));
    }

    @Test
    void constrainReport_shouldFlagResourceClaimWithoutMetricTrendCitation() {
        DiagnosisEvidence logs = evidence("ev-log-1", "queryLogs", "recent logs",
                "成功查询到 5 条 ERROR 日志");

        String report = "# 告警分析报告\n\nCPU 使用率异常 [evidence: ev-log-1]";
        String result = guard.constrainReport(report, List.of(logs));

        assertTrue(result.contains("资源类结论缺少成功的 queryMetricTrend 趋势 evidence"));
        assertTrue(result.contains("置信度: 中"));
    }

    private DiagnosisEvidence evidence(String id, String toolName, String timeRange, String summary) {
        DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                toolName,
                "{\"query\":\"test\"}",
                timeRange,
                summary,
                "{\"success\":true}",
                true,
                null,
                1L);
        evidence.setId(id);
        return evidence;
    }
}
