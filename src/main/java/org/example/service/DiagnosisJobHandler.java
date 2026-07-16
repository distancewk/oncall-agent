package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.BackgroundJobRecord;
import org.example.dto.DiagnosisRunRecord;
import org.example.dto.IncidentRecord;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class DiagnosisJobHandler implements BackgroundJobHandler {

    private final ObjectMapper objectMapper;
    private final IncidentService incidentService;
    private final AiOpsService aiOpsService;
    private final DashScopeChatModel chatModel;
    private final ToolCallbackProvider tools;
    private final IncidentCaseService incidentCaseService;
    private final MetricTrendPrefetchService metricTrendPrefetchService;
    private final BackgroundJobRepository jobRepository;
    private final AlertService alertService;
    private final Executor diagnosisPrefetchExecutor;

    @Autowired
    public DiagnosisJobHandler(ObjectMapper objectMapper,
                               IncidentService incidentService,
                               AiOpsService aiOpsService,
                               @Qualifier("dashScopeChatModelAiOps") DashScopeChatModel chatModel,
                               @Nullable ToolCallbackProvider tools,
                               @Nullable IncidentCaseService incidentCaseService,
                               @Nullable MetricTrendPrefetchService metricTrendPrefetchService,
                               BackgroundJobRepository jobRepository,
                               @Nullable AlertService alertService,
                               @Qualifier("diagnosisPrefetchExecutor") @Nullable Executor diagnosisPrefetchExecutor) {
        this.objectMapper = objectMapper;
        this.incidentService = incidentService;
        this.aiOpsService = aiOpsService;
        this.chatModel = chatModel;
        this.tools = tools;
        this.incidentCaseService = incidentCaseService;
        this.metricTrendPrefetchService = metricTrendPrefetchService;
        this.jobRepository = jobRepository;
        this.alertService = alertService;
        this.diagnosisPrefetchExecutor = diagnosisPrefetchExecutor == null ? Runnable::run : diagnosisPrefetchExecutor;
    }

    public DiagnosisJobHandler(ObjectMapper objectMapper,
                               IncidentService incidentService,
                               AiOpsService aiOpsService,
                               @Qualifier("dashScopeChatModelAiOps") DashScopeChatModel chatModel,
                               @Nullable ToolCallbackProvider tools,
                               @Nullable IncidentCaseService incidentCaseService,
                               @Nullable MetricTrendPrefetchService metricTrendPrefetchService,
                               BackgroundJobRepository jobRepository,
                               @Nullable AlertService alertService) {
        this(objectMapper, incidentService, aiOpsService, chatModel, tools, incidentCaseService,
                metricTrendPrefetchService, jobRepository, alertService, Runnable::run);
    }

    @Override
    public String jobType() {
        return "DIAGNOSIS";
    }

    @Override
    public void handle(BackgroundJobRecord job) throws Exception {
        if (cancelled(job)) {
            return;
        }
        JsonNode payload = objectMapper.readTree(job.getPayload());
        String incidentId = required(payload, "incidentId");
        String runId = required(payload, "runId");
        String alertId = payload.path("alertId").asText(null);
        String context = payload.path("alertContext").asText("");

        IncidentRecord incident = incidentService.getIncident(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Incident 不存在: " + incidentId));
        DiagnosisRunRecord run = findRun(incidentId, runId);
        incidentService.markRunRunning(incidentId, runId);

        if (cancelled(job)) {
            return;
        }
        String baseContext = context;
        CompletableFuture<String> similarCases = CompletableFuture.supplyAsync(
                () -> prefetchSimilarCases(incident, run, baseContext), diagnosisPrefetchExecutor);
        CompletableFuture<String> metricTrends = CompletableFuture.supplyAsync(
                () -> prefetchMetricTrends(incident, run, baseContext), diagnosisPrefetchExecutor);
        context = mergePrefetchContexts(baseContext, similarCases, metricTrends);
        if (!context.equals(baseContext)) {
            incidentService.updateRunAlertContext(incident.getId(), run.getRunId(), context);
        }
        if (cancelled(job)) {
            return;
        }

        ToolCallback[] callbacks = tools == null ? new ToolCallback[0] : tools.getToolCallbacks();
        try {
            Optional<OverAllState> state = aiOpsService.executeAiOpsAnalysis(
                    chatModel, callbacks, context, incidentId, runId);
            if (cancelled(job)) {
                return;
            }
            if (state.isEmpty()) {
                throw new IllegalStateException("告警分析执行失败，未获取到有效结果");
            }
            Optional<String> report = aiOpsService.extractFinalReport(state.get());
            if (report.isEmpty()) {
                throw new IllegalStateException("多 Agent 流程完成，但未能提取最终报告");
            }
            incidentService.completeRun(incidentId, runId, report.get());
            storeAlertReport(alertId, report.get());
        } catch (Exception e) {
            if (job.getAttemptCount() >= job.getMaxAttempts() && !cancelled(job)) {
                String message = "告警分析异常: " + e.getMessage();
                incidentService.failRun(incidentId, runId, message);
                storeAlertReport(alertId, message);
            }
            throw e;
        }
    }

    private String prefetchSimilarCases(IncidentRecord incident, DiagnosisRunRecord run,
                                        String context) {
        if (incidentCaseService == null) {
            return context;
        }
        String enriched = incidentCaseService.prefetchAndAppend(incident, run, context);
        return enriched;
    }

    private String prefetchMetricTrends(IncidentRecord incident, DiagnosisRunRecord run,
                                        String context) {
        if (metricTrendPrefetchService == null) {
            return context;
        }
        String enriched = metricTrendPrefetchService.prefetchAndAppend(incident, run, context);
        return enriched;
    }

    private String mergePrefetchContexts(String baseContext,
                                         CompletableFuture<String> similarCases,
                                         CompletableFuture<String> metricTrends) {
        String base = baseContext == null ? "" : baseContext;
        StringBuilder merged = new StringBuilder(base);
        appendPrefetchDelta(merged, base, awaitPrefetch(similarCases, base));
        appendPrefetchDelta(merged, base, awaitPrefetch(metricTrends, base));
        return merged.toString();
    }

    private String awaitPrefetch(CompletableFuture<String> future, String fallback) {
        try {
            return future.join();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void appendPrefetchDelta(StringBuilder merged, String base, String enriched) {
        if (enriched == null || enriched.equals(base)) {
            return;
        }
        String delta = enriched.startsWith(base) ? enriched.substring(base.length()) : enriched;
        if (!delta.isBlank()) {
            merged.append(delta);
        }
    }

    private DiagnosisRunRecord findRun(String incidentId, String runId) {
        List<DiagnosisRunRecord> runs = incidentService.getDiagnosisRuns(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("Incident 不存在: " + incidentId));
        return runs.stream()
                .filter(run -> runId.equals(run.getRunId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DiagnosisRun 不存在: " + runId));
    }

    private boolean cancelled(BackgroundJobRecord job) {
        return jobRepository.isCancelRequested(job.getJobId());
    }

    private String required(JsonNode payload, String field) {
        String value = payload.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("后台任务缺少字段: " + field);
        }
        return value;
    }

    private void storeAlertReport(String alertId, String report) {
        if (alertService != null && alertId != null && !alertId.isBlank()) {
            alertService.storeReport(alertId, report);
        }
    }
}
