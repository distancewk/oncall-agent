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
}
