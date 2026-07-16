package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.service.AiOpsService;
import org.example.service.AlertService;
import org.example.service.AlertIdentity;
import org.example.service.IncidentService;
import org.example.service.IncidentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookControllerTest {

    @Test
    void receiveAlert_shouldCreateIncidentAndEnqueueDiagnosisWithoutDirectAiOpsCall() throws Exception {
        AiOpsService aiOpsService = mock(AiOpsService.class);
        AlertService alertService = mock(AlertService.class);
        IncidentService incidentService = mock(IncidentService.class);
        DashScopeChatModel chatModel = mock(DashScopeChatModel.class);
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);

        IncidentRecord incident = new IncidentRecord();
        incident.setId("incident-1");
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-1");
        run.setIncidentId("incident-1");

        AlertPayload payload = alertPayload();
        String alertId = AlertIdentity.id(payload, new ObjectMapper());
        when(incidentService.recordAlertWithStatus(any(), any()))
                .thenReturn(new IncidentStore.RecordAlertResult(incident, true));
        when(incidentService.buildAlertContext(incident)).thenReturn("告警上下文");
        when(incidentService.createDiagnosisRunAndEnqueue(
                "incident-1", "告警上下文", alertId)).thenReturn(run);

        WebhookController controller = new WebhookController();
        ReflectionTestUtils.setField(controller, "alertService", alertService);
        ReflectionTestUtils.setField(controller, "incidentService", incidentService);
        ResponseEntity<String> response = controller.receiveAlert(payload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(incidentService).createDiagnosisRunAndEnqueue(
                "incident-1", "告警上下文", alertId);
        verify(aiOpsService, never()).executeAiOpsAnalysis(any(), any(), any(), any(), any());
    }

    @Test
    void receiveAlert_shouldReuseCompletedReportAndSkipAiOpsWhenSameIncidentHasReusableRun() throws Exception {
        AiOpsService aiOpsService = mock(AiOpsService.class);
        AlertService alertService = mock(AlertService.class);
        IncidentService incidentService = mock(IncidentService.class);
        DashScopeChatModel chatModel = mock(DashScopeChatModel.class);
        ToolCallbackProvider tools = mock(ToolCallbackProvider.class);

        IncidentRecord incident = new IncidentRecord();
        incident.setId("incident-1");
        DiagnosisRunRecord reused = new DiagnosisRunRecord();
        reused.setRunId("run-reuse");
        reused.setIncidentId("incident-1");
        reused.setStatus("COMPLETED");
        reused.setReport("# 复用报告");
        reused.setReusedFromRunId("run-original");

        AlertPayload payload = alertPayload();
        String alertId = AlertIdentity.id(payload, new ObjectMapper());
        when(incidentService.recordAlertWithStatus(any(), any()))
                .thenReturn(new IncidentStore.RecordAlertResult(incident, true));
        when(incidentService.buildAlertContext(incident)).thenReturn("告警上下文");
        when(incidentService.createReusedDiagnosisRunIfAvailable("incident-1", "告警上下文"))
                .thenReturn(Optional.of(reused));

        WebhookController controller = new WebhookController();
        ReflectionTestUtils.setField(controller, "alertService", alertService);
        ReflectionTestUtils.setField(controller, "incidentService", incidentService);

        ResponseEntity<String> response = controller.receiveAlert(payload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Alert received and reused existing diagnosis report.", response.getBody());
        verify(alertService).storeReport(alertId, "# 复用报告");
        verify(incidentService, never()).createDiagnosisRunAndEnqueue(eq("incident-1"), any(), any());
        verify(aiOpsService, never()).executeAiOpsAnalysis(any(), any(), any(), any(), any());
    }

    private AlertPayload alertPayload() {
        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setStatus("firing");
        alert.setFingerprint("fp-webhook-1");
        alert.setStartsAt("2026-05-13T00:00:00Z");
        alert.setLabels(Map.of(
                "alertname", "HighCPUUsage",
                "severity", "critical",
                "service", "payment-service"
        ));
        alert.setAnnotations(Map.of("summary", "HighCPUUsage payment-service"));

        AlertPayload payload = new AlertPayload();
        payload.setReceiver("webhook");
        payload.setStatus("firing");
        payload.setCommonLabels(Map.of("severity", "critical"));
        payload.setCommonAnnotations(Map.of("summary", "HighCPUUsage payment-service"));
        payload.setAlerts(List.of(alert));
        return payload;
    }
}
