package org.example.service;

import org.example.dto.DiagnosisEvidence;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceEntailmentPolicyTest {

    private final EvidenceEntailmentPolicy policy = new EvidenceEntailmentPolicy();

    @Test
    void findUnsupportedClaims_shouldAcceptSharedMetricAnchor() {
        DiagnosisEvidence evidence = evidence("ev-cpu", "cpu_usage 最近 15m 持续上升，latest=94.00");

        assertTrue(policy.findUnsupportedClaims(
                "## 告警根因分析\ncpu_usage 最近 15m 持续上升 [evidence: ev-cpu]",
                Map.of("ev-cpu", evidence)).isEmpty());
    }

    @Test
    void findUnsupportedClaims_shouldRejectCitationWithoutClaimAnchor() {
        DiagnosisEvidence evidence = evidence("ev-cpu", "cpu_usage 最近 15m 持续上升，latest=94.00");

        assertEquals(1, policy.findUnsupportedClaims(
                "## 告警根因分析\n数据库连接池耗尽 [evidence: ev-cpu]",
                Map.of("ev-cpu", evidence)).size());
    }

    @Test
    void findUnsupportedClaims_shouldRejectCitationWithOnlyOneSharedAnchor() {
        DiagnosisEvidence evidence = evidence("ev-db", "database latency is high");

        assertEquals(1, policy.findUnsupportedClaims(
                "## 告警根因分析\ndatabase connection pool exhausted [evidence: ev-db]",
                Map.of("ev-db", evidence)).size());
    }

    @Test
    void findUnsupportedClaims_shouldNotTreatUncertaintyAsFact() {
        DiagnosisEvidence evidence = evidence("ev-cpu", "cpu_usage 最近 15m 持续上升");

        assertTrue(policy.findUnsupportedClaims(
                "## 结论\n证据不足，等待人工复核 [evidence: ev-cpu]",
                Map.of("ev-cpu", evidence)).isEmpty());
    }

    private DiagnosisEvidence evidence(String id, String summary) {
        DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                "queryMetricTrend", "{\"metric\":\"cpu_usage\"}", "15m",
                summary, "{\"success\":true}", true, null, 1L);
        evidence.setId(id);
        return evidence;
    }
}
