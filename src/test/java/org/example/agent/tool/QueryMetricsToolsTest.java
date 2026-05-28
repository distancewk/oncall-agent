package org.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.config.AppResilienceProperties;
import org.example.dto.DiagnosisEvidence;
import org.example.service.DependencyGuard;
import org.example.service.DiagnosisEvidenceRecorder;
import org.example.service.IncidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void queryMetricTrend_shouldReturnCircuitOpenAfterGuardedPrometheusFailure() throws Exception {
        ReflectionTestUtils.setField(tools, "mockEnabled", false);
        ReflectionTestUtils.setField(tools, "httpClient", null);
        ReflectionTestUtils.setField(tools, "dependencyGuard", singleFailureGuard());

        JsonNode first = objectMapper.readTree(tools.queryMetricTrend(
                "cpu_usage", "payment-service", "pod-1", "15m", null));
        assertFalse(first.path("success").asBoolean());
        assertEquals("DEPENDENCY_ERROR", first.path("errorCode").asText());

        JsonNode second = objectMapper.readTree(tools.queryMetricTrend(
                "cpu_usage", "payment-service", "pod-1", "15m", null));
        assertFalse(second.path("success").asBoolean());
        assertEquals("CIRCUIT_OPEN", second.path("errorCode").asText());
        assertTrue(second.path("message").asText().contains("Prometheus"));
        assertTrue(second.path("message").asText().contains("熔断"));
        assertTrue(second.path("message").asText().contains("证据缺失"));
    }

    @Test
    void queryPrometheusAlerts_shouldOpenCircuitAfterPrometheusSemanticFailure() throws Exception {
        OkHttpClient httpClient = mock(OkHttpClient.class);
        Call firstCall = mock(Call.class);
        Call secondCall = mock(Call.class);
        when(httpClient.newCall(any(Request.class))).thenReturn(firstCall, secondCall);
        when(firstCall.execute()).thenReturn(prometheusErrorResponse());
        when(secondCall.execute()).thenReturn(prometheusErrorResponse());

        ReflectionTestUtils.setField(tools, "mockEnabled", false);
        ReflectionTestUtils.setField(tools, "httpClient", httpClient);
        ReflectionTestUtils.setField(tools, "dependencyGuard", singleFailureGuard());

        JsonNode first = objectMapper.readTree(tools.queryPrometheusAlerts());
        assertFalse(first.path("success").asBoolean());
        assertEquals("DEPENDENCY_ERROR", first.path("errorCode").asText());

        JsonNode second = objectMapper.readTree(tools.queryPrometheusAlerts());
        assertFalse(second.path("success").asBoolean());
        assertEquals("CIRCUIT_OPEN", second.path("errorCode").asText());
        assertTrue(second.path("message").asText().contains("Prometheus"));
        assertTrue(second.path("message").asText().contains("熔断"));
        assertTrue(second.path("message").asText().contains("证据缺失"));
    }

    private DependencyGuard singleFailureGuard() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(1);
        properties.getDefaultConfig().setFailureRateThreshold(1.0f);
        properties.getDefaultConfig().setOpenDuration(Duration.ofSeconds(30));
        return new DependencyGuard(properties);
    }

    private Response prometheusErrorResponse() {
        Request request = new Request.Builder()
                .url("http://localhost:9090/api/v1/alerts")
                .build();
        ResponseBody body = ResponseBody.create(
                "{\"status\":\"error\",\"error\":\"bad query\"}",
                MediaType.get("application/json"));
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build();
    }
}
