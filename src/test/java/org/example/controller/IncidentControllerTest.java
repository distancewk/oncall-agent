package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.dto.IncidentSummary;
import org.example.service.AiOpsService;
import org.example.service.IncidentService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IncidentController.class)
class IncidentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IncidentService incidentService;

    @MockBean
    private AiOpsService aiOpsService;

    @MockBean(name = "dashScopeChatModelAiOps")
    private DashScopeChatModel dashScopeChatModelAiOps;

    @MockBean
    private ToolCallbackProvider tools;

    @MockBean(name = "chatTaskExecutor")
    private Executor executor;

    @Test
    void listIncidents_shouldReturnIncidentSummaries() throws Exception {
        IncidentSummary summary = new IncidentSummary();
        summary.setId("incident-1");
        summary.setTitle("HighCPUUsage payment-service");
        summary.setStatus("OPEN");
        summary.setSeverity("critical");
        summary.setAlertCount(2);
        summary.setLatestRunStatus("COMPLETED");

        when(incidentService.listIncidents()).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("incident-1"))
                .andExpect(jsonPath("$.data[0].title").value("HighCPUUsage payment-service"))
                .andExpect(jsonPath("$.data[0].latestRunStatus").value("COMPLETED"));
    }

    @Test
    void getIncident_shouldReturnIncidentDetail() throws Exception {
        IncidentRecord incident = new IncidentRecord();
        incident.setId("incident-1");
        incident.setTitle("HighCPUUsage payment-service");
        incident.setStatus("OPEN");
        incident.setAlertCount(1);

        when(incidentService.getIncident("incident-1")).thenReturn(Optional.of(incident));

        mockMvc.perform(get("/api/incidents/incident-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("incident-1"))
                .andExpect(jsonPath("$.data.title").value("HighCPUUsage payment-service"));
    }

    @Test
    void diagnoseIncident_shouldCreateQueuedRunAndStartAsyncAnalysis() throws Exception {
        IncidentRecord incident = new IncidentRecord();
        incident.setId("incident-1");
        incident.setTitle("HighCPUUsage payment-service");
        when(incidentService.getIncident("incident-1")).thenReturn(Optional.of(incident));
        when(incidentService.buildAlertContext(incident)).thenReturn("告警上下文");

        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-1");
        run.setIncidentId("incident-1");
        run.setStatus("QUEUED");
        when(incidentService.createDiagnosisRun("incident-1", "告警上下文")).thenReturn(run);

        mockMvc.perform(post("/api/incidents/incident-1/diagnose"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void listRuns_shouldReturnDiagnosisRunsForIncident() throws Exception {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-1");
        run.setIncidentId("incident-1");
        run.setStatus("FAILED");
        run.setErrorMessage("Prometheus 查询失败");
        when(incidentService.getDiagnosisRuns("incident-1")).thenReturn(Optional.of(List.of(run)));

        mockMvc.perform(get("/api/incidents/incident-1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].runId").value("run-1"))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].errorMessage").value("Prometheus 查询失败"));
    }
}
