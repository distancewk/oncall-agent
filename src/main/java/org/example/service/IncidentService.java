package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppIncidentProperties;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.dto.IncidentSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class IncidentService {

    private final IncidentStore incidentStore;
    private final ObjectMapper objectMapper;
    private final DiagnosisReportService diagnosisReportService;
    private final AppIncidentProperties incidentProperties;

    public IncidentService(IncidentStore incidentStore, ObjectMapper objectMapper) {
        this(incidentStore, objectMapper, null);
    }

    public IncidentService(IncidentStore incidentStore,
                           ObjectMapper objectMapper,
                           DiagnosisReportService diagnosisReportService) {
        this(incidentStore, objectMapper, diagnosisReportService, new AppIncidentProperties());
    }

    @Autowired
    public IncidentService(IncidentStore incidentStore,
                           ObjectMapper objectMapper,
                           DiagnosisReportService diagnosisReportService,
                           AppIncidentProperties incidentProperties) {
        this.incidentStore = incidentStore;
        this.objectMapper = objectMapper;
        this.diagnosisReportService = diagnosisReportService;
        this.incidentProperties = incidentProperties == null ? new AppIncidentProperties() : incidentProperties;
    }

    public synchronized IncidentRecord recordAlert(AlertPayload payload) {
        String aggregationKey = aggregationKey(payload);
        long now = System.currentTimeMillis();
        IncidentRecord record = incidentStore.findByAggregationKey(aggregationKey)
                .orElseGet(() -> newIncident(payload, aggregationKey, now));

        record.setTitle(title(payload));
        record.setSeverity(severity(payload));
        record.setStatus(status(payload));
        record.setUpdatedAt(now);
        record.setLastAlertAt(now);
        record.setAlertCount(record.getAlertCount() + 1);
        record.getAlertPayloads().add(payload);
        incidentStore.save(record);
        return record;
    }

    public synchronized List<IncidentSummary> listIncidents() {
        return incidentStore.list().stream()
                .map(this::summary)
                .sorted(Comparator.comparingLong(IncidentSummary::getUpdatedAt).reversed())
                .toList();
    }

    public synchronized Optional<IncidentRecord> getIncident(String incidentId) {
        return incidentStore.load(incidentId);
    }

    public synchronized Optional<List<DiagnosisRunRecord>> getDiagnosisRuns(String incidentId) {
        return getIncident(incidentId).map(record -> new ArrayList<>(record.getDiagnosisRuns()));
    }

    public synchronized DiagnosisRunRecord createDiagnosisRun(String incidentId, String alertContext) {
        IncidentRecord incident = requireIncident(incidentId);
        long now = System.currentTimeMillis();
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-" + UUID.randomUUID().toString().substring(0, 12));
        run.setIncidentId(incidentId);
        run.setStatus("QUEUED");
        run.setCreatedAt(now);
        run.setAlertContext(alertContext);
        run.setCurrentStep("等待诊断任务开始");
        run.setProgressMessage("诊断任务已入队");
        run.getEvidence().add(DiagnosisEvidence.of("alert_context", "注入给 AI 的告警上下文", alertContext, now));

        incident.getDiagnosisRuns().add(run);
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public synchronized Optional<DiagnosisRunRecord> createReusedDiagnosisRunIfAvailable(String incidentId,
                                                                                         String alertContext) {
        IncidentRecord incident = requireIncident(incidentId);
        long now = System.currentTimeMillis();
        if (!incidentProperties.isDiagnosisReuseEnabled()) {
            return Optional.empty();
        }
        Optional<DiagnosisRunRecord> sourceOptional = latestReusableCompletedRun(incident, now);
        if (sourceOptional.isEmpty()) {
            return Optional.empty();
        }

        DiagnosisRunRecord source = sourceOptional.get();
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-" + UUID.randomUUID().toString().substring(0, 12));
        run.setIncidentId(incidentId);
        run.setStatus("COMPLETED");
        run.setCreatedAt(now);
        run.setStartedAt(now);
        run.setCompletedAt(now);
        run.setAlertContext(alertContext);
        run.setReport(source.getReport());
        run.setErrorMessage(null);
        run.setCurrentTool(null);
        run.setCurrentStep("复用历史诊断报告");

        String sourceRunId = notBlank(source.getReusedFromRunId())
                ? source.getReusedFromRunId()
                : source.getRunId();
        String reason = "同一 Incident 已存在完成诊断，告警聚合键一致，复用历史报告: " + sourceRunId;
        run.setReusedFromRunId(sourceRunId);
        run.setReuseReason(reason);
        run.setReuseConfidence(1.0d);
        run.setReuseValidatedAt(now);
        run.setProgressMessage(reason);
        run.getEvidence().add(DiagnosisEvidence.of("alert_context", "注入给 AI 的告警上下文", alertContext, now));
        run.getEvidence().add(DiagnosisEvidence.of("diagnosis_reuse", "复用历史诊断报告",
                buildReuseEvidenceContent(incident, source, sourceRunId, reason), now));

        incident.getDiagnosisRuns().add(run);
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return Optional.of(run);
    }

    public synchronized DiagnosisRunRecord updateRunAlertContext(String incidentId, String runId, String alertContext) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        long now = System.currentTimeMillis();
        run.setAlertContext(alertContext);
        Optional<DiagnosisEvidence> contextEvidence = run.getEvidence().stream()
                .filter(evidence -> "alert_context".equals(evidence.getType()))
                .findFirst();
        if (contextEvidence.isPresent()) {
            DiagnosisEvidence evidence = contextEvidence.get();
            evidence.setContent(alertContext);
            evidence.setSummary(alertContext);
            evidence.setRawFragment(alertContext);
        } else {
            run.getEvidence().add(DiagnosisEvidence.of("alert_context", "注入给 AI 的告警上下文", alertContext, now));
        }
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public synchronized DiagnosisRunRecord markRunRunning(String incidentId, String runId) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        long now = System.currentTimeMillis();
        run.setStatus("RUNNING");
        run.setStartedAt(now);
        run.setCurrentStep("正在拆解诊断任务");
        run.setProgressMessage("AI Ops 诊断已开始");
        run.setCurrentTool(null);
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public synchronized DiagnosisRunRecord markRunWaitingTool(String incidentId,
                                                              String runId,
                                                              String toolName,
                                                              String queryParams) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        long now = System.currentTimeMillis();
        if (run.getStartedAt() == 0L) {
            run.setStartedAt(now);
        }
        run.setStatus("WAITING_TOOL");
        run.setCurrentTool(toolName);
        run.setCurrentStep("正在调用工具 " + toolName);
        run.setProgressMessage(queryParams);
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public synchronized DiagnosisRunRecord addToolEvidence(String incidentId,
                                                           String runId,
                                                           DiagnosisEvidence evidence) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        long now = System.currentTimeMillis();
        run.getEvidence().add(evidence);
        run.setStatus("RUNNING");
        run.setCurrentTool(null);
        run.setCurrentStep("已完成工具调用 " + evidence.getToolName());
        run.setProgressMessage(evidence.getSummary());
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public synchronized DiagnosisRunRecord completeRun(String incidentId, String runId, String report) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        long now = System.currentTimeMillis();
        if (run.getStartedAt() == 0L) {
            run.setStartedAt(now);
        }
        run.setStatus("COMPLETED");
        run.setCompletedAt(now);
        String guardedReport = diagnosisReportService == null
                ? report
                : diagnosisReportService.guardReport(report, run);
        run.setReport(guardedReport);
        run.setErrorMessage(null);
        run.setCurrentTool(null);
        run.setCurrentStep("诊断完成");
        run.setProgressMessage("已生成诊断报告");
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public synchronized DiagnosisRunRecord failRun(String incidentId, String runId, String errorMessage) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        long now = System.currentTimeMillis();
        if (run.getStartedAt() == 0L) {
            run.setStartedAt(now);
        }
        run.setStatus("FAILED");
        run.setCompletedAt(now);
        run.setErrorMessage(errorMessage);
        run.setCurrentTool(null);
        run.setCurrentStep("诊断失败");
        run.setProgressMessage(errorMessage);
        run.getEvidence().add(DiagnosisEvidence.of("failure_reason", "诊断失败原因", errorMessage, now));
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public String buildAlertContext(IncidentRecord incident) {
        StringBuilder builder = new StringBuilder();
        builder.append("Incident ID: ").append(incident.getId()).append('\n');
        builder.append("聚合键: ").append(incident.getAggregationKey()).append('\n');
        builder.append("标题: ").append(incident.getTitle()).append('\n');
        builder.append("级别: ").append(incident.getSeverity()).append('\n');
        builder.append("状态: ").append(incident.getStatus()).append('\n');
        builder.append("累计告警次数: ").append(incident.getAlertCount()).append('\n');

        List<AlertPayload> payloads = incident.getAlertPayloads();
        if (!payloads.isEmpty()) {
            builder.append('\n').append("最新告警原文摘要:").append('\n');
            builder.append(buildAlertContext(payloads.get(payloads.size() - 1)));
        }
        return builder.toString();
    }

    public String buildAlertContext(AlertPayload payload) {
        StringBuilder builder = new StringBuilder();
        builder.append("告警状态: ").append(payload.getStatus()).append('\n');
        builder.append("接收者: ").append(payload.getReceiver()).append('\n');

        appendMap(builder, "公共标签", payload.getCommonLabels());
        appendMap(builder, "公共注解", payload.getCommonAnnotations());

        builder.append("告警列表:").append('\n');
        if (payload.getAlerts() != null) {
            for (int i = 0; i < payload.getAlerts().size(); i++) {
                AlertPayload.Alert alert = payload.getAlerts().get(i);
                builder.append("  [").append(i + 1).append("]").append('\n');
                builder.append("    状态: ").append(alert.getStatus()).append('\n');
                builder.append("    开始时间: ").append(alert.getStartsAt()).append('\n');
                builder.append("    结束时间: ").append(alert.getEndsAt()).append('\n');
                builder.append("    fingerprint: ").append(alert.getFingerprint()).append('\n');
                appendMap(builder, "    标签", alert.getLabels());
                appendMap(builder, "    注解", alert.getAnnotations());
            }
        }
        return builder.toString();
    }

    public String rawPayloadEvidence(AlertPayload payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return String.valueOf(payload);
        }
    }

    private IncidentRecord newIncident(AlertPayload payload, String aggregationKey, long now) {
        IncidentRecord record = new IncidentRecord();
        record.setId("inc-" + UUID.randomUUID().toString().substring(0, 12));
        record.setAggregationKey(aggregationKey);
        record.setTitle(title(payload));
        record.setSeverity(severity(payload));
        record.setStatus(status(payload));
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setLastAlertAt(now);
        record.setAlertCount(0);
        return record;
    }

    private IncidentRecord requireIncident(String incidentId) {
        return incidentStore.load(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Incident 不存在: " + incidentId));
    }

    private DiagnosisRunRecord requireRun(IncidentRecord incident, String runId) {
        return incident.getDiagnosisRuns().stream()
                .filter(run -> runId.equals(run.getRunId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DiagnosisRun 不存在: " + runId));
    }

    private Optional<DiagnosisRunRecord> latestReusableCompletedRun(IncidentRecord incident, long now) {
        List<DiagnosisRunRecord> runs = incident.getDiagnosisRuns();
        for (int i = runs.size() - 1; i >= 0; i--) {
            DiagnosisRunRecord run = runs.get(i);
            if ("COMPLETED".equals(run.getStatus())
                    && notBlank(run.getReport())
                    && isWithinReuseWindow(run, now)) {
                return Optional.of(run);
            }
        }
        return Optional.empty();
    }

    private boolean isWithinReuseWindow(DiagnosisRunRecord run, long now) {
        long windowMillis = incidentProperties.getDiagnosisReuseWindowMillis();
        if (windowMillis <= 0) {
            return false;
        }
        long completedAt = run.getCompletedAt();
        return completedAt > 0 && now - completedAt <= windowMillis;
    }

    private String buildReuseEvidenceContent(IncidentRecord incident,
                                             DiagnosisRunRecord source,
                                             String sourceRunId,
                                             String reason) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "sourceIncidentId", incident.getId(),
                    "sourceRunId", sourceRunId,
                    "reusedFromLatestRunId", source.getRunId(),
                    "aggregationKey", incident.getAggregationKey(),
                    "reason", reason
            ));
        } catch (JsonProcessingException e) {
            return reason;
        }
    }

    private IncidentSummary summary(IncidentRecord record) {
        IncidentSummary summary = new IncidentSummary();
        summary.setId(record.getId());
        summary.setTitle(record.getTitle());
        summary.setStatus(record.getStatus());
        summary.setSeverity(record.getSeverity());
        summary.setAlertCount(record.getAlertCount());
        summary.setCreatedAt(record.getCreatedAt());
        summary.setUpdatedAt(record.getUpdatedAt());
        summary.setLastAlertAt(record.getLastAlertAt());
        if (!record.getDiagnosisRuns().isEmpty()) {
            summary.setLatestRunStatus(record.getDiagnosisRuns()
                    .get(record.getDiagnosisRuns().size() - 1)
                    .getStatus());
        }
        return summary;
    }

    private String aggregationKey(AlertPayload payload) {
        AlertPayload.Alert alert = firstAlert(payload);
        if (alert != null && notBlank(alert.getFingerprint())) {
            return "fingerprint:" + alert.getFingerprint();
        }

        Map<String, String> labels = alert != null ? alert.getLabels() : Map.of();
        String alertName = label(labels, "alertname", "unknown-alert");
        String service = label(labels, "service", label(labels, "job", "unknown-service"));
        String instance = label(labels, "instance", "unknown-instance");
        String severity = label(labels, "severity", severity(payload));
        return String.join("|", alertName, service, instance, severity).toLowerCase(Locale.ROOT);
    }

    private String title(AlertPayload payload) {
        String summary = value(payload.getCommonAnnotations(), "summary");
        if (notBlank(summary)) {
            return summary;
        }
        AlertPayload.Alert alert = firstAlert(payload);
        if (alert != null) {
            summary = value(alert.getAnnotations(), "summary");
            if (notBlank(summary)) {
                return summary;
            }
            String alertName = value(alert.getLabels(), "alertname");
            String service = value(alert.getLabels(), "service");
            if (notBlank(alertName) && notBlank(service)) {
                return alertName + " " + service;
            }
            if (notBlank(alertName)) {
                return alertName;
            }
        }
        return "未知告警";
    }

    private String severity(AlertPayload payload) {
        String severity = value(payload.getCommonLabels(), "severity");
        if (notBlank(severity)) {
            return severity;
        }
        AlertPayload.Alert alert = firstAlert(payload);
        if (alert != null) {
            severity = value(alert.getLabels(), "severity");
            if (notBlank(severity)) {
                return severity;
            }
        }
        return "unknown";
    }

    private String status(AlertPayload payload) {
        return "resolved".equalsIgnoreCase(payload.getStatus()) ? "RESOLVED" : "OPEN";
    }

    private AlertPayload.Alert firstAlert(AlertPayload payload) {
        if (payload.getAlerts() == null || payload.getAlerts().isEmpty()) {
            return null;
        }
        return payload.getAlerts().get(0);
    }

    private void appendMap(StringBuilder builder, String title, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        builder.append(title).append(':').append('\n');
        values.forEach((key, value) -> builder
                .append("  - ")
                .append(key)
                .append(": ")
                .append(value)
                .append('\n'));
    }

    private String label(Map<String, String> labels, String key, String fallback) {
        String value = value(labels, key);
        return notBlank(value) ? value : fallback;
    }

    private String value(Map<String, String> map, String key) {
        if (map == null) {
            return null;
        }
        return map.get(key);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
