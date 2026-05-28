package org.example.service;

import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.junit.jupiter.api.Test;

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

        assertTrue(table.contains("| Evidence ID | 类型 | 工具 | 时间范围 | 状态 | 摘要 |"));
        assertTrue(table.contains("queryMetricTrend"));
        assertTrue(table.contains("cpu_usage 最近 15m 持续上升"));
        assertFalse(table.contains("raw payload"));
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
}
