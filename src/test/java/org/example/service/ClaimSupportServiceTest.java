package org.example.service;

import org.example.dto.DiagnosisEvidence;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimSupportServiceTest {

    private final ClaimSupportService service = new ClaimSupportService();

    @Test
    void metricClaimRequiresMetricTrendEvidence() {
        DiagnosisEvidence logs = DiagnosisEvidence.toolCall(
                "queryLogs", "{\"query\":\"ERROR\"}", "15m",
                "日志显示错误", "{\"success\":true}", true, null, 1L);
        logs.setId("ev-logs");

        assertTrue(service.findUnsupportedClaims(
                "## 告警根因分析\nCPU 持续升高 [evidence: ev-logs]",
                Map.of("ev-logs", logs)).size() == 1);
    }

    @Test
    void logClaimAcceptsSuccessfulLogEvidence() {
        DiagnosisEvidence logs = DiagnosisEvidence.toolCall(
                "queryLogs", "{\"query\":\"OOMKilled\"}", "15m",
                "OOMKilled 事件", "{\"success\":true}", true, null, 1L);
        logs.setId("ev-logs");

        assertTrue(service.findUnsupportedClaims(
                "## 告警根因分析\nOOMKilled 事件 [evidence: ev-logs]",
                Map.of("ev-logs", logs)).isEmpty());
    }

    @Test
    void metricClaimRejectsEvidenceWithConflictingTrendDirection() {
        DiagnosisEvidence stableCpu = DiagnosisEvidence.toolCall(
                "queryMetricTrend", "{\"metric\":\"cpu_usage\",\"window\":\"15m\"}", "15m",
                "cpu_usage 最近 15m 整体平稳，latest=40.00", "{\"metric\":\"cpu_usage\"}",
                true, null, 1L);
        stableCpu.setId("ev-cpu");

        assertTrue(service.findUnsupportedClaims(
                "## 告警根因分析\nCPU 使用率持续上升 [evidence: ev-cpu]",
                Map.of("ev-cpu", stableCpu)).size() == 1);
    }

    @Test
    void metricClaimRejectsContradictoryDirectionsWhenOnlyOneIsSupported() {
        DiagnosisEvidence risingCpu = DiagnosisEvidence.toolCall(
                "queryMetricTrend", "{\"metric\":\"cpu_usage\",\"window\":\"15m\"}", "15m",
                "cpu_usage 持续上升，latest=90.00", "{\"metric\":\"cpu_usage\"}",
                true, null, 1L);
        risingCpu.setId("ev-cpu");

        assertTrue(service.findUnsupportedClaims(
                "## 告警根因分析\nCPU 先上升后下降 [evidence: ev-cpu]",
                Map.of("ev-cpu", risingCpu)).size() == 1);
    }
}
