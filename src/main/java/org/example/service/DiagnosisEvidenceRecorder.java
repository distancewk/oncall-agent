package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.dto.DiagnosisEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class DiagnosisEvidenceRecorder {

    private static final Logger logger = LoggerFactory.getLogger(DiagnosisEvidenceRecorder.class);
    private static final int SUMMARY_LIMIT = 500;
    private static final int RAW_FRAGMENT_LIMIT = 6000;

    private final IncidentService incidentService;
    private final ObjectMapper objectMapper;
    private final ThreadLocal<ActiveRun> activeRun = new ThreadLocal<>();

    public DiagnosisEvidenceRecorder(IncidentService incidentService, ObjectMapper objectMapper) {
        this.incidentService = incidentService;
        this.objectMapper = objectMapper;
    }

    public <T> T withRun(String incidentId, String runId, RunSupplier<T> supplier) throws Exception {
        ActiveRun previous = activeRun.get();
        activeRun.set(new ActiveRun(incidentId, runId));
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

        markWaiting(run, safeToolName, safeQueryParams);

        String rawResult = null;
        boolean success = false;
        String errorMessage = null;
        RuntimeException runtimeFailure = null;

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
        addEvidence(run, evidence);

        if (runtimeFailure != null) {
            throw runtimeFailure;
        }

        return decorateResult(rawResult, evidence);
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

    private void markWaiting(ActiveRun run, String toolName, String queryParams) {
        try {
            incidentService.markRunWaitingTool(run.incidentId(), run.runId(), toolName, queryParams);
        } catch (RuntimeException e) {
            logger.warn("记录工具等待状态失败, incidentId: {}, runId: {}, tool: {}",
                    run.incidentId(), run.runId(), toolName, e);
        }
    }

    private void addEvidence(ActiveRun run, DiagnosisEvidence evidence) {
        try {
            incidentService.addToolEvidence(run.incidentId(), run.runId(), evidence);
        } catch (RuntimeException e) {
            logger.warn("保存诊断工具证据失败, incidentId: {}, runId: {}, evidenceId: {}",
                    run.incidentId(), run.runId(), evidence.getId(), e);
        }
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
        } catch (Exception ignored) {
            String normalized = rawResult.toLowerCase(Locale.ROOT).replace(" ", "");
            return !normalized.contains("\"success\":false");
        }
        return true;
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
        } catch (Exception ignored) {
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
                return objectMapper.writeValueAsString(objectNode);
            }
        } catch (Exception ignored) {
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

    @FunctionalInterface
    public interface RunSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ToolCallSupplier {
        String get() throws Exception;
    }

    private record ActiveRun(String incidentId, String runId) {
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
            return definition == null ? "externalTool" : definition.name();
        }
    }
}
