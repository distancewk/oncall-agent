package org.example.service;

import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosisReportServiceTest {

    private final DiagnosisReportService service = new DiagnosisReportService();

    @Test
    void buildEvidenceTable_shouldRenderCompactToolEvidenceWithoutRawFragments() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.getEvidence().add(DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\",\"window\":\"15m\"}",
                "15m",
                "cpu_usage 最近 15m 持续上升，latest=94.00",
                "{\"points\":[1,2,3],\"huge\":\"raw payload\"}",
                true,
                null,
                1L));

        String table = service.buildEvidenceTable(run);

        assertTrue(table.contains("| Evidence ID | 类型 | 工具 | 时间范围 | 状态 | 尝试次数 | 耗时(ms) | 可重试 | 摘要 |"));
        assertTrue(table.contains("queryMetricTrend"));
        assertTrue(table.contains("cpu_usage 最近 15m 持续上升"));
        assertFalse(table.contains("raw payload"));
    }

    @Test
    void buildEvidenceTableShouldRenderToolCallTelemetryForReportContext() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        DiagnosisEvidence retriedTrend = DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "1h",
                "cpu_usage 最近 1h 持续上升",
                "{\"success\":true}",
                true,
                null,
                1L);
        retriedTrend.setId("ev-retried");
        retriedTrend.setAttemptCount(3);
        retriedTrend.setDurationMs(1250);
        retriedTrend.setRetryable(true);
        run.getEvidence().add(retriedTrend);

        DiagnosisEvidence failedLogs = DiagnosisEvidence.toolCall(
                "queryLogs",
                "{\"query\":\"ERROR\"}",
                "15m",
                "日志依赖熔断，未执行查询",
                "{\"success\":false,\"errorCode\":\"CIRCUIT_OPEN\"}",
                false,
                "日志依赖熔断",
                1L);
        failedLogs.setId("ev-open");
        failedLogs.setErrorCode("CIRCUIT_OPEN");
        failedLogs.setAttemptCount(0);
        failedLogs.setDurationMs(0);
        failedLogs.setRetryable(true);
        run.getEvidence().add(failedLogs);

        String table = service.buildEvidenceTable(run);

        assertTrue(table.contains("| Evidence ID | 类型 | 工具 | 时间范围 | 状态 | 尝试次数 | 耗时(ms) | 可重试 | 摘要 |"));
        assertTrue(table.contains("| ev-retried | tool_call | queryMetricTrend | 1h | success | 3 | 1250 | true |"));
        assertTrue(table.contains("| Evidence ID | 工具 | 状态 | 错误码 | 尝试次数 | 耗时(ms) | 可重试 | 摘要 |"));
        assertTrue(table.contains("| ev-open | queryLogs | failed | CIRCUIT_OPEN | 0 | 0 | true |"));
    }

    @Test
    void buildEvidenceTable_shouldSeparatePolicySkipsFromRealFailures() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        DiagnosisEvidence skipped = DiagnosisEvidence.toolCall(
                "queryLogs", "{\"query\":\"same\"}", "15m", "重复工具调用已跳过",
                "{\"success\":false}", false, "重复工具调用已跳过", 1L);
        skipped.setId("ev-skipped");
        skipped.setErrorCode("TOOL_DUPLICATE_SKIPPED");
        skipped.setAttemptCount(0);
        run.getEvidence().add(skipped);

        String table = service.buildEvidenceTable(run);

        assertTrue(table.contains("工具策略拦截说明"));
        assertTrue(table.contains("| ev-skipped | queryLogs | skipped | TOOL_DUPLICATE_SKIPPED |"));
        assertFalse(table.contains("失败工具证据说明"));
    }

    @Test
    void evaluateQuality_shouldNotPenalizePolicySkippedEvidenceAsRealFailure() {
        DiagnosisEvidence trend = DiagnosisEvidence.toolCall(
                "queryMetricTrend", "{\"metric\":\"cpu_usage\"}", "15m",
                "cpu_usage 最近 15m 持续上升", "{\"success\":true}", true, null, 1L);
        trend.setId("ev-trend");
        DiagnosisEvidence skipped = DiagnosisEvidence.toolCall(
                "queryLogs", "{\"query\":\"same\"}", "15m", "预算超限，跳过",
                "{\"success\":false}", false, "预算超限", 1L);
        skipped.setId("ev-skipped");
        skipped.setErrorCode("TOOL_BUDGET_EXCEEDED");
        skipped.setAttemptCount(0);

        DiagnosisReportService.QualityAssessment quality = service.evaluateQuality(
                "CPU 使用率持续上升 [evidence: ev-trend]。", java.util.List.of(trend, skipped));

        assertEquals("HIGH", quality.grade());
        assertTrue(quality.issues().isEmpty());
    }

    @Test
    void evaluateQuality_shouldForceGapWhenSuccessfulEvidenceIsNotCited() {
        DiagnosisEvidence trend = DiagnosisEvidence.toolCall(
                "queryMetricTrend", "{\"metric\":\"cpu_usage\"}", "15m",
                "CPU 趋势", "{\"success\":true}", true, null, 1L);
        trend.setId("ev-uncited");

        DiagnosisReportService.QualityAssessment quality = service.evaluateQuality("""
                # 告警分析报告
                ## 活跃告警清单
                ## 告警根因分析
                当前证据不足，暂不确认根因。
                ## 处理方案执行
                等待人工复核。
                ## 结论
                暂不下结论。
                ### 置信度
                低
                ### 缺失证据
                - 真实运行数据
                """, List.of(trend));

        assertEquals("LOW", quality.grade());
        assertTrue(quality.score() < 60);
        assertTrue(quality.issues().contains("报告没有引用工具 evidence id"));
    }

    @Test
    void evaluateQuality_shouldForceGapWhenNoSuccessfulEvidenceExists() {
        DiagnosisReportService.QualityAssessment quality = service.evaluateQuality("""
                # 告警分析报告
                ## 活跃告警清单
                ## 告警根因分析
                证据不足。
                ## 处理方案执行
                等待真实数据。
                ## 结论
                无法确认根因。
                ### 置信度
                低
                ### 缺失证据
                - 真实指标和日志
                """, List.of());

        assertEquals("LOW", quality.grade());
        assertTrue(quality.score() < 60);
        assertTrue(quality.issues().contains("没有成功工具 evidence"));
    }

    @Test
    void evaluateQuality_shouldForceGapWhenCitedEvidenceIsMock() {
        DiagnosisEvidence mockTrend = DiagnosisEvidence.toolCall(
                "queryMetricTrend", "{\"metric\":\"cpu_usage\"}", "15m",
                "CPU 趋势", "{\"success\":true,\"dataMode\":\"MOCK\"}",
                true, null, 1L);
        mockTrend.setId("ev-mock-trend");

        DiagnosisReportService.QualityAssessment quality = service.evaluateQuality("""
                # 告警分析报告
                ## 活跃告警清单
                ## 告警根因分析
                CPU 持续升高 [evidence: ev-mock-trend]
                ## 处理方案执行
                继续观察 [evidence: ev-mock-trend]
                ## 结论
                需要真实数据复核。
                ### 置信度
                低
                ### 缺失证据
                - 真实 CPU 趋势
                """, List.of(mockTrend));

        assertEquals("LOW", quality.grade());
        assertTrue(quality.issues().contains("报告引用了 Mock 数据，不能视为真实诊断证据"));
    }

    @Test
    void guardReport_shouldDowngradeValidationForCitedMockEvidence() {
        DiagnosisEvidence mockTrend = DiagnosisEvidence.toolCall(
                "queryMetricTrend", "{\"metric\":\"cpu_usage\"}", "15m",
                "CPU 趋势", "{\"success\":true,\"dataMode\":\"MOCK\"}",
                true, null, 1L);
        mockTrend.setId("ev-mock-validation");

        String guarded = service.constrainReport("""
                # 告警分析报告
                ### 根因结论
                CPU 使用率持续升高 [evidence: ev-mock-validation]
                ### 置信度
                高
                """, List.of(mockTrend));

        assertTrue(guarded.contains("校验状态: 需补证"));
        assertTrue(guarded.contains("置信度: 低"));
        assertFalse(guarded.contains("### 置信度\n高"));
    }

    @Test
    void guardReport_shouldFlagUnknownEvidenceIdsAndUnsupportedGcClaims() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        DiagnosisEvidence cpuTrend = DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "15m",
                "cpu_usage 最近 15m 持续上升，latest=94.00",
                "{\"metric\":\"cpu_usage\"}",
                true,
                null,
                1L);
        cpuTrend.setId("ev-cpu");
        run.getEvidence().add(cpuTrend);

        String guarded = service.guardReport("""
                # 告警分析报告

                ### 根因结论
                Full GC 风暴导致 CPU 升高。[evidence: ev-missing]
                """, run);

        assertTrue(guarded.contains("## 证据校验"));
        assertTrue(guarded.contains("置信度: 低"));
        assertTrue(guarded.contains("ev-missing"));
        assertTrue(guarded.contains("GC/Full GC"));
    }

    @Test
    void guardReport_shouldMarkHighConfidenceWhenReferencesAreValidAndClaimsAreSupported() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        DiagnosisEvidence cpuTrend = DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "1h",
                "cpu_usage 最近 1h 持续上升，latest=94.00",
                "{\"metric\":\"cpu_usage\",\"summary\":{\"anomalous\":true}}",
                true,
                null,
                1L);
        cpuTrend.setId("ev-cpu");
        run.getEvidence().add(cpuTrend);

        String guarded = service.guardReport("""
                # 告警分析报告

                ### 根因结论
                CPU 使用率持续上升。[evidence: ev-cpu]
                """, run);

        assertTrue(guarded.contains("## 证据校验"));
        assertTrue(guarded.contains("置信度: 高"));
        assertTrue(guarded.contains("校验状态: 通过"));
    }

    @Test
    void guardReport_shouldNotAllowFailedMetricEvidenceToSupportResourceClaim() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        DiagnosisEvidence failedTrend = DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "1h",
                "Prometheus 熔断，未执行指标趋势查询",
                "{\"success\":false,\"errorCode\":\"CIRCUIT_OPEN\"}",
                false,
                "Prometheus 熔断",
                1L);
        failedTrend.setId("ev-open");
        failedTrend.setErrorCode("CIRCUIT_OPEN");
        run.getEvidence().add(failedTrend);

        String guarded = service.guardReport("""
                # 告警分析报告

                ### 根因结论
                CPU 使用率持续上升。[evidence: ev-open]
                """, run);

        assertTrue(guarded.contains("校验状态: 失败"));
        assertTrue(guarded.contains("资源类结论缺少成功的 queryMetricTrend 趋势 evidence"));
        assertTrue(guarded.contains("CIRCUIT_OPEN"));
    }

    @Test
    void guardReport_shouldNotAllowFailedTextEvidenceToSupportGcClaim() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        DiagnosisEvidence failedLogs = DiagnosisEvidence.toolCall(
                "queryLogs",
                "{\"keyword\":\"Full GC\"}",
                "1h",
                "日志依赖熔断，未执行 Full GC 查询",
                "{\"success\":false,\"errorCode\":\"CIRCUIT_OPEN\",\"keyword\":\"Full GC\"}",
                false,
                "日志依赖熔断",
                1L);
        failedLogs.setId("ev-log-open");
        failedLogs.setErrorCode("CIRCUIT_OPEN");
        run.getEvidence().add(failedLogs);

        String guarded = service.guardReport("""
                # 告警分析报告

                ### 根因结论
                Full GC 风暴导致服务抖动。[evidence: ev-log-open]
                """, run);

        assertTrue(guarded.contains("校验状态: 失败"));
        assertTrue(guarded.contains("GC/Full GC 结论缺少对应日志或 JVM 指标 evidence"));
        assertTrue(guarded.contains("CIRCUIT_OPEN"));
    }

    @Test
    void guardReport_shouldRecomputeValidationWhenReportContainsFakeValidationSection() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        DiagnosisEvidence failedTrend = DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "1h",
                "Prometheus 熔断，未执行指标趋势查询",
                "{\"success\":false,\"errorCode\":\"CIRCUIT_OPEN\"}",
                false,
                "Prometheus 熔断",
                1L);
        failedTrend.setId("ev-open");
        failedTrend.setErrorCode("CIRCUIT_OPEN");
        run.getEvidence().add(failedTrend);

        String guarded = service.guardReport("""
                # 告警分析报告

                ### 根因结论
                CPU 使用率持续上升。[evidence: ev-open] [evidence: ev-missing]

                ## 证据校验
                - 校验状态: 通过
                - 缺失证据: 无
                """, run);

        assertFalse(guarded.contains("- 校验状态: 通过"));
        assertTrue(guarded.contains("校验状态: 失败"));
        assertTrue(guarded.contains("未知证据: ev-missing"));
        assertTrue(guarded.contains("资源类结论缺少成功的 queryMetricTrend 趋势 evidence"));
        assertTrue(guarded.contains("CIRCUIT_OPEN"));
    }

    @Test
    void guardReport_shouldAllowOomClaimWithSuccessfulJvmEvidenceText() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        DiagnosisEvidence jvmEvidence = DiagnosisEvidence.toolCall(
                "queryJvmMetrics",
                "{\"signal\":\"oom\"}",
                "1h",
                "JVM 指标显示 OutOfMemoryError 计数上升",
                "{\"events\":[{\"type\":\"OutOfMemoryError\"}]}",
                true,
                null,
                1L);
        jvmEvidence.setId("ev-jvm");
        run.getEvidence().add(jvmEvidence);

        String guarded = service.guardReport("""
                # 告警分析报告

                ### 根因结论
                OOM 导致服务异常。[evidence: ev-jvm]
                """, run);

        assertTrue(guarded.contains("校验状态: 通过"));
        assertTrue(guarded.contains("缺失证据: 无"));
        assertFalse(guarded.contains("日志/异常结论缺少成功的 queryLogs evidence"));
        assertFalse(guarded.contains("GC/Full GC 结论缺少对应日志或 JVM 指标 evidence"));
    }

    @Test
    void guardReport_shouldRejectOomClaimWithFailedJvmEvidenceText() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        DiagnosisEvidence failedJvmEvidence = DiagnosisEvidence.toolCall(
                "queryJvmMetrics",
                "{\"signal\":\"oom\"}",
                "1h",
                "JVM 指标查询失败，未确认 OutOfMemoryError",
                "{\"success\":false,\"errorCode\":\"CIRCUIT_OPEN\",\"signal\":\"OutOfMemoryError\"}",
                false,
                "JVM 指标依赖熔断",
                1L);
        failedJvmEvidence.setId("ev-jvm-open");
        failedJvmEvidence.setErrorCode("CIRCUIT_OPEN");
        run.getEvidence().add(failedJvmEvidence);

        String guarded = service.guardReport("""
                # 告警分析报告

                ### 根因结论
                OOM 导致服务异常。[evidence: ev-jvm-open]
                """, run);

        assertTrue(guarded.contains("校验状态: 失败"));
        assertTrue(guarded.contains("日志/异常结论缺少成功的 queryLogs evidence"));
        assertTrue(guarded.contains("GC/Full GC 结论缺少对应日志或 JVM 指标 evidence"));
        assertTrue(guarded.contains("CIRCUIT_OPEN"));
    }

    @Test
    void guardReport_shouldNotUseQueryParamsAsOomEvidenceText() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        DiagnosisEvidence emptyJvmEvidence = DiagnosisEvidence.toolCall(
                "queryJvmMetrics",
                "{\"signal\":\"OutOfMemoryError\"}",
                "1h",
                "指标查询成功，未发现异常信号",
                "{\"success\":true,\"events\":[]}",
                true,
                null,
                1L);
        emptyJvmEvidence.setId("ev-jvm-empty");
        run.getEvidence().add(emptyJvmEvidence);

        String guarded = service.guardReport("""
                # 告警分析报告

                ### 根因结论
                OOM 导致服务异常。[evidence: ev-jvm-empty]
                """, run);

        assertTrue(guarded.contains("校验状态: 需补证"));
        assertTrue(guarded.contains("GC/Full GC 结论缺少对应日志或 JVM 指标 evidence"));
    }

    @Test
    void guardReport_shouldRejectGenericRootCauseClaimBackedOnlyByFailedEvidence() {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        DiagnosisEvidence failedDocs = DiagnosisEvidence.toolCall(
                "queryInternalDocs",
                "{\"keyword\":\"config\"}",
                "latest",
                "知识库依赖失败，未确认配置变更",
                "{\"success\":false,\"errorCode\":\"DEPENDENCY_ERROR\"}",
                false,
                "知识库依赖失败",
                1L);
        failedDocs.setId("ev-doc-failed");
        failedDocs.setErrorCode("DEPENDENCY_ERROR");
        run.getEvidence().add(failedDocs);

        String guarded = service.guardReport("""
                # 告警分析报告

                ### 根因结论
                根因是配置变更引发服务异常。[evidence: ev-doc-failed]
                """, run);

        assertTrue(guarded.contains("校验状态: 失败"));
        assertTrue(guarded.contains("无效/失败证据: ev-doc-failed(DEPENDENCY_ERROR)"));
        assertFalse(guarded.contains("置信度: 高"));
    }

    @Test
    void evaluateQuality_shouldScoreHighWhenReportCitesSuccessfulEvidence() {
        DiagnosisEvidence trend = DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "1h",
                "cpu_usage 最近 1h 持续上升",
                "{\"success\":true}",
                true,
                null,
                1L);
        trend.setId("ev-trend");
        DiagnosisEvidence logs = DiagnosisEvidence.toolCall(
                "queryLogs",
                "{\"query\":\"ERROR\"}",
                "15m",
                "日志中有线程池耗尽错误",
                "{\"success\":true}",
                true,
                null,
                1L);
        logs.setId("ev-log");

        DiagnosisReportService.QualityAssessment quality = service.evaluateQuality("""
                # 告警分析报告

                CPU 使用率持续上升 [evidence: ev-trend]，日志显示线程池耗尽 [evidence: ev-log]。
                """, java.util.List.of(trend, logs));

        assertEquals("HIGH", quality.grade());
        assertTrue(quality.score() >= 85);
        assertTrue(quality.issues().isEmpty());
    }

    @Test
    void evaluateQuality_shouldAcceptJvmGcMetricAsJvmEvidence() {
        DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"jvm_gc_collection_seconds_count\"}",
                "15m",
                "jvm_gc_collection_seconds_count 最近 15m 持续上升",
                "{\"success\":true,\"dataMode\":\"REAL\"}",
                true,
                null,
                System.currentTimeMillis());
        evidence.setId("ev-jvm-gc");

        DiagnosisReportService.QualityAssessment quality = service.evaluateQuality("""
                # 告警分析报告
                ## 活跃告警清单
                ## 告警根因分析
                Full GC 频率持续上升 [evidence: ev-jvm-gc]
                ## 处理方案执行
                检查 JVM 内存配置 [evidence: ev-jvm-gc]
                ## 结论
                ### 置信度
                中
                ### 缺失证据
                无
                """, List.of(evidence));

        assertFalse(quality.issues().contains("GC/Full GC 结论缺少对应日志或 JVM 指标 evidence"));
    }

    @Test
    void evaluateQuality_shouldScoreLowWhenReportUsesFailedEvidenceAndMissesTrend() {
        DiagnosisEvidence failedLogs = DiagnosisEvidence.toolCall(
                "queryLogs",
                "{\"query\":\"ERROR\"}",
                "15m",
                "日志查询失败",
                "{\"success\":false,\"errorCode\":\"CIRCUIT_OPEN\"}",
                false,
                "日志熔断",
                1L);
        failedLogs.setId("ev-log-open");
        failedLogs.setErrorCode("CIRCUIT_OPEN");

        DiagnosisReportService.QualityAssessment quality = service.evaluateQuality("""
                # 告警分析报告

                CPU 使用率持续上升，日志显示异常 [evidence: ev-log-open]。
                """, java.util.List.of(failedLogs));

        assertEquals("LOW", quality.grade());
        assertTrue(quality.score() < 60);
        assertTrue(quality.issues().contains("引用了失败或不可用 evidence"));
        assertTrue(quality.issues().contains("资源类结论缺少成功的趋势 evidence"));
    }
}
