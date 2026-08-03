package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

/**
 * Code-owned interpretation of Planner output.
 *
 * <p>The model may suggest a next action, but it cannot directly decide which
 * agent is invoked. Invalid or unsafe control output is treated as a stop
 * signal and is routed to the evidence-constrained finalizer.</p>
 */
public final class DiagnosisOrchestrationPolicy {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "queryMetricTrend",
            "queryLogs",
            "queryInternalDocs",
            "queryPrometheusAlerts",
            "getAvailableLogTopics",
            "getCurrentDateTime"
    );
    private static final Set<String> PLANNER_FIELDS = Set.of(
            "decision", "step", "tool", "parameters");
    private static final Set<String> EXECUTOR_FIELDS = Set.of(
            "status", "summary", "evidence", "nextHint");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("id", "claim");
    private static final int MAX_EVIDENCE_ITEMS = 32;
    private static final int MAX_CLAIM_LENGTH = 1_000;
    private static final int MAX_SUMMARY_LENGTH = 2_000;
    private static final int MAX_NEXT_HINT_LENGTH = 500;

    private final ObjectMapper objectMapper;

    public DiagnosisOrchestrationPolicy() {
        this(new ObjectMapper());
    }

    public DiagnosisOrchestrationPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public Decision classify(String plannerOutput) {
        if (DiagnosisReportService.isFinalReportCandidate(plannerOutput)) {
            return new Decision(Action.FINISH_REPORT, "final report candidate");
        }
        JsonNode json = parseObject(plannerOutput);
        if (json == null) {
            return new Decision(Action.STOP_AND_FINALIZE, "planner output is not valid control JSON");
        }
        if (!hasOnlyFields(json, PLANNER_FIELDS)) {
            return new Decision(Action.STOP_AND_FINALIZE,
                    "planner control JSON contains unsupported fields");
        }
        String decision = json.path("decision").asText("").trim().toUpperCase(Locale.ROOT);
        return switch (decision) {
            case "PLAN" -> validatedAction(json, Action.PLAN, "planner requested replanning", false);
            case "EXECUTE" -> validatedAction(json, Action.EXECUTE, "planner requested executor", true);
            case "FINISH" -> new Decision(Action.STOP_AND_FINALIZE, "planner requested finalization");
            default -> new Decision(Action.STOP_AND_FINALIZE, "unknown planner decision");
        };
    }

    public boolean requestsStop(String output) {
        if (output == null || output.isBlank()) {
            return true;
        }
        String normalized = output.toUpperCase(Locale.ROOT);
        return normalized.contains("STOP_DIAGNOSTIC_TOOLS")
                || normalized.contains("TOOL_BUDGET_EXCEEDED")
                || normalized.contains("UNSUPPORTED_METRIC")
                || normalized.matches("(?s).*\"STOP\"\\s*:\\s*TRUE.*");
    }

    public boolean isValidExecutorFeedback(String output) {
        return isValidExecutorFeedback(output, null);
    }

    /**
     * Validates executor feedback and, when supplied, binds every referenced
     * evidence ID to the successful evidence IDs of the active DiagnosisRun.
     * A null set means the caller is operating without an active recorder and
     * can only enforce the structural contract.
     */
    public boolean isValidExecutorFeedback(String output, Set<String> usableEvidenceIds) {
        JsonNode json = parseObject(output);
        if (json == null || !hasOnlyFields(json, EXECUTOR_FIELDS)) {
            return false;
        }
        String status = json.path("status").asText("").trim().toUpperCase(Locale.ROOT);
        JsonNode summary = json.get("summary");
        JsonNode evidence = json.get("evidence");
        if (!(status.equals("SUCCESS") || status.equals("FAILED") || status.equals("PARTIAL"))
                || summary == null || !summary.isTextual() || summary.asText().isBlank()
                || summary.asText().length() > MAX_SUMMARY_LENGTH
                || evidence == null || !evidence.isArray()
                || evidence.size() > MAX_EVIDENCE_ITEMS) {
            return false;
        }
        Set<String> referencedIds = new HashSet<>();
        for (JsonNode item : evidence) {
            if (!item.isObject()
                    || !hasOnlyFields(item, EVIDENCE_FIELDS)
                    || !item.path("id").isTextual()
                    || !item.path("id").asText().matches("ev-[A-Za-z0-9_-]{1,128}")
                    || !item.path("claim").isTextual()
                    || item.path("claim").asText().isBlank()
                    || item.path("claim").asText().length() > MAX_CLAIM_LENGTH
                    || !referencedIds.add(item.path("id").asText())
                    || (usableEvidenceIds != null
                    && !usableEvidenceIds.contains(item.path("id").asText()))) {
                return false;
            }
        }
        JsonNode nextHint = json.get("nextHint");
        return nextHint == null || (nextHint.isTextual()
                && nextHint.asText().length() <= MAX_NEXT_HINT_LENGTH);
    }

    private JsonNode parseObject(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String candidate = output.trim();
        JsonNode parsed = readTree(candidate);
        return parsed != null && parsed.isObject() ? parsed : null;
    }

    private Decision validatedAction(JsonNode json, Action action, String reason,
                                     boolean toolRequired) {
        JsonNode stepNode = json.get("step");
        if (stepNode == null || !stepNode.isTextual()) {
            return new Decision(Action.STOP_AND_FINALIZE,
                    "planner decision step must be a string");
        }
        String step = stepNode.asText().trim();
        if (step.isBlank() || step.length() > 500) {
            return new Decision(Action.STOP_AND_FINALIZE,
                    "planner decision missing a bounded step");
        }
        JsonNode toolNode = json.get("tool");
        if (toolNode != null && !toolNode.isTextual()) {
            return new Decision(Action.STOP_AND_FINALIZE,
                    "planner decision tool must be a string");
        }
        String tool = toolNode == null ? "" : toolNode.asText().trim();
        if (toolRequired && tool.isBlank()) {
            return new Decision(Action.STOP_AND_FINALIZE,
                    "executor decision missing a tool");
        }
        if (!tool.isBlank() && !ALLOWED_TOOLS.contains(tool)) {
            return new Decision(Action.STOP_AND_FINALIZE,
                    "planner requested an unsupported tool");
        }
        JsonNode parameters = json.get("parameters");
        if (parameters != null && !parameters.isObject()) {
            return new Decision(Action.STOP_AND_FINALIZE,
                    "planner parameters must be an object");
        }
        return new Decision(action, reason, step, tool);
    }

    private JsonNode readTree(String value) {
        try {
            ObjectReader reader = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            return reader.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean hasOnlyFields(JsonNode object, Set<String> allowedFields) {
        var fields = object.fieldNames();
        while (fields.hasNext()) {
            if (!allowedFields.contains(fields.next())) {
                return false;
            }
        }
        return true;
    }

    public enum Action {
        PLAN,
        EXECUTE,
        FINISH_REPORT,
        STOP_AND_FINALIZE
    }

    public record Decision(Action action, String reason, String step, String tool) {
        public Decision(Action action, String reason) {
            this(action, reason, "", "");
        }
    }
}
