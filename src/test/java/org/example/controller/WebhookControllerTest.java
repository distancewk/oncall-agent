package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.service.AiOpsService;
import org.example.service.AlertService;
import org.example.service.IncidentService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookControllerTest {

    @Test
    void receiveAlert_shouldCreateIncidentDiagnosisRunAndMarkFailedWhenAiOpsReturnsNoState() throws Exception {
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
        when(incidentService.recordAlert(payload)).thenReturn(incident);
        when(alertService.storeAlert(payload, "incident-1")).thenReturn("alert-1");
        when(incidentService.buildAlertContext(incident)).thenReturn("告警上下文");
        when(incidentService.createDiagnosisRun("incident-1", "告警上下文")).thenReturn(run);
        when(aiOpsService.executeAiOpsAnalysis(eq(chatModel), any(), eq("告警上下文")))
                .thenReturn(Optional.empty());

        WebhookController controller = new WebhookController();
        ReflectionTestUtils.setField(controller, "aiOpsService", aiOpsService);
        ReflectionTestUtils.setField(controller, "alertService", alertService);
        ReflectionTestUtils.setField(controller, "incidentService", incidentService);
        ReflectionTestUtils.setField(controller, "dashScopeChatModel", chatModel);
        ReflectionTestUtils.setField(controller, "tools", tools);
        Executor directExecutor = Runnable::run;
        ReflectionTestUtils.setField(controller, "executor", directExecutor);

        ResponseEntity<String> response = controller.receiveAlert(payload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(incidentService).markRunRunning("incident-1", "run-1");
        verify(incidentService).failRun("incident-1", "run-1", "告警分析执行失败，未获取到有效结果");
        verify(alertService).storeReport("alert-1", "告警分析执行失败，未获取到有效结果");
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
