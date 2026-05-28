package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppIncidentProperties;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncidentServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void recordAlert_shouldAggregateByFingerprintAndRestoreAfterRestart() {
        IncidentService service = newIncidentService();

        IncidentRecord first = service.recordAlert(alertPayload("fp-cpu-1", "HighCPUUsage", "payment-service"));
        IncidentRecord second = service.recordAlert(alertPayload("fp-cpu-1", "HighCPUUsage", "payment-service"));

        assertEquals(first.getId(), second.getId());
        assertEquals("fingerprint:fp-cpu-1", second.getAggregationKey());
        assertEquals(2, second.getAlertCount());
        assertEquals(2, second.getAlertPayloads().size());

        IncidentService restarted = newIncidentService();
        IncidentRecord restored = restarted.getIncident(first.getId()).orElseThrow();

        assertEquals(first.getId(), restored.getId());
        assertEquals("HighCPUUsage payment-service", restored.getTitle());
        assertEquals("critical", restored.getSeverity());
        assertEquals(2, restored.getAlertCount());
    }

    @Test
    void createDiagnosisRun_shouldPersistRunningAndCompletedStateWithEvidence() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-memory-1", "HighMemoryUsage", "order-service"));

        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "告警上下文");
        DiagnosisRunRecord running = service.markRunRunning(incident.getId(), queued.getRunId());
        DiagnosisRunRecord completed = service.completeRun(incident.getId(), queued.getRunId(), "# 告警分析报告");

        assertEquals("QUEUED", queued.getStatus());
        assertEquals("RUNNING", running.getStatus());
        assertEquals("COMPLETED", completed.getStatus());
        assertEquals("# 告警分析报告", completed.getReport());
        assertTrue(completed.getCompletedAt() >= completed.getStartedAt());
        assertEquals(List.of("alert_context"), completed.getEvidence().stream()
                .map(evidence -> evidence.getType())
                .toList());

        IncidentRecord restored = newIncidentService().getIncident(incident.getId()).orElseThrow();
        assertEquals("COMPLETED", restored.getDiagnosisRuns().get(0).getStatus());
    }

    @Test
    void failRun_shouldPersistFailureReasonAndEvidence() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-disk-1", "HighDiskUsage", "storage-service"));
        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "磁盘告警上下文");

        DiagnosisRunRecord failed = service.failRun(incident.getId(), queued.getRunId(), "Prometheus 查询失败");

        assertEquals("FAILED", failed.getStatus());
        assertEquals("Prometheus 查询失败", failed.getErrorMessage());
        assertEquals("failure_reason", failed.getEvidence().get(1).getType());
        assertEquals("Prometheus 查询失败", failed.getEvidence().get(1).getContent());
    }

    @Test
    void toolEvidence_shouldPersistWaitingToolProgressAndStructuredEvidence() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-2", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");

        DiagnosisRunRecord waiting = service.markRunWaitingTool(
                incident.getId(), queued.getRunId(), "queryPrometheusAlerts", "{\"range\":\"1h\"}");
        assertEquals("WAITING_TOOL", waiting.getStatus());
        assertEquals("queryPrometheusAlerts", waiting.getCurrentTool());
        assertEquals("正在调用工具 queryPrometheusAlerts", waiting.getCurrentStep());

        DiagnosisRunRecord updated = service.addToolEvidence(
                incident.getId(),
                queued.getRunId(),
                org.example.dto.DiagnosisEvidence.toolCall(
                        "queryPrometheusAlerts",
                        "{\"range\":\"1h\"}",
                        "now-1h to now",
                        "返回 2 条活动告警",
                        "{\"success\":true,\"alerts\":[{\"alertName\":\"HighCPUUsage\"}]}",
                        true,
                        null,
                        System.currentTimeMillis()));

        assertEquals("RUNNING", updated.getStatus());
        assertEquals("已完成工具调用 queryPrometheusAlerts", updated.getCurrentStep());
        assertEquals("返回 2 条活动告警", updated.getProgressMessage());
        assertEquals("queryPrometheusAlerts", updated.getEvidence().get(1).getToolName());
        assertEquals("{\"range\":\"1h\"}", updated.getEvidence().get(1).getQueryParams());
        assertEquals("now-1h to now", updated.getEvidence().get(1).getTimeRange());
        assertTrue(updated.getEvidence().get(1).isSuccess());
    }

    @Test
    void updateRunAlertContext_shouldReplacePersistedContextAndAlertContextEvidence() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-3", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "基础告警上下文");

        DiagnosisRunRecord updated = service.updateRunAlertContext(
                incident.getId(), queued.getRunId(), "基础告警上下文\n\n## 预取指标趋势证据");

        assertEquals("基础告警上下文\n\n## 预取指标趋势证据", updated.getAlertContext());
        assertEquals(1, updated.getEvidence().stream()
                .filter(evidence -> "alert_context".equals(evidence.getType()))
                .count());
        assertEquals("基础告警上下文\n\n## 预取指标趋势证据", updated.getEvidence().get(0).getContent());

        IncidentRecord restored = newIncidentService().getIncident(incident.getId()).orElseThrow();
        assertEquals("基础告警上下文\n\n## 预取指标趋势证据",
                restored.getDiagnosisRuns().get(0).getAlertContext());
    }

    @Test
    void completeRun_shouldApplyEvidenceGuard_whenGuardIsConfigured() {
        IncidentService service = newIncidentService(new DiagnosisReportService());
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-4", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord queued = service.createDiagnosisRun(incident.getId(), "CPU 告警上下文");
        org.example.dto.DiagnosisEvidence evidence = org.example.dto.DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "15m",
                "cpu_usage 最近 15m 持续上升",
                "{\"success\":true}",
                true,
                null,
                System.currentTimeMillis());
        evidence.setId("ev-cpu-guard");
        service.addToolEvidence(incident.getId(), queued.getRunId(), evidence);

        DiagnosisRunRecord completed = service.completeRun(
                incident.getId(),
                queued.getRunId(),
                "# 告警分析报告\n\nCPU 持续上升 [evidence: ev-cpu-guard]");

        assertTrue(completed.getReport().contains("## 证据校验"));
        assertTrue(completed.getReport().contains("置信度: 高"));
        assertTrue(completed.getReport().contains("已引用证据: ev-cpu-guard"));
    }

    @Test
    void createReusedDiagnosisRunIfAvailable_shouldReuseLatestCompletedReportForSameIncident() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-reuse", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord original = service.createDiagnosisRun(incident.getId(), "原始告警上下文");
        DiagnosisRunRecord completed = service.completeRun(
                incident.getId(),
                original.getRunId(),
                "# 告警分析报告\n\nCPU 持续高水位");

        DiagnosisRunRecord reused = service.createReusedDiagnosisRunIfAvailable(
                incident.getId(), "重复告警上下文").orElseThrow();

        assertEquals("COMPLETED", reused.getStatus());
        assertEquals(completed.getReport(), reused.getReport());
        assertEquals(completed.getRunId(), reused.getReusedFromRunId());
        assertEquals(1.0d, reused.getReuseConfidence());
        assertTrue(reused.getReuseValidatedAt() > 0);
        assertTrue(reused.getReuseReason().contains("同一 Incident 已存在完成诊断"));
        assertEquals("复用历史诊断报告", reused.getCurrentStep());
        assertEquals(List.of("alert_context", "diagnosis_reuse"), reused.getEvidence().stream()
                .map(evidence -> evidence.getType())
                .toList());

        IncidentRecord restored = newIncidentService().getIncident(incident.getId()).orElseThrow();
        assertEquals(2, restored.getDiagnosisRuns().size());
        assertEquals(completed.getRunId(), restored.getDiagnosisRuns().get(1).getReusedFromRunId());
    }

    @Test
    void createReusedDiagnosisRunIfAvailable_shouldReturnEmptyWhenNoCompletedReportExists() {
        IncidentService service = newIncidentService();
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-no-reuse", "HighCPUUsage", "payment-service"));
        service.createDiagnosisRun(incident.getId(), "告警上下文");

        assertFalse(service.createReusedDiagnosisRunIfAvailable(incident.getId(), "重复告警上下文").isPresent());
    }

    @Test
    void createReusedDiagnosisRunIfAvailable_shouldReturnEmptyWhenReuseIsDisabled() {
        AppIncidentProperties properties = incidentProperties();
        properties.setDiagnosisReuseEnabled(false);
        IncidentService service = newIncidentService(null, properties);
        IncidentRecord incident = service.recordAlert(alertPayload("fp-cpu-reuse-disabled", "HighCPUUsage", "payment-service"));
        DiagnosisRunRecord original = service.createDiagnosisRun(incident.getId(), "原始告警上下文");
        service.completeRun(incident.getId(), original.getRunId(), "# 告警分析报告");

        assertFalse(service.createReusedDiagnosisRunIfAvailable(incident.getId(), "重复告警上下文").isPresent());
    }

    private IncidentService newIncidentService() {
        return newIncidentService(null);
    }

    private IncidentService newIncidentService(DiagnosisReportService reportService) {
        return newIncidentService(reportService, incidentProperties());
    }

    private IncidentService newIncidentService(DiagnosisReportService reportService,
                                               AppIncidentProperties properties) {
        IncidentStore store = new IncidentStore(properties, new ObjectMapper());
        return new IncidentService(store, new ObjectMapper(), reportService, properties);
    }

    private AppIncidentProperties incidentProperties() {
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setPath(tempDir.resolve("incidents").toString());
        return properties;
    }

    private AlertPayload alertPayload(String fingerprint, String alertName, String service) {
        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setStatus("firing");
        alert.setFingerprint(fingerprint);
        alert.setStartsAt("2026-05-13T00:00:00Z");
        alert.setLabels(Map.of(
                "alertname", alertName,
                "severity", "critical",
                "service", service,
                "instance", service + "-pod-1"
        ));
        alert.setAnnotations(Map.of("summary", alertName + " " + service));

        AlertPayload payload = new AlertPayload();
        payload.setReceiver("webhook");
        payload.setStatus("firing");
        payload.setCommonLabels(Map.of("severity", "critical", "service", service));
        payload.setCommonAnnotations(Map.of("summary", alertName + " " + service));
        payload.setAlerts(List.of(alert));
        payload.setExternalURL("http://alertmanager.example/alerts");
        return payload;
    }
}
