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
        assertEquals(List.of("alert_context", "final_report"), completed.getEvidence().stream()
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

    private IncidentService newIncidentService() {
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setPath(tempDir.resolve("incidents").toString());
        IncidentStore store = new IncidentStore(properties, new ObjectMapper());
        return new IncidentService(store, new ObjectMapper());
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
