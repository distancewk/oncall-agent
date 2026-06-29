package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppIncidentProperties;
import org.example.config.AppJobProperties;
import org.example.dto.AlertPayload;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.example.dto.IncidentSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private final BackgroundJobRepository backgroundJobRepository;
    private final RunningJobRegistry runningJobRegistry;
    private final AppJobProperties jobProperties;

    public IncidentService(IncidentStore incidentStore, ObjectMapper objectMapper) {
        this(incidentStore, objectMapper, null);
    }

    public IncidentService(IncidentStore incidentStore,
                           ObjectMapper objectMapper,
                           DiagnosisReportService diagnosisReportService) {
        this(incidentStore, objectMapper, diagnosisReportService, new AppIncidentProperties());
    }

    public IncidentService(IncidentStore incidentStore,
                           ObjectMapper objectMapper,
                           DiagnosisReportService diagnosisReportService,
                           AppIncidentProperties incidentProperties) {
        this(incidentStore, objectMapper, diagnosisReportService, incidentProperties,
                null, null, new AppJobProperties());
    }

    @Autowired
    public IncidentService(IncidentStore incidentStore,
                           ObjectMapper objectMapper,
                           DiagnosisReportService diagnosisReportService,
                           AppIncidentProperties incidentProperties,
                           BackgroundJobRepository backgroundJobRepository,
                           RunningJobRegistry runningJobRegistry,
                           AppJobProperties jobProperties) {
        this.incidentStore = incidentStore;
        this.objectMapper = objectMapper;
        this.diagnosisReportService = diagnosisReportService;
        this.incidentProperties = incidentProperties == null ? new AppIncidentProperties() : incidentProperties;
        this.backgroundJobRepository = backgroundJobRepository;
        this.runningJobRegistry = runningJobRegistry;
        this.jobProperties = jobProperties == null ? new AppJobProperties() : jobProperties;
    }

    public IncidentRecord recordAlert(AlertPayload payload) {
        String aggregationKey = aggregationKey(payload);
        long now = System.currentTimeMillis();
        IncidentRecord candidate = newIncident(payload, aggregationKey, now);
        return incidentStore.recordAlert(candidate, payload, now);
    }

    public List<IncidentSummary> listIncidents() {
        return listIncidents(null, null, null, null, null);
    }

    public List<IncidentSummary> listIncidents(String status,
                                               String severity,
                                               String latestRunStatus,
                                               String query,
                                               String humanReviewStatus) {
        return incidentStore.list().stream()
                .filter(record -> matchesIncidentFilters(record, status, severity, latestRunStatus, query, humanReviewStatus))
                .map(this::summary)
                .sorted(Comparator.comparingLong(IncidentSummary::getUpdatedAt).reversed())
                .toList();
    }

    public Optional<IncidentRecord> getIncident(String incidentId) {
        return incidentStore.load(incidentId);
    }

    public Optional<List<DiagnosisRunRecord>> getDiagnosisRuns(String incidentId) {
        return getIncident(incidentId).map(record -> new ArrayList<>(record.getDiagnosisRuns()));
    }

    public Optional<List<DiagnosisEvidence>> getRunEvidence(String incidentId, String runId) {
        return getIncident(incidentId)
                .flatMap(record -> record.getDiagnosisRuns().stream()
                        .filter(run -> runId.equals(run.getRunId()))
                        .findFirst()
                        .map(run -> new ArrayList<>(run.getEvidence())));
    }

    public DiagnosisRunRecord createDiagnosisRun(String incidentId, String alertContext) {
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

    public DiagnosisRunRecord createDiagnosisRunAndEnqueue(String incidentId,
                                                           String alertContext) {
        return createDiagnosisRunAndEnqueue(incidentId, alertContext, null);
    }

    public DiagnosisRunRecord createDiagnosisRunAndEnqueue(String incidentId,
                                                           String alertContext,
                                                           String alertId) {
        if (backgroundJobRepository == null) {
            throw new IllegalStateException("后台任务仓库未配置");
        }
        requireIncident(incidentId);
        long now = System.currentTimeMillis();
        DiagnosisRunRecord run = new DiagnosisRunRecord();
        run.setRunId("run-" + UUID.randomUUID().toString().substring(0, 12));
        run.setIncidentId(incidentId);
        run.setStatus("QUEUED");
        run.setCreatedAt(now);
        run.setAlertContext(alertContext);
        run.setCurrentStep("等待诊断任务开始");
        run.setProgressMessage("诊断任务已入队");
        DiagnosisEvidence initialEvidence = DiagnosisEvidence.of(
                "alert_context", "注入给 AI 的告警上下文", alertContext, now);
        initialEvidence.setId("ev-" + run.getRunId() + "-alert-context");
        run.getEvidence().add(initialEvidence);

        Map<String, String> jobPayload = new LinkedHashMap<>();
        jobPayload.put("incidentId", incidentId);
        jobPayload.put("runId", run.getRunId());
        jobPayload.put("alertContext", alertContext == null ? "" : alertContext);
        if (notBlank(alertId)) {
            jobPayload.put("alertId", alertId);
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(jobPayload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("创建诊断后台任务失败: " + run.getRunId(), e);
        }
        return incidentStore.inTransaction(connection -> {
            incidentStore.persistNewDiagnosisRun(connection, incidentId, run, now);
            backgroundJobRepository.enqueue(
                    connection,
                    "DIAGNOSIS",
                    run.getRunId(),
                    payload,
                    jobProperties.getDiagnosisMaxAttempts(),
                    run.getCreatedAt()
            );
            return run;
        });
    }

    public Optional<DiagnosisRunRecord> createReusedDiagnosisRunIfAvailable(String incidentId,
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

    public DiagnosisRunRecord updateRunAlertContext(String incidentId, String runId, String alertContext) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        if (!isActiveRun(run)) {
            return run;
        }
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

    public DiagnosisRunRecord markRunRunning(String incidentId, String runId) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        if (!isActiveRun(run)) {
            return run;
        }
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

    public DiagnosisRunRecord markRunWaitingTool(String incidentId,
                                                 String runId,
                                                 String toolName,
                                                 String queryParams) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        if (!isActiveRun(run)) {
            return run;
        }
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

    public DiagnosisRunRecord addToolEvidence(String incidentId,
                                              String runId,
                                              DiagnosisEvidence evidence) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        if (!isActiveRun(run)) {
            return run;
        }
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

    public DiagnosisRunRecord completeRun(String incidentId, String runId, String report) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        if (!isActiveRun(run)) {
            return run;
        }
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
        if (diagnosisReportService != null) {
            DiagnosisReportService.QualityAssessment quality =
                    diagnosisReportService.evaluateQuality(guardedReport, run.getEvidence());
            run.setQualityScore(quality.score());
            run.setQualityGrade(quality.grade());
            run.setQualitySummary(quality.summary());
            run.setQualityIssues(quality.issues());
        }
        run.setErrorMessage(null);
        run.setCurrentTool(null);
        run.setCurrentStep("诊断完成");
        run.setProgressMessage("已生成诊断报告");
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public DiagnosisRunRecord failRun(String incidentId, String runId, String errorMessage) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        if (!isActiveRun(run)) {
            return run;
        }
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

    public DiagnosisRunRecord cancelRun(String incidentId, String runId, String reason) {
        long now = System.currentTimeMillis();
        String cancelReason = notBlank(reason) ? reason : "用户取消诊断";
        DiagnosisEvidenceRepository evidenceRepository = new DiagnosisEvidenceRepository();
        DiagnosisRunRepository runRepository =
                new DiagnosisRunRepository(objectMapper, evidenceRepository);
        DiagnosisRunRecord cancelled = incidentStore.inTransaction(connection -> {
            boolean transitioned =
                    runRepository.cancelActive(connection, incidentId, runId, cancelReason, now);
            DiagnosisRunRecord current = runRepository.findById(connection, incidentId, runId)
                    .orElseThrow(() -> new IllegalArgumentException("诊断运行不存在: " + runId));
            if (!transitioned && !"CANCELLED".equals(current.getStatus())) {
                throw new IllegalStateException("已结束的诊断不能取消: " + runId);
            }
            if (transitioned) {
                DiagnosisEvidence evidence = DiagnosisEvidence.of(
                        "diagnosis_cancelled", "诊断取消原因", cancelReason, now);
                evidence.setId("ev-" + runId + "-cancelled");
                evidenceRepository.insert(connection, runId, evidence);
                current = runRepository.findById(connection, incidentId, runId).orElseThrow();
            }
            if (backgroundJobRepository != null) {
                backgroundJobRepository.requestCancel(connection, "DIAGNOSIS", runId, now);
            }
            return current;
        });
        if (runningJobRegistry != null) {
            runningJobRegistry.cancelByBusinessKey("DIAGNOSIS", runId);
        }
        return cancelled;
    }

    public DiagnosisRunRecord confirmRun(String incidentId, String runId, String comment) {
        return reviewCompletedRun(incidentId, runId, "CONFIRMED", comment);
    }

    public DiagnosisRunRecord rejectRun(String incidentId, String runId, String comment) {
        return reviewCompletedRun(incidentId, runId, "REJECTED", comment);
    }

    public DiagnosisRunRecord markRunCaseArchived(String incidentId,
                                                  String runId,
                                                  boolean archived,
                                                  String documentId,
                                                  String message) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        long now = System.currentTimeMillis();
        run.setCaseArchived(archived);
        run.setCaseDocumentId(documentId);
        run.setCaseArchiveMessage(message);
        incident.setUpdatedAt(now);
        incidentStore.save(incident);
        return run;
    }

    public List<DiagnosisRunRecord> markStaleRunsFailed(long nowMillis) {
        long timeoutMillis = incidentProperties.getStaleRunTimeoutMillis();
        if (timeoutMillis <= 0) {
            return List.of();
        }
        List<DiagnosisRunRecord> failedRuns = new ArrayList<>();
        for (IncidentRecord incident : incidentStore.list()) {
            for (DiagnosisRunRecord run : incident.getDiagnosisRuns()) {
                if (!isActiveRun(run) || !isStaleRun(run, nowMillis, timeoutMillis)) {
                    continue;
                }
                String message = "诊断任务超时，已自动标记失败";
                DiagnosisEvidence evidence =
                        DiagnosisEvidence.of("diagnosis_timeout", "诊断超时原因", message, nowMillis);
                incidentStore.failStaleRunIfActive(
                                incident.getId(),
                                run.getRunId(),
                                nowMillis,
                                timeoutMillis,
                                evidence)
                        .ifPresent(failedRuns::add);
            }
        }
        return failedRuns;
    }

    public List<DiagnosisRunRecord> markStaleRunsFailed() {
        return markStaleRunsFailed(System.currentTimeMillis());
    }

    private DiagnosisRunRecord reviewCompletedRun(String incidentId,
                                                  String runId,
                                                  String reviewStatus,
                                                  String comment) {
        IncidentRecord incident = requireIncident(incidentId);
        DiagnosisRunRecord run = requireRun(incident, runId);
        if (!"COMPLETED".equals(run.getStatus())) {
            throw new IllegalStateException("只有已完成诊断才能进行人工确认: " + runId);
        }
        long now = System.currentTimeMillis();
        run.setHumanReviewStatus(reviewStatus);
        run.setHumanReviewComment(comment);
        run.setHumanReviewedAt(now);
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

    private boolean isActiveRun(DiagnosisRunRecord run) {
        return "QUEUED".equals(run.getStatus())
                || "RUNNING".equals(run.getStatus())
                || "WAITING_TOOL".equals(run.getStatus());
    }

    private boolean isStaleRun(DiagnosisRunRecord run, long nowMillis, long timeoutMillis) {
        long baseline = run.getStartedAt() > 0L ? run.getStartedAt() : run.getCreatedAt();
        return baseline > 0L && nowMillis - baseline > timeoutMillis;
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
            DiagnosisRunRecord latestRun = record.getDiagnosisRuns().get(record.getDiagnosisRuns().size() - 1);
            summary.setLatestRunId(latestRun.getRunId());
            summary.setLatestRunStatus(latestRun.getStatus());
            summary.setLatestRunQualityScore(latestRun.getQualityScore());
            summary.setLatestRunQualityGrade(latestRun.getQualityGrade());
            summary.setLatestRunHumanReviewStatus(latestRun.getHumanReviewStatus());
            summary.setLatestRunCaseArchived(latestRun.isCaseArchived());
        }
        return summary;
    }

    private boolean matchesIncidentFilters(IncidentRecord record,
                                           String status,
                                           String severity,
                                           String latestRunStatus,
                                           String query,
                                           String humanReviewStatus) {
        DiagnosisRunRecord latestRun = latestRun(record).orElse(null);
        return matchesIgnoreCase(record.getStatus(), status)
                && matchesIgnoreCase(record.getSeverity(), severity)
                && matchesIgnoreCase(latestRun == null ? null : latestRun.getStatus(), latestRunStatus)
                && matchesIgnoreCase(latestRun == null ? null : latestRun.getHumanReviewStatus(), humanReviewStatus)
                && matchesQuery(record, query);
    }

    private Optional<DiagnosisRunRecord> latestRun(IncidentRecord record) {
        if (record.getDiagnosisRuns().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(record.getDiagnosisRuns().get(record.getDiagnosisRuns().size() - 1));
    }

    private boolean matchesIgnoreCase(String actual, String expected) {
        return !notBlank(expected) || value(actual).equalsIgnoreCase(expected.trim());
    }

    private boolean matchesQuery(IncidentRecord record, String query) {
        if (!notBlank(query)) {
            return true;
        }
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        String searchable = (value(record.getId()) + " "
                + value(record.getTitle()) + " "
                + value(record.getAggregationKey()) + " "
                + value(record.getSeverity()) + " "
                + value(record.getStatus())).toLowerCase(Locale.ROOT);
        return searchable.contains(normalizedQuery);
    }

    private String aggregationKey(AlertPayload payload) {
        AlertPayload.Alert alert = firstAlert(payload);
        if (alert != null && notBlank(alert.getFingerprint())) {
            return "fingerprint:" + alert.getFingerprint();
        }

        Map<String, String> labels = mergedLabels(payload, alert);
        String alertName = normalizeAlertName(label(labels, "alertname", title(payload)));
        String service = firstLabel(labels, "service", "job", "app", "application", "component", "workload");
        if (!notBlank(service)) {
            service = "unknown-service";
        }
        String instance = firstLabel(labels, "instance", "pod", "pod_name", "node", "host", "hostname");
        if (!notBlank(instance)) {
            instance = "unknown-instance";
        }
        String severity = firstLabel(labels, "severity");
        if (!notBlank(severity)) {
            severity = severity(payload);
        }
        return String.join("|", alertName, service, instance, severity).toLowerCase(Locale.ROOT);
    }

    private Map<String, String> mergedLabels(AlertPayload payload, AlertPayload.Alert alert) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (payload != null) {
            putAllLabels(labels, payload.getGroupLabels());
            putAllLabels(labels, payload.getCommonLabels());
        }
        if (alert != null) {
            putAllLabels(labels, alert.getLabels());
        }
        return labels;
    }

    private void putAllLabels(Map<String, String> target, Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                target.put(key, value);
            }
        });
    }

    private String normalizeAlertName(String alertName) {
        if (!notBlank(alertName)) {
            return "unknown-alert";
        }
        String compact = alertName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        return switch (compact) {
            case "cpuhigh", "highcpu", "cpuusagehigh", "highcpuusage" -> "cpu_high";
            case "memoryhigh", "highmemory", "memoryusagehigh", "highmemoryusage", "oomkilled" -> "memory_high";
            case "errorratehigh", "higherrorrate", "highhttperror", "high5xxrate" -> "error_rate_high";
            case "p99latencyhigh", "highp99latency", "latencyhigh", "slowresponse" -> "p99_latency_high";
            case "restartcounthigh", "highrestartcount", "podrestart", "crashloopbackoff" -> "restart_count_high";
            case "diskhigh", "highdisk", "diskusagehigh", "highdiskusage" -> "disk_high";
            default -> alertName;
        };
    }

    private String firstLabel(Map<String, String> labels, String... keys) {
        if (labels == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            String value = value(labels, key);
            if (notBlank(value)) {
                return value;
            }
        }
        return "";
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

    private String value(String value) {
        return value == null ? "" : value;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
