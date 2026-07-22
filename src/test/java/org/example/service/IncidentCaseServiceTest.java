package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.UpsertParam;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.exception.DependencyUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentCaseServiceTest {

    @Test
    void archiveCase_shouldInsertIncidentCaseDocumentWithStructuredMetadata() {
        IncidentService incidentService = mock(IncidentService.class);
        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);
        VectorSearchService vectorSearchService = mock(VectorSearchService.class);

        IncidentRecord incident = incident("incident-1");
        DiagnosisRunRecord run = completedRun("run-1", """
                # 告警分析报告

                ### 根因结论
                JVM GC 风暴导致 CPU 升高。

                ### 处理建议
                重启服务并分析 heap dump。
                """);
        run.setHumanReviewStatus("CONFIRMED");
        incident.getDiagnosisRuns().add(run);
        when(incidentService.getIncident("incident-1")).thenReturn(Optional.of(incident));
        when(embeddingService.generateEmbedding(any())).thenReturn(List.of(0.1f, 0.2f));
        when(embeddingService.generateSparseVector(any())).thenReturn(new TreeMap<>(Map.of(1L, 1.0f)));

        @SuppressWarnings("unchecked")
        R<MutationResult> mutationResponse = mock(R.class);
        when(mutationResponse.getStatus()).thenReturn(0);
        when(milvusClient.upsert(any(UpsertParam.class))).thenReturn(mutationResponse);

        IncidentCaseService service = new IncidentCaseService(
                incidentService, milvusClient, embeddingService, vectorSearchService, new MilvusInsertHelper());

        IncidentCaseService.ArchiveResult result = service.archiveCase("incident-1");

        assertTrue(result.isSuccess());
        assertEquals("incident-1", result.getIncidentId());

        ArgumentCaptor<UpsertParam> upsertCaptor = ArgumentCaptor.forClass(UpsertParam.class);
        verify(milvusClient).upsert(upsertCaptor.capture());
        UpsertParam upsertParam = upsertCaptor.getValue();

        assertEquals(1, upsertParam.getFields().get(0).getValues().size());
        String content = fieldValues(upsertParam.getFields(), "content").get(0).toString();
        String metadata = fieldValues(upsertParam.getFields(), "metadata").get(0).toString();
        assertTrue(content.contains("JVM GC 风暴导致 CPU 升高"));
        assertTrue(content.contains("重启服务并分析 heap dump"));
        assertTrue(metadata.contains("\"doc_type\":\"incident_case\""));
        assertTrue(metadata.contains("\"incident_id\":\"incident-1\""));
        assertTrue(metadata.contains("\"alertname\":\"HighCPUUsage\""));
        assertTrue(metadata.contains("\"service\":\"payment-service\""));
        assertTrue(metadata.contains("\"instance\":\"payment-service-pod-1\""));
        assertTrue(metadata.contains("\"root_cause\":\"JVM GC 风暴导致 CPU 升高。\""));
    }

    @Test
    void archiveCaseWithRunId_shouldRequireConfirmedRunAndInsertThatRun() {
        IncidentService incidentService = mock(IncidentService.class);
        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);
        VectorSearchService vectorSearchService = mock(VectorSearchService.class);

        IncidentRecord incident = incident("incident-4");
        DiagnosisRunRecord run = completedRun("run-confirmed", """
                # 告警分析报告

                ### 根因结论
                CPU 线程池耗尽。

                ### 处理建议
                扩容实例并调整线程池。
                """);
        run.setHumanReviewStatus("CONFIRMED");
        incident.getDiagnosisRuns().add(run);
        when(incidentService.getIncident("incident-4")).thenReturn(Optional.of(incident));
        when(embeddingService.generateEmbedding(any())).thenReturn(List.of(0.1f, 0.2f));
        when(embeddingService.generateSparseVector(any())).thenReturn(new TreeMap<>(Map.of(1L, 1.0f)));

        @SuppressWarnings("unchecked")
        R<MutationResult> mutationResponse = mock(R.class);
        when(mutationResponse.getStatus()).thenReturn(0);
        when(milvusClient.upsert(any(UpsertParam.class))).thenReturn(mutationResponse);

        IncidentCaseService service = new IncidentCaseService(
                incidentService, milvusClient, embeddingService, vectorSearchService, new MilvusInsertHelper());

        IncidentCaseService.ArchiveResult result = service.archiveCase("incident-4", "run-confirmed");

        assertTrue(result.isSuccess());
        assertEquals("incident-4", result.getIncidentId());
        ArgumentCaptor<UpsertParam> upsertCaptor = ArgumentCaptor.forClass(UpsertParam.class);
        verify(milvusClient).upsert(upsertCaptor.capture());
        String metadata = fieldValues(upsertCaptor.getValue().getFields(), "metadata").get(0).toString();
        assertTrue(metadata.contains("\"run_id\":\"run-confirmed\""));
        assertTrue(metadata.contains("\"human_review_status\":\"CONFIRMED\""));
    }

    @Test
    void archiveCaseWithRunId_shouldRejectUnconfirmedRun() {
        IncidentService incidentService = mock(IncidentService.class);
        IncidentRecord incident = incident("incident-5");
        incident.getDiagnosisRuns().add(completedRun("run-unreviewed", "# 报告"));
        when(incidentService.getIncident("incident-5")).thenReturn(Optional.of(incident));
        IncidentCaseService service = new IncidentCaseService(
                incidentService, mock(MilvusServiceClient.class), mock(VectorEmbeddingService.class),
                mock(VectorSearchService.class), new MilvusInsertHelper());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.archiveCase("incident-5", "run-unreviewed"));

        assertTrue(exception.getMessage().contains("未被人工确认"));
    }

    @Test
    void prefetchAndAppend_shouldRecordSimilarCasesAsToolEvidenceAndAppendContext() {
        IncidentService incidentService = mock(IncidentService.class);
        VectorSearchService vectorSearchService = mock(VectorSearchService.class);

        IncidentRecord incident = incident("incident-2");
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-2");
        run.setIncidentId("incident-2");

        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId("case-incident-1");
        result.setContent("历史案例: payment-service CPU 高，根因为 GC 风暴");
        result.setScore(0.82f);
        result.setMetadata("{\"doc_type\":\"incident_case\",\"incident_id\":\"incident-1\",\"root_cause\":\"GC 风暴\"}");
        when(vectorSearchService.searchIncidentCases(any(), any(Integer.class))).thenReturn(List.of(result));

        IncidentCaseService service = new IncidentCaseService(
                incidentService, mock(MilvusServiceClient.class), mock(VectorEmbeddingService.class),
                vectorSearchService, new MilvusInsertHelper());

        String enriched = service.prefetchAndAppend(incident, run, "基础上下文");

        assertTrue(enriched.contains("基础上下文"));
        assertTrue(enriched.contains("## 相似历史故障案例"));
        assertTrue(enriched.contains("case-incident-1"));
        assertTrue(enriched.contains("[evidence:"));

        ArgumentCaptor<DiagnosisEvidence> evidenceCaptor = ArgumentCaptor.forClass(DiagnosisEvidence.class);
        verify(incidentService).addToolEvidence(eq("incident-2"), eq("run-2"), evidenceCaptor.capture());
        DiagnosisEvidence evidence = evidenceCaptor.getValue();
        assertEquals("searchSimilarIncidentCases", evidence.getToolName());
        assertEquals("历史故障案例", evidence.getTimeRange());
        assertTrue(evidence.getSummary().contains("召回 1 个相似历史故障案例"));
    }

    @Test
    void prefetchAndAppend_shouldRecordCircuitOpenEvidence_whenSimilarCaseRecallBreakerOpen() {
        IncidentService incidentService = mock(IncidentService.class);
        VectorSearchService vectorSearchService = mock(VectorSearchService.class);

        IncidentRecord incident = incident("incident-3");
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-3");
        run.setIncidentId("incident-3");

        when(vectorSearchService.searchIncidentCases(any(), any(Integer.class)))
                .thenThrow(new DependencyUnavailableException("milvus", "hybridSearch", "CIRCUIT_OPEN", null));

        IncidentCaseService service = new IncidentCaseService(
                incidentService, mock(MilvusServiceClient.class), mock(VectorEmbeddingService.class),
                vectorSearchService, new MilvusInsertHelper());

        String enriched = service.prefetchAndAppend(incident, run, "基础上下文");

        assertTrue(enriched.contains("召回失败"));
        assertTrue(enriched.contains("CIRCUIT_OPEN"));

        ArgumentCaptor<DiagnosisEvidence> evidenceCaptor = ArgumentCaptor.forClass(DiagnosisEvidence.class);
        verify(incidentService).addToolEvidence(eq("incident-3"), eq("run-3"), evidenceCaptor.capture());
        DiagnosisEvidence evidence = evidenceCaptor.getValue();
        assertEquals(false, evidence.isSuccess());
        assertEquals("CIRCUIT_OPEN", evidence.getErrorCode());
        assertTrue(evidence.getRawFragment().contains("\"success\":false"));
        assertTrue(evidence.getRawFragment().contains("\"errorCode\":\"CIRCUIT_OPEN\""));
    }

    private IncidentRecord incident(String incidentId) {
        AlertPayload.Alert alert = new AlertPayload.Alert();
        alert.setStatus("firing");
        alert.setFingerprint("fp-1");
        alert.setStartsAt("2026-05-14T00:00:00Z");
        alert.setLabels(Map.of(
                "alertname", "HighCPUUsage",
                "severity", "critical",
                "service", "payment-service",
                "instance", "payment-service-pod-1"
        ));
        alert.setAnnotations(Map.of("summary", "HighCPUUsage payment-service"));

        AlertPayload payload = new AlertPayload();
        payload.setReceiver("webhook");
        payload.setStatus("firing");
        payload.setCommonLabels(Map.of("severity", "critical", "service", "payment-service"));
        payload.setCommonAnnotations(Map.of("summary", "HighCPUUsage payment-service"));
        payload.setAlerts(List.of(alert));

        IncidentRecord incident = new IncidentRecord();
        incident.setId(incidentId);
        incident.setTitle("HighCPUUsage payment-service");
        incident.setSeverity("critical");
        incident.setStatus("OPEN");
        incident.setAggregationKey("fp:1");
        incident.getAlertPayloads().add(payload);
        return incident;
    }

    private DiagnosisRunRecord completedRun(String runId, String report) {
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId(runId);
        run.setStatus("COMPLETED");
        run.setReport(report);
        return run;
    }

    private List<?> fieldValues(InsertParam insertParam, String fieldName) {
        return fieldValues(insertParam.getFields(), fieldName);
    }

    private List<?> fieldValues(List<InsertParam.Field> fields, String fieldName) {
        return fields.stream()
                .filter(field -> fieldName.equals(field.getName()))
                .findFirst()
                .orElseThrow()
                .getValues();
    }
}
