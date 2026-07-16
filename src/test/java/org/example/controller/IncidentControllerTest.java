package org.example.controller;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.DiagnosisEvidence;
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
        summary.setLatestRunId("run-1");
        summary.setLatestRunStatus("COMPLETED");
        summary.setLatestRunQualityScore(92);
        summary.setLatestRunQualityGrade("HIGH");
        summary.setLatestRunHumanReviewStatus("CONFIRMED");

        when(incidentService.listIncidents("OPEN", "critical", "COMPLETED", "payment", "CONFIRMED", 0, 100))
                .thenReturn(List.of(summary));

        mockMvc.perform(get("/api/incidents")
                        .param("status", "OPEN")
                        .param("severity", "critical")
                        .param("latestRunStatus", "COMPLETED")
                        .param("q", "payment")
                        .param("humanReviewStatus", "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("incident-1"))
                .andExpect(jsonPath("$.data[0].title").value("HighCPUUsage payment-service"))
                .andExpect(jsonPath("$.data[0].latestRunStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].latestRunId").value("run-1"))
                .andExpect(jsonPath("$.data[0].latestRunQualityScore").value(92))
                .andExpect(jsonPath("$.data[0].latestRunHumanReviewStatus").value("CONFIRMED"));
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
    void diagnoseIncident_shouldCreateQueuedRunAndEnqueueDurableJob() throws Exception {
        IncidentRecord incident = new IncidentRecord();
        incident.setId("incident-1");
        incident.setTitle("HighCPUUsage payment-service");
        when(incidentService.getIncident("incident-1")).thenReturn(Optional.of(incident));
        when(incidentService.buildAlertContext(incident)).thenReturn("告警上下文");

        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-1");
        run.setIncidentId("incident-1");
        run.setStatus("QUEUED");
        when(incidentService.createDiagnosisRunAndEnqueue("incident-1", "告警上下文")).thenReturn(run);

        mockMvc.perform(post("/api/incidents/incident-1/diagnose"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(incidentService).createDiagnosisRunAndEnqueue("incident-1", "告警上下文");
        verify(executor, never()).execute(any(Runnable.class));
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

        verify(incidentService, never()).createDiagnosisRunAndEnqueue(eq("incident-1"), any());
        verify(executor, never()).execute(any(Runnable.class));
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
    void getRunEvidence_shouldReturnEvidenceForSpecificRun() throws Exception {
        DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                "queryMetricTrend",
                "{\"metric\":\"cpu_usage\"}",
                "1h",
                "CPU 上升",
                "{\"success\":true}",
                true,
                null,
                1L);
        when(incidentService.getRunEvidence("incident-1", "run-1")).thenReturn(Optional.of(List.of(evidence)));

        mockMvc.perform(get("/api/incidents/incident-1/runs/run-1/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].toolName").value("queryMetricTrend"))
                .andExpect(jsonPath("$.data[0].summary").value("CPU 上升"));
    }

    @Test
    void cancelRun_shouldDelegateToIncidentService() throws Exception {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-1");
        run.setIncidentId("incident-1");
        run.setStatus("CANCELLED");
        run.setErrorMessage("用户取消");
        when(incidentService.cancelRun("incident-1", "run-1", "用户取消")).thenReturn(run);

        mockMvc.perform(post("/api/incidents/incident-1/runs/run-1/cancel")
                        .param("reason", "用户取消"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.errorMessage").value("用户取消"));
    }

    @Test
    void confirmRun_shouldConfirmArchiveCaseAndReturnUpdatedRun() throws Exception {
        DiagnosisRunRecord confirmed = new DiagnosisRunRecord();
        confirmed.setRunId("run-1");
        confirmed.setIncidentId("incident-1");
        confirmed.setStatus("COMPLETED");
        confirmed.setHumanReviewStatus("CONFIRMED");
        when(incidentService.confirmRun("incident-1", "run-1", "根因准确")).thenReturn(confirmed);

        IncidentCaseService.ArchiveResult archiveResult = new IncidentCaseService.ArchiveResult();
        archiveResult.setSuccess(true);
        archiveResult.setIncidentId("incident-1");
        archiveResult.setDocumentId("doc-1");
        archiveResult.setMessage("历史案例已写入知识库");
        when(incidentCaseService.archiveCase("incident-1", "run-1")).thenReturn(archiveResult);

        DiagnosisRunRecord archived = new DiagnosisRunRecord();
        archived.setRunId("run-1");
        archived.setIncidentId("incident-1");
        archived.setStatus("COMPLETED");
        archived.setHumanReviewStatus("CONFIRMED");
        archived.setCaseArchived(true);
        archived.setCaseDocumentId("doc-1");
        when(incidentService.markRunCaseArchived(
                "incident-1", "run-1", true, "doc-1", "历史案例已写入知识库")).thenReturn(archived);

        mockMvc.perform(post("/api/incidents/incident-1/runs/run-1/confirm")
                        .param("comment", "根因准确"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.humanReviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.caseArchived").value(true))
                .andExpect(jsonPath("$.data.caseDocumentId").value("doc-1"));
    }

    @Test
    void rejectRun_shouldRejectWithoutArchiving() throws Exception {
        DiagnosisRunRecord rejected = new DiagnosisRunRecord();
        rejected.setRunId("run-1");
        rejected.setIncidentId("incident-1");
        rejected.setStatus("COMPLETED");
        rejected.setHumanReviewStatus("REJECTED");
        rejected.setHumanReviewComment("证据不足");
        when(incidentService.rejectRun("incident-1", "run-1", "证据不足")).thenReturn(rejected);

        mockMvc.perform(post("/api/incidents/incident-1/runs/run-1/reject")
                        .param("comment", "证据不足"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.humanReviewStatus").value("REJECTED"))
                .andExpect(jsonPath("$.data.humanReviewComment").value("证据不足"));

        verify(incidentCaseService, never()).archiveCase(any(), any());
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
