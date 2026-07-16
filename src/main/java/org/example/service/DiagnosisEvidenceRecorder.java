package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.config.AppIncidentProperties;
import org.example.dto.DiagnosisEvidence;
import org.example.dto.DiagnosisRunRecord;
import org.example.exception.DependencyUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@Service
public class DiagnosisEvidenceRecorder {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosisEvidenceRecorder.class);
    private static final int SUMMARY_LIMIT = 500;
    private static final int RAW_FRAGMENT_LIMIT = 6000;
    private static final String QUERY_LOGS_TOOL = "queryLogs";
    private static final String QUERY_INTERNAL_DOCS_TOOL = "queryInternalDocs";
    private static final String QUERY_METRIC_TREND_TOOL = "queryMetricTrend";
    private static final String ERROR_DUPLICATE = "TOOL_DUPLICATE_SKIPPED";
    private static final String ERROR_BUDGET = "TOOL_BUDGET_EXCEEDED";

    private final IncidentService incidentService;
    private final ObjectMapper objectMapper;
    private final AppIncidentProperties incidentProperties;
    private final ToolCallAttemptContext attemptContext;
    private final ThreadLocal<ActiveRun> activeRun = new ThreadLocal<>();

    public DiagnosisEvidenceRecorder(IncidentService incidentService, ObjectMapper objectMapper) {
        this(incidentService, objectMapper, new AppIncidentProperties(), new ToolCallAttemptContext());
    }

    public DiagnosisEvidenceRecorder(IncidentService incidentService,
                                     ObjectMapper objectMapper,
                                     AppIncidentProperties incidentProperties) {
        this(incidentService, objectMapper, incidentProperties, new ToolCallAttemptContext());
    }

    @Autowired
    public DiagnosisEvidenceRecorder(IncidentService incidentService,
                                     ObjectMapper objectMapper,
                                     AppIncidentProperties incidentProperties,
                                     ToolCallAttemptContext attemptContext) {
        this.incidentService = incidentService;
        this.objectMapper = objectMapper;
        this.incidentProperties = incidentProperties == null ? new AppIncidentProperties() : incidentProperties;
        this.attemptContext = attemptContext == null ? new ToolCallAttemptContext() : attemptContext;
    }

    public <T> T withRun(String incidentId, String runId, RunSupplier<T> supplier) throws Exception {
        ActiveRun previous = activeRun.get();
        Optional<DiagnosisRunRecord> snapshot = incidentService.getDiagnosisRun(incidentId, runId);
        activeRun.set(new ActiveRun(incidentId, runId,
                snapshot.map(DiagnosisRunRecord::getVersion).orElse(-1L),
                snapshot.map(DiagnosisRunRecord::getEvidence).map(ArrayList::new).orElseGet(ArrayList::new),
                snapshot.isPresent()));
        try {
            return supplier.get();
        } finally {
            if (previous == null) {
                activeRun.remove();
            } else {
                activeRun.set(previous);
            }
        }
    }

    public String recordToolCall(String toolName, String queryParams, String timeRange, ToolCallSupplier supplier) {
        ActiveRun run = activeRun.get();
        if (run == null) {
            return callWithoutRecording(supplier);
        }

        String safeToolName = blankToDefault(toolName, "unknownTool");
        String safeQueryParams = blankToDefault(queryParams, "{}");
        String safeTimeRange = blankToDefault(timeRange, "未指定");

        Optional<String> policyError = toolPolicyError(run, safeToolName, safeQueryParams);
        if (policyError.isPresent()) {
            String errorCode = policyError.get();
            String message = ERROR_DUPLICATE.equals(errorCode)
                    ? "重复工具调用已跳过: " + safeToolName
                    : budgetMessage(safeToolName);
            return recordSkippedToolCall(run, safeToolName, safeQueryParams, safeTimeRange, message, errorCode);
        }

        if (!markWaiting(run, safeToolName, safeQueryParams)) {
            throw new IllegalStateException("诊断工具状态写入失败");
        }

        String rawResult = null;
        boolean success = false;
        String errorMessage = null;
        RuntimeException runtimeFailure = null;
        long startedAt = System.nanoTime();

        try {
            rawResult = supplier.get();
            success = detectSuccess(rawResult);
        } catch (RuntimeException e) {
            runtimeFailure = e;
            rawResult = "";
            errorMessage = e.getMessage();
        } catch (Exception e) {
            runtimeFailure = new IllegalStateException(e);
            rawResult = "";
            errorMessage = e.getMessage();
        }

        long durationMs = elapsedMillis(startedAt);
        int attemptCount = attemptContext.consumeLastAttempts();
        DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                safeToolName,
                safeQueryParams,
                safeTimeRange,
                summarize(rawResult, errorMessage),
                truncate(rawResult, RAW_FRAGMENT_LIMIT),
                success,
                errorMessage,
                System.currentTimeMillis()
        );
        evidence.setErrorCode(extractErrorCode(rawResult, runtimeFailure));
        evidence.setAttemptCount(attemptCount);
        evidence.setDurationMs(durationMs);
        evidence.setRetryable(isRetryableTool(safeToolName) || attemptCount > 1);
        if (!addEvidence(run, evidence)) {
            throw new IllegalStateException("诊断证据写入失败");
        }

        if (runtimeFailure != null) {
            throw runtimeFailure;
        }

        return decorateResult(rawResult, evidence);
    }

    private Optional<String> toolPolicyError(ActiveRun run, String toolName, String queryParams) {
        List<DiagnosisEvidence> currentEvidence = currentEvidence(run);
        if (incidentProperties.isToolCallDeduplicationEnabled()) {
            String currentKey = toolCallKey(toolName, queryParams);
            boolean duplicate = currentEvidence.stream()
                    .filter(this::isCountableToolEvidence)
                    .anyMatch(evidence -> currentKey.equals(toolCallKey(evidence.getToolName(), evidence.getQueryParams())));
            if (duplicate) {
                return Optional.of(ERROR_DUPLICATE);
            }
        }

        int maxToolCalls = incidentProperties.getMaxToolCallsPerRun();
        if (maxToolCalls >= 0) {
            long existingToolCalls = currentEvidence.stream()
                    .filter(this::isCountableToolEvidence)
                    .count();
            if (existingToolCalls >= maxToolCalls) {
                return Optional.of(ERROR_BUDGET);
            }
        }

        int maxQueryLogsCalls = incidentProperties.getQueryLogsMaxCallsPerRun();
        if (QUERY_LOGS_TOOL.equals(toolName) && maxQueryLogsCalls >= 0) {
            long existingQueryLogsCalls = countToolCalls(currentEvidence, QUERY_LOGS_TOOL::equals);
            if (existingQueryLogsCalls >= maxQueryLogsCalls) {
                return Optional.of(ERROR_BUDGET);
            }
        }

        int maxQueryInternalDocsCalls = incidentProperties.getQueryInternalDocsMaxCallsPerRun();
        if (QUERY_INTERNAL_DOCS_TOOL.equals(toolName)
                && maxQueryInternalDocsCalls >= 0
                && countToolCalls(currentEvidence, QUERY_INTERNAL_DOCS_TOOL::equals) >= maxQueryInternalDocsCalls) {
            return Optional.of(ERROR_BUDGET);
        }

        int maxTavilyCalls = incidentProperties.getTavilyMaxCallsPerRun();
        if (containsIgnoreCase(toolName, "tavily")
                && maxTavilyCalls >= 0
                && countToolCalls(currentEvidence, name -> containsIgnoreCase(name, "tavily")) >= maxTavilyCalls) {
            return Optional.of(ERROR_BUDGET);
        }

        int maxDbhubCalls = incidentProperties.getDbhubMaxCallsPerRun();
        if (isDbhubToolName(toolName)
                && maxDbhubCalls >= 0
                && countToolCalls(currentEvidence, this::isDbhubToolName) >= maxDbhubCalls) {
            return Optional.of(ERROR_BUDGET);
        }
        return Optional.empty();
    }

    private long countToolCalls(List<DiagnosisEvidence> evidence, Predicate<String> toolMatcher) {
        return evidence.stream()
                .filter(this::isCountableToolEvidence)
                .map(DiagnosisEvidence::getToolName)
                .filter(name -> name != null && toolMatcher.test(name))
                .count();
    }

    private boolean isDbhubToolName(String toolName) {
        return containsIgnoreCase(toolName, "dbhub")
                || containsIgnoreCase(toolName, "execute_sql")
                || containsIgnoreCase(toolName, "list_tables")
                || containsIgnoreCase(toolName, "describe_table");
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private String recordSkippedToolCall(ActiveRun run,
                                         String toolName,
                                         String queryParams,
                                         String timeRange,
                                         String message,
                                         String errorCode) {
        String rawResult = policyErrorJson(toolName, message, errorCode);
        DiagnosisEvidence evidence = DiagnosisEvidence.toolCall(
                toolName,
                queryParams,
                timeRange,
                message,
                rawResult,
                false,
                message,
                System.currentTimeMillis()
        );
        evidence.setErrorCode(errorCode);
        evidence.setAttemptCount(0);
        evidence.setDurationMs(0);
        evidence.setRetryable(false);
        addEvidence(run, evidence);
        logger.info("诊断工具调用被策略拦截, incidentId: {}, runId: {}, tool: {}, errorCode: {}",
                run.incidentId(), run.runId(), toolName, errorCode);
        return decorateResult(rawResult, evidence);
    }

    private String policyErrorJson(String toolName, String message, String errorCode) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("success", false);
        node.put("tool", toolName);
        node.put("message", message);
        node.put("error", message);
        node.put("errorCode", errorCode);
        return node.toString();
    }

    private String budgetMessage(String toolName) {
        if (QUERY_LOGS_TOOL.equals(toolName)) {
            return toolName + " 调用次数已达上限，本次跳过";
        }
        return "工具调用总次数已达上限，本次跳过: " + toolName;
    }

    private List<DiagnosisEvidence> currentEvidence(ActiveRun run) {
        if (run.incremental()) {
            return new ArrayList<>(run.evidence());
        }
        try {
            Optional<List<DiagnosisRunRecord>> runs = incidentService.getDiagnosisRuns(run.incidentId());
            if (runs == null || runs.isEmpty()) {
                return List.of();
            }
            return runs.get().stream()
                    .filter(item -> run.runId().equals(item.getRunId()))
                    .findFirst()
                    .map(DiagnosisRunRecord::getEvidence)
                    .orElse(List.of());
        } catch (RuntimeException e) {
            logger.warn("读取当前诊断证据失败，将跳过工具预算检查, incidentId: {}, runId: {}",
                    run.incidentId(), run.runId(), e);
            return List.of();
        }
    }

    private boolean isToolEvidence(DiagnosisEvidence evidence) {
        return evidence != null && ("tool_call".equals(evidence.getType()) || evidence.getToolName() != null);
    }

    private boolean isCountableToolEvidence(DiagnosisEvidence evidence) {
        return isToolEvidence(evidence) && !isPolicySkippedEvidence(evidence);
    }

    private boolean isPolicySkippedEvidence(DiagnosisEvidence evidence) {
        String errorCode = evidence.getErrorCode();
        return !evidence.isSuccess()
                && evidence.getAttemptCount() == 0
                && (ERROR_DUPLICATE.equals(errorCode) || ERROR_BUDGET.equals(errorCode));
    }

    private String toolCallKey(String toolName, String queryParams) {
        String safeToolName = blankToDefault(toolName, "unknownTool");
        return safeToolName + "|" + semanticKey(safeToolName, queryParams);
    }

    private String semanticKey(String toolName, String queryParams) {
        try {
            JsonNode node = objectMapper.readTree(blankToDefault(queryParams, "{}"));
            if (QUERY_METRIC_TREND_TOOL.equals(toolName) && node.isObject()) {
                return joinFields(node, "metric", "service", "instance", "window");
            }
            if (QUERY_LOGS_TOOL.equals(toolName) && node.isObject()) {
                return joinFields(node, "logTopic", "topic", "query", "service", "instance", "level");
            }
            if ("getAvailableLogTopics".equals(toolName)) {
                return "all";
            }
        } catch (JsonProcessingException ignored) {
            // Fall back to exact canonicalization below.
        }
        return canonicalize(queryParams);
    }

    private String joinFields(JsonNode node, String... fields) {
        StringBuilder builder = new StringBuilder();
        for (String field : fields) {
            if (!builder.isEmpty()) {
                builder.append('|');
            }
            JsonNode value = node.get(field);
            builder.append(field).append('=').append(normalizedText(value));
        }
        return builder.toString();
    }

    private String normalizedText(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) {
            return "";
        }
        String text = value.isTextual() ? value.asText() : value.toString();
        return text.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private String canonicalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return canonicalizeNode(objectMapper.readTree(value));
        } catch (Exception ignored) {
            return value.trim();
        }
    }

    private String canonicalizeNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            StringBuilder builder = new StringBuilder("{");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                String field = fields.get(i);
                builder.append(quote(field)).append(':').append(canonicalizeNode(node.get(field)));
            }
            return builder.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                builder.append(canonicalizeNode(node.get(i)));
            }
            return builder.append(']').toString();
        }
        return node.toString();
    }

    private String quote(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ignored) {
            return "\"" + value + "\"";
        }
    }

    public ToolCallback[] wrapToolCallbacks(ToolCallback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) {
            return new ToolCallback[0];
        }
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = callbacks[i] == null ? null : new RecordingToolCallback(callbacks[i]);
        }
        return wrapped;
    }

    private String callWithoutRecording(ToolCallSupplier supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean markWaiting(ActiveRun run, String toolName, String queryParams) {
        if (run.incremental()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                long nextVersion = incidentService.updateRunToolStateIncremental(
                        run.incidentId(), run.runId(), run.version(), "WAITING_TOOL", toolName,
                        "正在调用工具 " + toolName, queryParams);
                if (nextVersion > run.version()) {
                    run.setVersion(nextVersion);
                    return true;
                }
                if (!refresh(run)) {
                    return false;
                }
            }
            return false;
        }
        try {
            incidentService.markRunWaitingTool(run.incidentId(), run.runId(), toolName, queryParams);
            return true;
        } catch (RuntimeException e) {
            logger.warn("记录工具等待状态失败, incidentId: {}, runId: {}, tool: {}",
                    run.incidentId(), run.runId(), toolName, e);
            return false;
        }
    }

    private boolean addEvidence(ActiveRun run, DiagnosisEvidence evidence) {
        if (run.incremental()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                long nextVersion = incidentService.appendToolEvidenceIncremental(
                        run.incidentId(), run.runId(), run.version(), evidence);
                if (nextVersion > run.version()) {
                    run.setVersion(nextVersion);
                    run.evidence().add(evidence);
                    return true;
                }
                if (!refresh(run)) {
                    return false;
                }
            }
            return false;
        }
        try {
            incidentService.addToolEvidence(run.incidentId(), run.runId(), evidence);
            return true;
        } catch (RuntimeException e) {
            logger.warn("保存诊断工具证据失败, incidentId: {}, runId: {}, evidenceId: {}",
                    run.incidentId(), run.runId(), evidence.getId(), e);
            return false;
        }
    }

    private boolean refresh(ActiveRun run) {
        Optional<DiagnosisRunRecord> snapshot = incidentService.getDiagnosisRun(run.incidentId(), run.runId());
        if (snapshot.isEmpty()) {
            return false;
        }
        run.setVersion(snapshot.get().getVersion());
        run.evidence().clear();
        run.evidence().addAll(snapshot.get().getEvidence());
        return true;
    }

    private boolean detectSuccess(String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return true;
        }
        try {
            JsonNode json = objectMapper.readTree(rawResult);
            JsonNode success = json.get("success");
            if (success != null && success.isBoolean()) {
                return success.asBoolean();
            }
            JsonNode status = json.get("status");
            if (status != null && status.isTextual()) {
                String value = status.asText().toLowerCase(Locale.ROOT);
                return !("error".equals(value) || "failed".equals(value) || "failure".equals(value));
            }
        } catch (JsonProcessingException ignored) {
            String normalized = rawResult.toLowerCase(Locale.ROOT).replace(" ", "");
            return !normalized.contains("\"success\":false");
        }
        return true;
    }

    private String extractErrorCode(String rawResult, RuntimeException runtimeFailure) {
        if (runtimeFailure instanceof DependencyUnavailableException unavailable) {
            return unavailable.getErrorCode();
        }
        if (rawResult == null || rawResult.isBlank()) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(rawResult);
            JsonNode errorCode = json.get("errorCode");
            if (errorCode != null && errorCode.isTextual() && !errorCode.asText().isBlank()) {
                return errorCode.asText();
            }
        } catch (JsonProcessingException ignored) {
            return null;
        }
        return null;
    }

    private String summarize(String rawResult, String errorMessage) {
        if (errorMessage != null && !errorMessage.isBlank()) {
            return truncate("工具调用失败: " + errorMessage, SUMMARY_LIMIT);
        }
        if (rawResult == null || rawResult.isBlank()) {
            return "工具调用完成，返回为空";
        }
        try {
            JsonNode json = objectMapper.readTree(rawResult);
            JsonNode message = json.get("message");
            if (message != null && message.isTextual() && !message.asText().isBlank()) {
                return truncate(message.asText(), SUMMARY_LIMIT);
            }
            JsonNode status = json.get("status");
            if (status != null && status.isTextual() && !status.asText().isBlank()) {
                return truncate("status=" + status.asText(), SUMMARY_LIMIT);
            }
        } catch (JsonProcessingException ignored) {
            // Fall back to text truncation below.
        }
        return truncate(rawResult, SUMMARY_LIMIT);
    }

    private String decorateResult(String rawResult, DiagnosisEvidence evidence) {
        if (rawResult == null) {
            return null;
        }
        try {
            JsonNode json = objectMapper.readTree(rawResult);
            if (json instanceof ObjectNode objectNode) {
                objectNode.put("_diagnosisEvidenceId", evidence.getId());
                objectNode.put("_diagnosisEvidenceSuccess", evidence.isSuccess());
                objectNode.put("_diagnosisEvidenceAttemptCount", evidence.getAttemptCount());
                objectNode.put("_diagnosisEvidenceDurationMs", evidence.getDurationMs());
                return objectMapper.writeValueAsString(objectNode);
            }
        } catch (JsonProcessingException ignored) {
            // Plain text tool result, append an evidence footer.
        }
        return rawResult + "\n\n诊断证据ID: " + evidence.getId();
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "...";
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
    }

    private boolean isRetryableTool(String toolName) {
        String normalized = blankToDefault(toolName, "").toLowerCase(Locale.ROOT);
        Set<String> retryableTools = Set.of(
                "querymetrictrend",
                "queryprometheusalerts",
                "querylogs",
                "queryinternaldocs"
        );
        return retryableTools.contains(normalized)
                || normalized.contains("tavily")
                || normalized.contains("dbhub")
                || normalized.contains("mcp");
    }

    @FunctionalInterface
    public interface RunSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ToolCallSupplier {
        String get() throws Exception;
    }

    private static final class ActiveRun {
        private final String incidentId;
        private final String runId;
        private final List<DiagnosisEvidence> evidence;
        private final boolean incremental;
        private long version;

        private ActiveRun(String incidentId,
                          String runId,
                          long version,
                          List<DiagnosisEvidence> evidence,
                          boolean incremental) {
            this.incidentId = incidentId;
            this.runId = runId;
            this.version = version;
            this.evidence = evidence;
            this.incremental = incremental;
        }

        private String incidentId() { return incidentId; }
        private String runId() { return runId; }
        private List<DiagnosisEvidence> evidence() { return evidence; }
        private boolean incremental() { return incremental; }
        private long version() { return version; }
        private void setVersion(long version) { this.version = version; }
    }

    private class RecordingToolCallback implements ToolCallback {

        private final ToolCallback delegate;

        private RecordingToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return recordToolCall(toolName(), toolInput, "外部工具调用", () -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return recordToolCall(toolName(), toolInput, "外部工具调用", () -> delegate.call(toolInput, toolContext));
        }

        private String toolName() {
            ToolDefinition definition = delegate.getToolDefinition();
            if (definition == null || definition.name() == null || definition.name().isBlank()) {
                return "externalTool";
            }
            return definition.name();
        }
    }
}
