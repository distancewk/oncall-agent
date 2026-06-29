package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppIncidentProperties;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.exception.DependencyUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void wrapToolCallbacks_shouldRecordExternalToolWhenDefinitionIsMissing() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper);
        ToolCallback missingDefinition = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return null;
            }

            @Override
            public String call(String toolInput) {
                return "{\"success\":false,\"errorCode\":\"CIRCUIT_OPEN\",\"message\":\"MCP unavailable\"}";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };

        ToolCallback wrapped = recorder.wrapToolCallbacks(new ToolCallback[]{missingDefinition})[0];
        String result = recorder.withRun("inc-1", "run-1", () -> wrapped.call("{\"query\":\"spring\"}"));

        ArgumentCaptor<DiagnosisEvidence> evidenceCaptor = ArgumentCaptor.forClass(DiagnosisEvidence.class);
        verify(incidentService).addToolEvidence(eq("inc-1"), eq("run-1"), evidenceCaptor.capture());
        DiagnosisEvidence evidence = evidenceCaptor.getValue();

        assertEquals("externalTool", evidence.getToolName());
        assertEquals("CIRCUIT_OPEN", evidence.getErrorCode());
        JsonNode json = objectMapper.readTree(result);
        assertEquals(evidence.getId(), json.path("_diagnosisEvidenceId").asText());
    }

    @Test
    void recordToolCall_shouldPersistAttemptCountAndDuration() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        ToolCallAttemptContext attemptContext = new ToolCallAttemptContext();
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(
                incidentService, objectMapper, new org.example.config.AppIncidentProperties(), attemptContext);

        String result = recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "1h",
                () -> {
                    attemptContext.recordAttempts(3);
                    Thread.sleep(2);
                    return "{\"success\":true,\"message\":\"趋势查询成功\"}";
                }
        ));

        ArgumentCaptor<DiagnosisEvidence> evidenceCaptor = ArgumentCaptor.forClass(DiagnosisEvidence.class);
        verify(incidentService).addToolEvidence(eq("inc-1"), eq("run-1"), evidenceCaptor.capture());
        DiagnosisEvidence evidence = evidenceCaptor.getValue();

        assertEquals(3, evidence.getAttemptCount());
        assertTrue(evidence.getDurationMs() >= 0);
        assertTrue(evidence.isRetryable());

        JsonNode json = objectMapper.readTree(result);
        assertEquals(3, json.path("_diagnosisEvidenceAttemptCount").asInt());
        assertTrue(json.path("_diagnosisEvidenceDurationMs").asLong() >= 0);
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

    @Test
    void recordToolCall_shouldSkipDuplicateToolCallWithoutInvokingSupplier() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        when(incidentService.getDiagnosisRuns("inc-1")).thenReturn(OptionalRun.with(evidence(
                "queryLogs", "{\"query\":\"level:ERROR\",\"logTopic\":\"application-logs\"}")));
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper);
        AtomicInteger calls = new AtomicInteger();

        String result = recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "queryLogs",
                "{\"logTopic\":\"application-logs\",\"query\":\"level:ERROR\"}",
                "recent logs",
                () -> {
                    calls.incrementAndGet();
                    return "{\"success\":true}";
                }
        ));

        assertEquals(0, calls.get());
        JsonNode json = objectMapper.readTree(result);
        assertFalse(json.path("success").asBoolean());
        assertEquals("TOOL_DUPLICATE_SKIPPED", json.path("errorCode").asText());
        assertTrue(json.path("message").asText().contains("重复工具调用"));
        verify(incidentService, never()).markRunWaitingTool(any(), any(), any(), any());

        ArgumentCaptor<DiagnosisEvidence> evidenceCaptor = ArgumentCaptor.forClass(DiagnosisEvidence.class);
        verify(incidentService).addToolEvidence(eq("inc-1"), eq("run-1"), evidenceCaptor.capture());
        assertEquals("TOOL_DUPLICATE_SKIPPED", evidenceCaptor.getValue().getErrorCode());
        assertFalse(evidenceCaptor.getValue().isSuccess());
    }

    @Test
    void recordToolCall_shouldSkipSemanticallyDuplicateMetricTrendCalls() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        when(incidentService.getDiagnosisRuns("inc-1")).thenReturn(OptionalRun.with(evidence(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\",\"service\":\"payment-service\",\"instance\":\"pod-1\","
                        + "\"window\":\"1h\",\"step\":\"1m\"}")));
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper);
        AtomicInteger calls = new AtomicInteger();

        String result = recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "queryMetricTrend",
                "{\"instance\":\"pod-1\",\"service\":\"payment-service\",\"metric\":\"cpu_usage\","
                        + "\"window\":\"1h\",\"step\":\"5m\"}",
                "1h",
                () -> {
                    calls.incrementAndGet();
                    return "{\"success\":true}";
                }
        ));

        assertEquals(0, calls.get());
        JsonNode json = objectMapper.readTree(result);
        assertEquals("TOOL_DUPLICATE_SKIPPED", json.path("errorCode").asText());
        verify(incidentService, never()).markRunWaitingTool(any(), any(), any(), any());
    }

    @Test
    void recordToolCall_shouldEnforceQueryLogsBudget() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        when(incidentService.getDiagnosisRuns("inc-1")).thenReturn(OptionalRun.with(
                evidence("queryLogs", "{\"query\":\"cpu\"}"),
                evidence("queryLogs", "{\"query\":\"error\"}"),
                evidence("queryLogs", "{\"query\":\"event\"}")
        ));
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setQueryLogsMaxCallsPerRun(3);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper, properties);
        AtomicInteger calls = new AtomicInteger();

        String result = recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "queryLogs",
                "{\"query\":\"another\"}",
                "recent logs",
                () -> {
                    calls.incrementAndGet();
                    return "{\"success\":true}";
                }
        ));

        assertEquals(0, calls.get());
        JsonNode json = objectMapper.readTree(result);
        assertFalse(json.path("success").asBoolean());
        assertEquals("TOOL_BUDGET_EXCEEDED", json.path("errorCode").asText());
        assertTrue(json.path("message").asText().contains("queryLogs 调用次数已达上限"));
        verify(incidentService, never()).markRunWaitingTool(any(), any(), any(), any());
    }

    @Test
    void recordToolCall_shouldIgnorePolicySkippedEvidenceWhenCountingBudgets() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        when(incidentService.getDiagnosisRuns("inc-1")).thenReturn(OptionalRun.with(
                evidence("queryLogs", "{\"query\":\"cpu\"}"),
                evidence("queryLogs", "{\"query\":\"error\"}"),
                skippedEvidence("queryLogs", "{\"query\":\"skipped\"}", "TOOL_BUDGET_EXCEEDED")
        ));
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setQueryLogsMaxCallsPerRun(3);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper, properties);
        AtomicInteger calls = new AtomicInteger();

        String result = recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "queryLogs",
                "{\"query\":\"another\"}",
                "recent logs",
                () -> {
                    calls.incrementAndGet();
                    return "{\"success\":true}";
                }
        ));

        assertEquals(1, calls.get());
        JsonNode json = objectMapper.readTree(result);
        assertTrue(json.path("success").asBoolean());
        verify(incidentService).markRunWaitingTool(eq("inc-1"), eq("run-1"), eq("queryLogs"), eq("{\"query\":\"another\"}"));
    }

    @Test
    void recordToolCall_shouldCountRealToolEvidenceWithPolicyLikeErrorCode() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        DiagnosisEvidence realFailedToolEvidence = evidence("queryLogs", "{\"query\":\"cpu\"}");
        realFailedToolEvidence.setSuccess(false);
        realFailedToolEvidence.setErrorCode("TOOL_BUDGET_EXCEEDED");
        realFailedToolEvidence.setAttemptCount(1);
        when(incidentService.getDiagnosisRuns("inc-1")).thenReturn(OptionalRun.with(realFailedToolEvidence));
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setQueryLogsMaxCallsPerRun(1);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper, properties);
        AtomicInteger calls = new AtomicInteger();

        String result = recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "queryLogs",
                "{\"query\":\"another\"}",
                "recent logs",
                () -> {
                    calls.incrementAndGet();
                    return "{\"success\":true}";
                }
        ));

        assertEquals(0, calls.get());
        JsonNode json = objectMapper.readTree(result);
        assertEquals("TOOL_BUDGET_EXCEEDED", json.path("errorCode").asText());
    }

    @Test
    void recordToolCall_shouldEnforceQueryInternalDocsBudget() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        when(incidentService.getDiagnosisRuns("inc-1")).thenReturn(OptionalRun.with(
                evidence("queryInternalDocs", "{\"query\":\"cpu\"}")
        ));
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setQueryInternalDocsMaxCallsPerRun(1);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper, properties);
        AtomicInteger calls = new AtomicInteger();

        String result = recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "queryInternalDocs",
                "{\"query\":\"memory\"}",
                "docs",
                () -> {
                    calls.incrementAndGet();
                    return "{\"success\":true}";
                }
        ));

        assertEquals(0, calls.get());
        JsonNode json = objectMapper.readTree(result);
        assertFalse(json.path("success").asBoolean());
        assertEquals("TOOL_BUDGET_EXCEEDED", json.path("errorCode").asText());
        verify(incidentService, never()).markRunWaitingTool(any(), any(), any(), any());
    }

    @Test
    void recordToolCall_shouldEnforceTavilyBudgetByToolNameContainingTavily() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        when(incidentService.getDiagnosisRuns("inc-1")).thenReturn(OptionalRun.with(
                evidence("tavily_search", "{\"query\":\"error code\"}")
        ));
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setTavilyMaxCallsPerRun(1);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper, properties);
        AtomicInteger calls = new AtomicInteger();

        String result = recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "tavily_search",
                "{\"query\":\"version\"}",
                "external",
                () -> {
                    calls.incrementAndGet();
                    return "{\"success\":true}";
                }
        ));

        assertEquals(0, calls.get());
        JsonNode json = objectMapper.readTree(result);
        assertFalse(json.path("success").asBoolean());
        assertEquals("TOOL_BUDGET_EXCEEDED", json.path("errorCode").asText());
        verify(incidentService, never()).markRunWaitingTool(any(), any(), any(), any());
    }

    @Test
    void recordToolCall_shouldEnforceDbhubBudgetByToolNameAliases() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        when(incidentService.getDiagnosisRuns("inc-1")).thenReturn(OptionalRun.with(
                evidence("execute_sql", "{\"sql\":\"select 1\"}"),
                evidence("list_tables", "{}")
        ));
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setDbhubMaxCallsPerRun(2);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper, properties);
        AtomicInteger calls = new AtomicInteger();

        String result = recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "describe_table",
                "{\"table\":\"orders\"}",
                "dbhub",
                () -> {
                    calls.incrementAndGet();
                    return "{\"success\":true}";
                }
        ));

        assertEquals(0, calls.get());
        JsonNode json = objectMapper.readTree(result);
        assertFalse(json.path("success").asBoolean());
        assertEquals("TOOL_BUDGET_EXCEEDED", json.path("errorCode").asText());
        verify(incidentService, never()).markRunWaitingTool(any(), any(), any(), any());
    }

    @Test
    void recordToolCall_shouldEnforceGlobalToolBudget() throws Exception {
        IncidentService incidentService = mock(IncidentService.class);
        when(incidentService.getDiagnosisRuns("inc-1")).thenReturn(OptionalRun.with(
                evidence("queryMetricTrend", "{\"metric\":\"cpu_usage\"}"),
                evidence("queryLogs", "{\"query\":\"cpu\"}")
        ));
        AppIncidentProperties properties = new AppIncidentProperties();
        properties.setMaxToolCallsPerRun(2);
        DiagnosisEvidenceRecorder recorder = new DiagnosisEvidenceRecorder(incidentService, objectMapper, properties);
        AtomicInteger calls = new AtomicInteger();

        String result = recorder.withRun("inc-1", "run-1", () -> recorder.recordToolCall(
                "queryInternalDocs",
                "{\"query\":\"cpu runbook\"}",
                "knowledge",
                () -> {
                    calls.incrementAndGet();
                    return "{\"success\":true}";
                }
        ));

        assertEquals(0, calls.get());
        JsonNode json = objectMapper.readTree(result);
        assertFalse(json.path("success").asBoolean());
        assertEquals("TOOL_BUDGET_EXCEEDED", json.path("errorCode").asText());
        assertTrue(json.path("message").asText().contains("工具调用总次数已达上限"));
        verify(incidentService, never()).markRunWaitingTool(any(), any(), any(), any());
    }

    private DiagnosisEvidence evidence(String toolName, String queryParams) {
        return DiagnosisEvidence.toolCall(
                toolName,
                queryParams,
                "recent logs",
                "ok",
                "{\"success\":true}",
                true,
                null,
                System.currentTimeMillis());
    }

    private DiagnosisEvidence skippedEvidence(String toolName, String queryParams, String errorCode) {
        DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                toolName,
                queryParams,
                "recent logs",
                "skipped",
                "{\"success\":false}",
                false,
                "skipped",
                System.currentTimeMillis());
        evidence.setErrorCode(errorCode);
        evidence.setAttemptCount(0);
        return evidence;
    }

    private static final class OptionalRun {
        private OptionalRun() {
        }

        private static java.util.Optional<List<DiagnosisRunRecord>> with(DiagnosisEvidence... evidence) {
            DiagnosisRunRecord run = new DiagnosisRunRecord();
            run.setRunId("run-1");
            run.getEvidence().addAll(List.of(evidence));
            return java.util.Optional.of(List.of(run));
        }
    }
}
