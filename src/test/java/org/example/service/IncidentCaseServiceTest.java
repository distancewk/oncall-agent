package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        incident.getDiagnosisRuns().add(run);
        when(incidentService.getIncident("incident-1")).thenReturn(Optional.of(incident));
        when(embeddingService.generateEmbedding(any())).thenReturn(List.of(0.1f, 0.2f));
        when(embeddingService.generateSparseVector(any())).thenReturn(new TreeMap<>(Map.of(1L, 1.0f)));

        @SuppressWarnings("unchecked")
        R<MutationResult> mutationResponse = mock(R.class);
        when(mutationResponse.getStatus()).thenReturn(0);
        when(milvusClient.delete(any(DeleteParam.class))).thenReturn(mutationResponse);
        when(milvusClient.insert(any(InsertParam.class))).thenReturn(mutationResponse);

        IncidentCaseService service = new IncidentCaseService(
                incidentService, milvusClient, embeddingService, vectorSearchService, new MilvusInsertHelper());

        IncidentCaseService.ArchiveResult result = service.archiveCase("incident-1");

        assertTrue(result.isSuccess());
        assertEquals("incident-1", result.getIncidentId());

        ArgumentCaptor<InsertParam> insertCaptor = ArgumentCaptor.forClass(InsertParam.class);
        verify(milvusClient).insert(insertCaptor.capture());
        InsertParam insertParam = insertCaptor.getValue();

        assertEquals(1, insertParam.getRowCount());
        String content = fieldValues(insertParam, "content").get(0).toString();
        String metadata = fieldValues(insertParam, "metadata").get(0).toString();
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
        return insertParam.getFields().stream()
                .filter(field -> fieldName.equals(field.getName()))
                .findFirst()
                .orElseThrow()
                .getValues();
    }
}
