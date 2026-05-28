package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.dto.IncidentSummary;
import org.example.service.AiOpsService;
import org.example.service.IncidentCaseService;
import org.example.service.IncidentService;
import org.example.service.MetricTrendPrefetchService;
import org.example.service.VectorSearchService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
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

    @MockBean
    private IncidentCaseService incidentCaseService;

    @MockBean
    private MetricTrendPrefetchService metricTrendPrefetchService;

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
    void diagnoseIncident_shouldReuseCompletedReportAndSkipAsyncAnalysis() throws Exception {
        IncidentRecord incident = new IncidentRecord();
        incident.setId("incident-1");
        incident.setTitle("HighCPUUsage payment-service");
        when(incidentService.getIncident("incident-1")).thenReturn(Optional.of(incident));
        when(incidentService.buildAlertContext(incident)).thenReturn("告警上下文");

        DiagnosisRunRecord reused = new DiagnosisRunRecord();
        reused.setRunId("run-reuse");
        reused.setIncidentId("incident-1");
        reused.setStatus("COMPLETED");
        reused.setReport("# 已复用报告");
        reused.setReusedFromRunId("run-original");
        when(incidentService.createReusedDiagnosisRunIfAvailable("incident-1", "告警上下文"))
                .thenReturn(Optional.of(reused));

        mockMvc.perform(post("/api/incidents/incident-1/diagnose"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-reuse"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.reusedFromRunId").value("run-original"));

        verify(incidentService, never()).createDiagnosisRun(eq("incident-1"), any());
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void diagnoseIncident_shouldPrefetchSimilarCasesAndMetricTrendsBeforeAiOps() throws Exception {
        IncidentRecord incident = new IncidentRecord();
        incident.setId("incident-1");
        incident.setTitle("HighCPUUsage payment-service");
        when(incidentService.getIncident("incident-1")).thenReturn(Optional.of(incident));
        when(incidentService.buildAlertContext(incident)).thenReturn("基础告警上下文");

        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-1");
        run.setIncidentId("incident-1");
        run.setStatus("QUEUED");
        when(incidentService.createDiagnosisRun("incident-1", "基础告警上下文")).thenReturn(run);
        when(incidentService.getDiagnosisRuns("incident-1")).thenReturn(Optional.of(List.of(run)));
        when(incidentCaseService.prefetchAndAppend(incident, run, "基础告警上下文"))
                .thenReturn("基础告警上下文\n\n## 相似历史故障案例");
        when(metricTrendPrefetchService.prefetchAndAppend(incident, run, "基础告警上下文\n\n## 相似历史故障案例"))
                .thenReturn("基础告警上下文\n\n## 相似历史故障案例\n\n## 预取指标趋势证据");
        when(aiOpsService.executeAiOpsAnalysis(
                eq(dashScopeChatModelAiOps), any(),
                eq("基础告警上下文\n\n## 相似历史故障案例\n\n## 预取指标趋势证据"),
                eq("incident-1"), eq("run-1")))
                .thenReturn(Optional.empty());
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        mockMvc.perform(post("/api/incidents/incident-1/diagnose"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-1"));

        verify(incidentCaseService).prefetchAndAppend(incident, run, "基础告警上下文");
        verify(metricTrendPrefetchService).prefetchAndAppend(incident, run, "基础告警上下文\n\n## 相似历史故障案例");
        verify(incidentService).updateRunAlertContext("incident-1", "run-1",
                "基础告警上下文\n\n## 相似历史故障案例");
        verify(incidentService).updateRunAlertContext("incident-1", "run-1",
                "基础告警上下文\n\n## 相似历史故障案例\n\n## 预取指标趋势证据");
        verify(aiOpsService).executeAiOpsAnalysis(
                eq(dashScopeChatModelAiOps), any(),
                eq("基础告警上下文\n\n## 相似历史故障案例\n\n## 预取指标趋势证据"),
                eq("incident-1"), eq("run-1"));
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

    @Test
    void archiveCase_shouldDelegateToIncidentCaseService() throws Exception {
        IncidentCaseService.ArchiveResult result = new IncidentCaseService.ArchiveResult();
        result.setSuccess(true);
        result.setIncidentId("incident-1");
        result.setMessage("历史案例已写入知识库");
        when(incidentCaseService.archiveCase("incident-1")).thenReturn(result);

        mockMvc.perform(post("/api/incidents/incident-1/archive-case"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.incidentId").value("incident-1"));
    }

    @Test
    void similarCases_shouldReturnIncidentCaseSearchResults() throws Exception {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId("case-1");
        result.setContent("历史案例");
        result.setScore(0.82f);
        result.setMetadata("{\"doc_type\":\"incident_case\"}");
        when(incidentCaseService.findSimilarCases("incident-1", 3)).thenReturn(List.of(result));

        mockMvc.perform(get("/api/incidents/incident-1/similar-cases?topK=3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("case-1"))
                .andExpect(jsonPath("$.data[0].score").value(0.82));
    }
}
