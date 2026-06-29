package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.QueryMetricsTools;
import org.example.config.AppIncidentProperties;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricTrendPrefetchServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void prefetchAndAppend_shouldRecordCpuTrendEvidenceAndAppendContext() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        IncidentService incidentService = newIncidentService(objectMapper);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper);
        QueryMetricsTools tools = new QueryMetricsTools();
        ReflectionTestUtils.setField(tools, "prometheusBaseUrl", "http://localhost:9090");
        ReflectionTestUtils.setField(tools, "mockEnabled", true);
        ReflectionTestUtils.setField(tools, "diagnosisEvidenceRecorder", recorder);

        MetricTrendPrefetchService service = new MetricTrendPrefetchService(tools, recorder);
        IncidentRecord incident = incidentService.recordAlert(alertPayload("HighCPUUsage", "payment-service",
                "pod-payment-service-1"));
        DiagnosisRunRecord run = incidentService.createDiagnosisRun(incident.getId(), "基础告警上下文");
        incidentService.markRunRunning(incident.getId(), run.getRunId());

        String enrichedContext = service.prefetchAndAppend(incident, run, "基础告警上下文");

        assertTrue(enrichedContext.contains("预取指标趋势证据"));
        assertTrue(enrichedContext.contains("metric=cpu_usage"));
        assertTrue(enrichedContext.contains("[evidence: ev-"));

        DiagnosisRunRecord updatedRun = incidentService.getDiagnosisRuns(incident.getId()).orElseThrow().get(0);
        long trendEvidenceCount = updatedRun.getEvidence().stream()
                .filter(evidence -> "tool_call".equals(evidence.getType()))
                .filter(evidence -> QueryMetricsTools.TOOL_QUERY_METRIC_TREND.equals(evidence.getToolName()))
                .filter(evidence -> evidence.getQueryParams().contains("\"metric\":\"cpu_usage\""))
                .count();
        assertEquals(2, trendEvidenceCount);
    }

    @Test
    void prefetchAndAppend_shouldReturnOriginalContextWhenMetricCannotBeInferred() {
        MetricTrendPrefetchService service = new MetricTrendPrefetchService(null, null);
        IncidentRecord incident = new IncidentRecord();
        incident.setId("inc-1");
        incident.setAlertPayloads(List.of(alertPayload("DiskSpaceLow", "storage-service", "pod-storage-1")));
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-1");

        String context = service.prefetchAndAppend(incident, run, "基础告警上下文");

        assertEquals("基础告警上下文", context);
    }

    private IncidentService newIncidentService(ObjectMapper objectMapper) {
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setPath(tempDir.resolve("incidents").toString());
        properties.setJdbcUrl("jdbc:h2:" + tempDir.resolve("incidents-db"));
        properties.setJdbcUsername("");
        properties.setJdbcPassword("");
        IncidentStore store = new IncidentStore(properties, objectMapper, dataSource(properties));
        return new IncidentService(store, objectMapper);
    }

    private DataSource dataSource(AppIncidentProperties properties) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(properties.getJdbcUrl());
        dataSource.setUsername(properties.getJdbcUsername());
        dataSource.setPassword(properties.getJdbcPassword());
        return dataSource;
    }

    private AlertPayload alertPayload(String alertName, String service, String instance) {
        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setStatus("firing");
        alert.setFingerprint("fp-" + alertName);
        alert.setStartsAt("2026-05-13T00:00:00Z");
        alert.setLabels(Map.of(
                "alertname", alertName,
                "severity", "critical",
                "service", service,
                "instance", instance
        ));
        alert.setAnnotations(Map.of("summary", alertName + " " + service));

        AlertPayload payload = new AlertPayload();
        payload.setReceiver("webhook");
        payload.setStatus("firing");
        payload.setCommonLabels(Map.of("severity", "critical", "service", service));
        payload.setCommonAnnotations(Map.of("summary", alertName + " " + service));
        payload.setAlerts(List.of(alert));
        return payload;
    }
}
