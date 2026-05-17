package org.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.DiagnosisEvidence;
import org.example.service.DiagnosisEvidenceRecorder;
import org.example.service.IncidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class QueryMetricsToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private QueryMetricsTools tools;

    @BeforeEach
    void setUp() {
        tools = new QueryMetricsTools();
        ReflectionTestUtils.setField(tools, "prometheusBaseUrl", "http://localhost:9090");
        ReflectionTestUtils.setField(tools, "mockEnabled", true);
    }

    @Test
    void queryMetricTrend_shouldReturnIncreasingAnomalousCpuTrendInMockMode() throws Exception {
        String result = tools.queryMetricTrend("cpu_usage", "payment-service",
                "pod-payment-service-7d8f9c6b5-x2k4m", "1h", null);

        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.path("success").asBoolean());
        assertEquals("cpu_usage", json.path("metric").asText());
        assertEquals("1h", json.path("window").asText());
        assertFalse(json.path("points").isEmpty());
        assertEquals("increasing", json.path("summary").path("direction").asText());
        assertTrue(json.path("summary").path("anomalous").asBoolean());
        assertTrue(json.path("message").asText().contains("cpu_usage 最近 1h"));
    }

    @Test
    void queryMetricTrend_shouldReturnErrorForUnsupportedMetric() throws Exception {
        String result = tools.queryMetricTrend("disk_io", "payment-service", "instance-1", "1h", null);

        JsonNode json = objectMapper.readTree(result);
        assertFalse(json.path("success").asBoolean());
        assertTrue(json.path("message").asText().contains("不支持的指标"));
    }

    @Test
    void queryMetricTrend_shouldDefaultInvalidWindowToOneHour() throws Exception {
        String result = tools.queryMetricTrend("memory_usage", "order-service", "instance-1", "2h", null);

        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.path("success").asBoolean());
        assertEquals("1h", json.path("window").asText());
    }

    @Test
    void queryMetricTrend_shouldRecordEvidenceWhenDiagnosisContextExists() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper);
        ReflectionTestUtils.setField(tools, "diagnosisEvidenceRecorder", recorder);

        String result = recorder.withRun("inc-1", "run-1", () -> tools.queryMetricTrend(
                "cpu_usage", "payment-service", "pod-1", "15m", null));

        verify(incidentService).markRunWaitingTool(eq("inc-1"), eq("run-1"),
                eq("queryMetricTrend"), org.mockito.ArgumentMatchers.contains("\"metric\":\"cpu_usage\""));

        ArgumentCaptor<DiagnosisEvidence> evidenceCaptor = ArgumentCaptor.forClass(DiagnosisEvidence.class);
        verify(incidentService).addToolEvidence(eq("inc-1"), eq("run-1"), evidenceCaptor.capture());
        DiagnosisEvidence evidence = evidenceCaptor.getValue();
        assertEquals("tool_call", evidence.getType());
        assertEquals("queryMetricTrend", evidence.getToolName());
        assertEquals("15m", evidence.getTimeRange());

        JsonNode json = objectMapper.readTree(result);
        assertEquals(evidence.getId(), json.path("_diagnosisEvidenceId").asText());
    }
}
