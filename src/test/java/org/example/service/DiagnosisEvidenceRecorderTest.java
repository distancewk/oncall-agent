package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.DiagnosisEvidence;
import org.example.exception.DependencyUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DiagnosisEvidenceRecorderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void recordToolCall_shouldPersistProgressEvidenceAndReturnEvidenceId() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper);

        String result = recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "queryPrometheusAlerts",
                "{\"endpoint\":\"/api/v1/alerts\"}",
                "active alerts",
                () -> "{\"success\":true,\"message\":\"成功检索到 2 个活动告警\"}"
        ));

        verify(incidentService).markRunWaitingTool("inc-1", "run-1",
                "queryPrometheusAlerts", "{\"endpoint\":\"/api/v1/alerts\"}");

        ArgumentCaptor<DiagnosisEvidence> evidenceCaptor = ArgumentCaptor.forClass(DiagnosisEvidence.class);
        verify(incidentService).addToolEvidence(eq("inc-1"), eq("run-1"), evidenceCaptor.capture());
        DiagnosisEvidence evidence = evidenceCaptor.getValue();

        assertNotNull(evidence.getId());
        assertEquals("tool_call", evidence.getType());
        assertEquals("queryPrometheusAlerts", evidence.getToolName());
        assertEquals("{\"endpoint\":\"/api/v1/alerts\"}", evidence.getQueryParams());
        assertEquals("active alerts", evidence.getTimeRange());
        assertTrue(evidence.isSuccess());
        assertTrue(evidence.getSummary().contains("成功检索到 2 个活动告警"));

        JsonNode json = objectMapper.readTree(result);
        assertEquals(evidence.getId(), json.path("_diagnosisEvidenceId").asText());
        assertTrue(json.path("_diagnosisEvidenceSuccess").asBoolean());
    }

    @Test
    void recordToolCall_withoutActiveRunShouldJustCallSupplier() {
        IncidentService incidentService = mock(IncidentService.class);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper);

        String result = recorder.recordToolCall("queryLogs", "{}", "recent", () -> "plain text");

        assertEquals("plain text", result);
    }

    @Test
    void recordToolCall_shouldCaptureErrorCodeFromFailedJsonResult() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper);

        recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "1h",
                () -> "{\"success\":false,\"errorCode\":\"CIRCUIT_OPEN\",\"message\":\"Prometheus 熔断\"}"
        ));

        ArgumentCaptor<DiagnosisEvidence> evidenceCaptor = ArgumentCaptor.forClass(DiagnosisEvidence.class);
        verify(incidentService).addToolEvidence(eq("inc-1"), eq("run-1"), evidenceCaptor.capture());
        DiagnosisEvidence evidence = evidenceCaptor.getValue();

        assertEquals("CIRCUIT_OPEN", evidence.getErrorCode());
        assertFalse(evidence.isSuccess());
    }

    @Test
    void recordToolCall_shouldCaptureErrorCodeFromDependencyUnavailableException() {
        IncidentService incidentService = mock(IncidentService.class);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper);

        DependencyUnavailableException thrown = assertThrows(DependencyUnavailableException.class,
                () -> recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                        "queryMetricTrend",
                        "{\"metric\":\"cpu_usage\"}",
                        "1h",
                        () -> {
                            throw new DependencyUnavailableException(
                                    "prometheus", "queryMetricTrend", "CIRCUIT_OPEN", null);
                        }
                )));

        ArgumentCaptor<DiagnosisEvidence> evidenceCaptor = ArgumentCaptor.forClass(DiagnosisEvidence.class);
        verify(incidentService).addToolEvidence(eq("inc-1"), eq("run-1"), evidenceCaptor.capture());
        DiagnosisEvidence evidence = evidenceCaptor.getValue();

        assertEquals("CIRCUIT_OPEN", thrown.getErrorCode());
        assertEquals("CIRCUIT_OPEN", evidence.getErrorCode());
        assertFalse(evidence.isSuccess());
    }
}
