package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.exception.DependencyUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;

@Service
public class IncidentCaseService {

    public static final String TOOL_SEARCH_SIMILAR_CASES = "searchSimilarIncidentCases";

    private final IncidentService incidentService;
    private final MilvusServiceClient milvusClient;
    private final VectorEmbeddingService embeddingService;
    private final VectorSearchService vectorSearchService;
    private final MilvusInsertHelper insertHelper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private DependencyGuard dependencyGuard;

    @Autowired
    public IncidentCaseService(IncidentService incidentService,
                               @Lazy MilvusServiceClient milvusClient,
                               VectorEmbeddingService embeddingService,
                               VectorSearchService vectorSearchService,
                               MilvusInsertHelper insertHelper) {
        this.incidentService = incidentService;
        this.milvusClient = milvusClient;
        this.embeddingService = embeddingService;
        this.vectorSearchService = vectorSearchService;
        this.insertHelper = insertHelper;
    }

    public ArchiveResult archiveCase(String incidentId) {
        IncidentRecord incident = incidentService.getIncident(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Incident 不存在: " + incidentId));
        DiagnosisRunRecord run = latestCompletedRun(incident)
                .orElseThrow(() -> new IllegalStateException("Incident 尚无已完成诊断，不能写入历史案例"));
        return archiveCase(incident, run);
    }

    public ArchiveResult archiveCase(String incidentId, String runId) {
        IncidentRecord incident = incidentService.getIncident(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Incident 不存在: " + incidentId));
        DiagnosisRunRecord run = incident.getDiagnosisRuns().stream()
                .filter(item -> runId.equals(item.getRunId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DiagnosisRun 不存在: " + runId));
        if (!"COMPLETED".equals(run.getStatus()) || run.getReport() == null || run.getReport().isBlank()) {
            throw new IllegalStateException("DiagnosisRun 尚无已完成报告，不能写入历史案例: " + runId);
        }
        if (!"CONFIRMED".equals(run.getHumanReviewStatus())) {
            throw new IllegalStateException("DiagnosisRun 未被人工确认，不能写入历史案例: " + runId);
        }
        return archiveCase(incident, run);
    }

    private ArchiveResult archiveCase(IncidentRecord incident, DiagnosisRunRecord run) {
        CaseDocument document = buildCaseDocument(incident, run);
        deleteExistingCase(incident.getId());
        insertCaseDocument(document);

        ArchiveResult result = new ArchiveResult();
        result.setSuccess(true);
        result.setIncidentId(incident.getId());
        result.setDocumentId(document.id());
        result.setMessage("历史案例已写入知识库");
        return result;
    }

    public List<VectorSearchService.SearchResult> findSimilarCases(String incidentId, int topK) {
        IncidentRecord incident = incidentService.getIncident(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Incident 不存在: " + incidentId));
        return findSimilarCases(incident, topK);
    }

    public List<VectorSearchService.SearchResult> findSimilarCases(IncidentRecord incident, int topK) {
        String query = buildIncidentCaseQuery(incident);
        return vectorSearchService.searchIncidentCases(query, normalizeTopK(topK));
    }

    public String prefetchAndAppend(IncidentRecord incident, DiagnosisRunRecord run, String alertContext) {
        Optional<DiagnosisEvidence> existingEvidence = findSuccessfulEvidence(incident, run);
        if (existingEvidence.isPresent()) {
            DiagnosisEvidence evidence = existingEvidence.get();
            return (alertContext == null ? "" : alertContext)
                    + "\n\n## 相似历史故障案例\n"
                    + "- evidence: [evidence: " + evidence.getId() + "]\n"
                    + "- " + value(evidence.getSummary()) + "\n";
        }
        List<VectorSearchService.SearchResult> cases;
        try {
            cases = findSimilarCases(incident, 3);
        } catch (DependencyUnavailableException e) {
            DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                    TOOL_SEARCH_SIMILAR_CASES,
                    buildSearchParams(incident, 3),
                    "历史故障案例",
                    "相似历史故障案例召回失败: " + e.getErrorCode(),
                    buildFailureRaw(e),
                    false,
                    e.getMessage(),
                    System.currentTimeMillis());
            evidence.setErrorCode(e.getErrorCode());
            incidentService.addToolEvidence(incident.getId(), run.getRunId(), evidence);
            return alertContext + "\n\n## 相似历史故障案例\n- 召回失败 [evidence: "
                    + evidence.getId() + "]: " + e.getErrorCode() + " " + e.getMessage() + "\n";
        } catch (Exception e) {
            DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                    TOOL_SEARCH_SIMILAR_CASES,
                    buildSearchParams(incident, 3),
                    "历史故障案例",
                    "相似历史故障案例召回失败: " + e.getMessage(),
                    "{\"success\":false,\"error\":\"" + escapeJson(e.getMessage()) + "\"}",
                    false,
                    e.getMessage(),
                    System.currentTimeMillis());
            incidentService.addToolEvidence(incident.getId(), run.getRunId(), evidence);
            return alertContext + "\n\n## 相似历史故障案例\n- 召回失败 [evidence: " + evidence.getId() + "]: " + e.getMessage() + "\n";
        }

        if (cases.isEmpty()) {
            return alertContext;
        }

        DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                TOOL_SEARCH_SIMILAR_CASES,
                buildSearchParams(incident, 3),
                "历史故障案例",
                "召回 " + cases.size() + " 个相似历史故障案例",
                toJson(cases),
                true,
                null,
                System.currentTimeMillis());
        incidentService.addToolEvidence(incident.getId(), run.getRunId(), evidence);

        StringBuilder builder = new StringBuilder(alertContext == null ? "" : alertContext);
        builder.append("\n\n## 相似历史故障案例\n");
        builder.append("- evidence: [evidence: ").append(evidence.getId()).append("]\n");
        for (int i = 0; i < cases.size(); i++) {
            VectorSearchService.SearchResult item = cases.get(i);
            builder.append("  ").append(i + 1).append(". ")
                    .append(item.getId()).append(": ")
                    .append(compact(item.getContent(), 220))
                    .append("，score=").append(String.format(java.util.Locale.ROOT, "%.2f", item.getScore()))
                    .append('\n');
        }
        return builder.toString();
    }

    private Optional<DiagnosisEvidence> findSuccessfulEvidence(IncidentRecord incident,
                                                                DiagnosisRunRecord run) {
        if (run == null || run.getEvidence() == null) {
            return Optional.empty();
        }
        String expectedParams = buildSearchParams(incident, 3);
        return run.getEvidence().stream()
                .filter(evidence -> evidence != null && evidence.isSuccess())
                .filter(evidence -> TOOL_SEARCH_SIMILAR_CASES.equals(evidence.getToolName()))
                .filter(evidence -> expectedParams.equals(evidence.getQueryParams()))
                .findFirst();
    }

    private CaseDocument buildCaseDocument(IncidentRecord incident, DiagnosisRunRecord run) {
        String rootCause = extractSection(run.getReport(), "根因结论");
        String action = extractSection(run.getReport(), "处理建议");
        if (rootCause.isBlank()) {
            rootCause = compact(run.getReport(), 240);
        }
        if (action.isBlank()) {
            action = "未提取到结构化处理动作";
        }

        Map<String, String> labels = latestLabels(incident);
        String alertName = firstNonBlank(labels.get("alertname"), incident.getTitle());
        String service = firstNonBlank(labels.get("service"), labels.get("job"));
        String instance = labels.get("instance");

        StringBuilder content = new StringBuilder();
        content.append("# 历史故障案例\n");
        content.append("Incident ID: ").append(incident.getId()).append('\n');
        content.append("标题: ").append(value(incident.getTitle())).append('\n');
        content.append("级别: ").append(value(incident.getSeverity())).append('\n');
        content.append("告警名: ").append(value(alertName)).append('\n');
        content.append("服务: ").append(value(service)).append('\n');
        content.append("实例: ").append(value(instance)).append('\n');
        content.append("\n## 根因\n").append(rootCause).append('\n');
        content.append("\n## 处理动作\n").append(action).append('\n');
        content.append("\n## 原始诊断报告\n").append(compact(run.getReport(), 2000)).append('\n');

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", "incident_case:" + incident.getId());
        metadata.put("doc_type", MilvusConstants.DOC_TYPE_INCIDENT_CASE);
        metadata.put("incident_id", incident.getId());
        metadata.put("run_id", run.getRunId());
        metadata.put("alertname", value(alertName));
        metadata.put("service", value(service));
        metadata.put("instance", value(instance));
        metadata.put("severity", value(incident.getSeverity()));
        metadata.put("root_cause", rootCause);
        metadata.put("human_review_status", value(run.getHumanReviewStatus()));
        metadata.put("archived_at", System.currentTimeMillis());

        String id = UUID.nameUUIDFromBytes(("incident_case:" + incident.getId()).getBytes(StandardCharsets.UTF_8)).toString();
        return new CaseDocument(id, content.toString(), metadata);
    }

    private void deleteExistingCase(String incidentId) {
        String expr = "metadata[\"doc_type\"] == \"" + MilvusConstants.DOC_TYPE_INCIDENT_CASE
                + "\" && metadata[\"incident_id\"] == \"" + escapeExpr(incidentId) + "\"";
        R<MutationResult> response = guardedMilvus("deleteExistingCase", () -> milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withExpr(expr)
                .build()));
        if (response.getStatus() != 0) {
            throw new RuntimeException("删除旧历史案例失败: " + response.getMessage());
        }
    }

    private void insertCaseDocument(CaseDocument document) {
        List<Float> vector = embeddingService.generateEmbedding(document.content());
        SortedMap<Long, Float> sparseVector = embeddingService.generateSparseVector(document.content());
        InsertParam insertParam = insertHelper.buildInsertParam(
                List.of(document.id()),
                List.of(document.content()),
                List.of(vector),
                List.of(sparseVector),
                List.of(document.metadata()));
        R<MutationResult> response = guardedMilvus("insertCaseDocument", () -> milvusClient.insert(insertParam));
        if (response.getStatus() != 0) {
            throw new RuntimeException("写入历史案例失败: " + response.getMessage());
        }
    }

    private R<MutationResult> guardedMilvus(String operation, java.util.function.Supplier<R<MutationResult>> call) {
        if (dependencyGuard == null) {
            return call.get();
        }
        return dependencyGuard.execute("milvus", operation,
                () -> {
                    R<MutationResult> response = call.get();
                    if (response.getStatus() != 0) {
                        throw new RuntimeException(response.getMessage());
                    }
                    return response;
                },
                error -> {
                    if (error instanceof DependencyUnavailableException unavailable) {
                        throw unavailable;
                    }
                    if (error instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new RuntimeException(error);
                });
    }

    private Optional<DiagnosisRunRecord> latestCompletedRun(IncidentRecord incident) {
        List<DiagnosisRunRecord> runs = incident.getDiagnosisRuns();
        for (int i = runs.size() - 1; i >= 0; i--) {
            DiagnosisRunRecord run = runs.get(i);
            if ("COMPLETED".equals(run.getStatus()) && run.getReport() != null && !run.getReport().isBlank()) {
                return Optional.of(run);
            }
        }
        return Optional.empty();
    }

    private String buildIncidentCaseQuery(IncidentRecord incident) {
        Map<String, String> labels = latestLabels(incident);
        List<String> parts = new ArrayList<>();
        parts.add(incident.getTitle());
        parts.add(incident.getSeverity());
        parts.add(labels.get("alertname"));
        parts.add(labels.get("service"));
        parts.add(labels.get("job"));
        parts.add(labels.get("instance"));
        return String.join(" ", parts.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList());
    }

    private Map<String, String> latestLabels(IncidentRecord incident) {
        if (incident.getAlertPayloads().isEmpty()) {
            return Map.of();
        }
        AlertPayload payload = incident.getAlertPayloads().get(incident.getAlertPayloads().size() - 1);
        if (payload.getAlerts() == null || payload.getAlerts().isEmpty()) {
            return payload.getCommonLabels() == null ? Map.of() : payload.getCommonLabels();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        if (payload.getCommonLabels() != null) {
            labels.putAll(payload.getCommonLabels());
        }
        if (payload.getAlerts().get(0).getLabels() != null) {
            labels.putAll(payload.getAlerts().get(0).getLabels());
        }
        return labels;
    }

    private String extractSection(String report, String heading) {
        if (report == null || report.isBlank()) {
            return "";
        }
        String marker = "### " + heading;
        int start = report.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int contentStart = start + marker.length();
        int next = report.indexOf("\n### ", contentStart);
        if (next < 0) {
            next = report.indexOf("\n## ", contentStart);
        }
        String section = next < 0 ? report.substring(contentStart) : report.substring(contentStart, next);
        return compact(section.trim(), 500);
    }

    private String buildSearchParams(IncidentRecord incident, int topK) {
        return "{\"incidentId\":\"" + escapeJson(incident.getId()) + "\",\"topK\":" + topK
                + ",\"query\":\"" + escapeJson(buildIncidentCaseQuery(incident)) + "\"}";
    }

    private int normalizeTopK(int topK) {
        if (topK <= 0) {
            return 3;
        }
        return Math.min(topK, 10);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String buildFailureRaw(DependencyUnavailableException e) {
        try {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("success", false);
            raw.put("status", "error");
            raw.put("errorCode", e.getErrorCode());
            raw.put("message", e.getMessage());
            return objectMapper.writeValueAsString(raw);
        } catch (Exception jsonError) {
            return "{\"success\":false,\"status\":\"error\",\"errorCode\":\""
                    + escapeJson(e.getErrorCode()) + "\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private String compact(String value, int limit) {
        String text = value(value).replaceAll("\\s+", " ").trim();
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : value(second);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private String escapeJson(String value) {
        return value(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String escapeExpr(String value) {
        return escapeJson(value);
    }

    private record CaseDocument(String id, String content, Map<String, Object> metadata) {
    }

    @Getter
    @Setter
    public static class ArchiveResult {
        private boolean success;
        private String incidentId;
        private String documentId;
        private String message;
    }
}
