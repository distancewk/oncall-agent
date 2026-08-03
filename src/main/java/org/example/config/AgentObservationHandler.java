package org.example.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.example.service.ObservabilityMetrics;
import org.slf4j.MDC;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.observation.AiOperationMetadata;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Captures Spring AI model/tool observations without capturing content. */
@Component
public class AgentObservationHandler implements ObservationHandler<Observation.Context> {

    public static final String AI_OPERATION_MDC_KEY = "ai_operation";
    private static final String START_NANOS_KEY = AgentObservationHandler.class.getName() + ".startNanos";
    private static final String OPERATION_KEY = AgentObservationHandler.class.getName() + ".operation";

    private final ObservabilityMetrics metrics;
    private final TraceExportService traceExportService;

    public AgentObservationHandler(ObservabilityMetrics metrics,
                                   TraceExportService traceExportService) {
        this.metrics = metrics;
        this.traceExportService = traceExportService;
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext
                || context instanceof ToolCallingObservationContext;
    }

    @Override
    public void onStart(Observation.Context context) {
        context.put(START_NANOS_KEY, System.nanoTime());
        context.put(OPERATION_KEY, operationFromMdc());
    }

    @Override
    public void onStop(Observation.Context context) {
        Object startedValue = context.remove(START_NANOS_KEY);
        Long startedNanos = startedValue instanceof Long value ? value : null;
        long durationNanos = startedNanos == null
                ? 0L : Math.max(0L, System.nanoTime() - startedNanos);
        String operation = context.getOrDefault(OPERATION_KEY, "agentModelCall");
        Throwable error = context.getError();
        String outcome = error == null ? "SUCCESS" : "ERROR";

        if (context instanceof ChatModelObservationContext modelContext) {
            ChatResponse response = modelContext.getResponse();
            ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
            String model = metadata == null ? null : metadata.getModel();
            AiOperationMetadata operationMetadata = modelContext.getOperationMetadata();
            String provider = operationMetadata.provider();
            metrics.recordObservedModelCall(operation, provider, model, outcome, durationNanos, response);
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("ai.operation", operation);
            tags.put("ai.provider", provider == null ? "unknown" : provider);
            tags.put("ai.model", model == null ? "unknown" : model);
            tags.put("outcome", outcome);
            traceExportService.exportInternalSpan("gen_ai.chat", tags, startedNanos == null
                    ? System.nanoTime() : startedNanos, error);
            return;
        }

        if (!(context instanceof ToolCallingObservationContext toolContext)) {
            return;
        }
        String toolName = toolContext.getToolDefinition().name();
        metrics.recordObservedToolCall(toolName, outcome, durationNanos);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("tool.name", toolName);
        tags.put("outcome", outcome);
        traceExportService.exportInternalSpan("gen_ai.tool", tags, startedNanos == null
                ? System.nanoTime() : startedNanos, error);
    }

    private String operationFromMdc() {
        String operation = MDC.get(AI_OPERATION_MDC_KEY);
        return operation == null || operation.isBlank() ? "agentModelCall" : operation;
    }
}
