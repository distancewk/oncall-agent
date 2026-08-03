package org.example.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Gauge;
import org.example.config.AppObservabilityProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

/**
 * Low-cardinality runtime metrics for diagnosis, jobs and dependency calls.
 * No prompt, report, token, log fragment, or user-controlled identifier is used
 * as a metric tag.
 */
@Component
public class ObservabilityMetrics {

    private final MeterRegistry registry;
    private final AppObservabilityProperties properties;
    private final ConcurrentMap<String, AtomicLong> queueDepths = new ConcurrentHashMap<>();

    public ObservabilityMetrics(MeterRegistry registry) {
        this(registry, new AppObservabilityProperties());
    }

    @Autowired
    public ObservabilityMetrics(MeterRegistry registry, AppObservabilityProperties properties) {
        this.registry = registry;
        this.properties = properties == null ? new AppObservabilityProperties() : properties;
    }

    public void recordDiagnosisStarted() {
        registry.counter("superbizagent.diagnosis.started").increment();
    }

    public void recordDiagnosisOutcome(String outcome, long durationNanos) {
        registry.counter("superbizagent.diagnosis.outcomes", "outcome", safeOutcome(outcome))
                .increment();
        Timer.builder("superbizagent.diagnosis.duration")
                .description("Diagnosis execution duration")
                .publishPercentileHistogram()
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    public void recordJobStarted(String jobType) {
        registry.counter("superbizagent.jobs.started", "job_type", safeJobType(jobType)).increment();
    }

    public void recordJobOutcome(String jobType, String outcome) {
        registry.counter("superbizagent.jobs.outcomes",
                        "job_type", safeJobType(jobType),
                        "outcome", safeOutcome(outcome))
                .increment();
    }

    public void recordJobOutcome(String jobType, String outcome, long durationNanos) {
        recordJobOutcome(jobType, outcome);
        Timer.builder("superbizagent.jobs.duration")
                .description("Background job execution duration")
                .publishPercentileHistogram()
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    public void recordJobQueueDepth(String jobType, long depth) {
        String safeType = safeJobType(jobType);
        AtomicLong gauge = queueDepths.computeIfAbsent(safeType, type -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder("superbizagent.jobs.queue.depth", value, AtomicLong::get)
                    .description("Ready background jobs waiting to be claimed")
                    .tag("job_type", type)
                    .register(registry);
            return value;
        });
        gauge.set(Math.max(0L, depth));
    }

    public void recordJobClaimDelay(String jobType, long delayMillis) {
        Timer.builder("superbizagent.jobs.claim.delay")
                .description("Delay between a job becoming available and being claimed")
                .publishPercentileHistogram()
                .tag("job_type", safeJobType(jobType))
                .register(registry)
                .record(Math.max(0L, delayMillis), TimeUnit.MILLISECONDS);
    }

    public void recordDependencyCall(String dependency, String outcome) {
        registry.counter("superbizagent.dependencies.calls",
                        "dependency", safeDependency(dependency),
                        "outcome", safeOutcome(outcome))
                .increment();
    }

    public void recordDependencyProbe(String dependency, String outcome, long latencyMs) {
        String safeDependency = safeDependency(dependency);
        String safeOutcome = safeProbeOutcome(outcome);
        registry.counter("superbizagent.dependencies.probes",
                        "dependency", safeDependency,
                        "outcome", safeOutcome)
                .increment();
        Timer.builder("superbizagent.dependencies.probe.duration")
                .description("Explicit dependency connectivity probe duration")
                .publishPercentileHistogram()
                .tag("dependency", safeDependency)
                .register(registry)
                .record(Math.max(0L, latencyMs), TimeUnit.MILLISECONDS);
    }

    /**
     * Records a model invocation and, when the provider returned usage metadata,
     * its prompt/completion tokens and estimated cost. Missing provider usage is
     * recorded separately; it must never be mistaken for zero-token usage.
     */
    public void recordModelCall(String operation,
                                String model,
                                String outcome,
                                long durationNanos,
                                ChatResponse response) {
        String safeOperation = safeModelOperation(operation);
        String responseModel = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getModel();
        String safeModel = safeModel(model == null || model.isBlank() ? responseModel : model);
        recordModelBase(safeOperation, safeModel, outcome, durationNanos);

        if (!properties.isModelUsageEnabled()) {
            return;
        }
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        if (usage == null) {
            registry.counter("superbizagent.model.usage.unknown",
                            "operation", safeOperation,
                            "model", safeModel)
                    .increment();
            return;
        }
        int promptTokens = nonNegative(usage.getPromptTokens());
        int completionTokens = nonNegative(usage.getCompletionTokens());
        recordModelTokens(safeOperation, safeModel, "prompt", promptTokens);
        recordModelTokens(safeOperation, safeModel, "completion", completionTokens);

        double estimatedCost = (promptTokens / 1_000.0d) * properties.getInputCostPer1kTokens()
                + (completionTokens / 1_000.0d) * properties.getOutputCostPer1kTokens();
        if (estimatedCost > 0.0d) {
            registry.counter("superbizagent.model.estimated_cost",
                            "operation", safeOperation,
                            "model", safeModel,
                            "currency", safeCurrency(properties.getCurrency()))
                    .increment(estimatedCost);
        } else {
            registry.counter("superbizagent.model.cost.unknown",
                            "operation", safeOperation,
                            "model", safeModel,
                            "currency", safeCurrency(properties.getCurrency()))
                    .increment();
        }
    }

    public void recordModelInvocation(String operation,
                                      String model,
                                      String outcome,
                                      long durationNanos) {
        String safeOperation = safeModelOperation(operation);
        String safeModel = safeModel(model == null || model.isBlank()
                ? properties.getDefaultModel() : model);
        recordModelBase(safeOperation, safeModel, outcome, durationNanos);
        if (properties.isModelUsageEnabled()) {
            registry.counter("superbizagent.model.usage.unknown",
                            "operation", safeOperation,
                            "model", safeModel)
                    .increment();
        }
    }

    /** Records usage from the framework observation, separate from legacy call counters. */
    public void recordObservedModelCall(String operation,
                                        String provider,
                                        String model,
                                        String outcome,
                                        long durationNanos,
                                        ChatResponse response) {
        String safeOperation = safeModelOperation(operation);
        String safeProvider = safeProvider(provider);
        String safeModel = safeModel(model == null || model.isBlank()
                ? properties.getDefaultModel() : model);
        registry.counter("superbizagent.agent.model.calls",
                        "operation", safeOperation,
                        "provider", safeProvider,
                        "model", safeModel,
                        "outcome", safeOutcome(outcome))
                .increment();
        Timer.builder("superbizagent.agent.model.duration")
                .description("Spring AI agent model observation duration")
                .publishPercentileHistogram()
                .tag("operation", safeOperation)
                .tag("provider", safeProvider)
                .tag("model", safeModel)
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
        if (!properties.isModelUsageEnabled()) {
            return;
        }
        Usage usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        if (usage == null) {
            registry.counter("superbizagent.agent.model.usage.unknown",
                            "operation", safeOperation,
                            "provider", safeProvider,
                            "model", safeModel)
                    .increment();
            return;
        }
        int promptTokens = nonNegative(usage.getPromptTokens());
        int completionTokens = nonNegative(usage.getCompletionTokens());
        registry.counter("superbizagent.agent.model.tokens",
                        "operation", safeOperation,
                        "provider", safeProvider,
                        "model", safeModel,
                        "direction", "prompt")
                .increment(promptTokens);
        registry.counter("superbizagent.agent.model.tokens",
                        "operation", safeOperation,
                        "provider", safeProvider,
                        "model", safeModel,
                        "direction", "completion")
                .increment(completionTokens);
    }

    public void recordObservedToolCall(String toolName, String outcome, long durationNanos) {
        String safeTool = safeTool(toolName);
        registry.counter("superbizagent.agent.tool.calls",
                        "tool", safeTool,
                        "outcome", safeOutcome(outcome))
                .increment();
        Timer.builder("superbizagent.agent.tool.duration")
                .description("Spring AI agent tool observation duration")
                .publishPercentileHistogram()
                .tag("tool", safeTool)
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    private void recordModelBase(String operation, String model, String outcome, long durationNanos) {
        registry.counter("superbizagent.model.calls",
                        "operation", operation,
                        "model", model,
                        "outcome", safeOutcome(outcome))
                .increment();
        Timer.builder("superbizagent.model.duration")
                .description("Model invocation duration")
                .publishPercentileHistogram()
                .tag("operation", operation)
                .tag("model", model)
                .register(registry)
                .record(Math.max(0L, durationNanos), TimeUnit.NANOSECONDS);
    }

    private void recordModelTokens(String operation, String model, String direction, int tokens) {
        registry.counter("superbizagent.model.tokens",
                        "operation", operation,
                        "model", model,
                        "direction", direction)
                .increment(tokens);
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    public void recordToolCall(String toolName, String outcome) {
        registry.counter("superbizagent.diagnosis.tool_calls",
                        "tool", safeTool(toolName),
                        "outcome", safeOutcome(outcome))
                .increment();
    }

    public void recordDiagnosisQuality(String grade, boolean evidenceGap) {
        registry.counter("superbizagent.diagnosis.quality",
                        "grade", safeGrade(grade),
                        "evidence_gap", Boolean.toString(evidenceGap))
                .increment();
    }

    private String safeOutcome(String outcome) {
        if (outcome == null) {
            return "OTHER";
        }
        return switch (outcome) {
            case "COMPLETED", "FAILED", "CANCELLED", "SUCCESS", "FALLBACK", "CIRCUIT_OPEN", "ERROR" -> outcome;
            default -> "OTHER";
        };
    }

    private String safeProbeOutcome(String outcome) {
        if (outcome == null) {
            return "OTHER";
        }
        return switch (outcome) {
            case "UP", "DOWN", "DEGRADED", "AUTH_FAILED", "NOT_CONFIGURED", "DISABLED" -> outcome;
            default -> "OTHER";
        };
    }

    private String safeJobType(String jobType) {
        if (jobType == null) {
            return "OTHER";
        }
        return switch (jobType) {
            case "DIAGNOSIS", "INDEX", "ARCHIVE_CASE" -> jobType;
            default -> "OTHER";
        };
    }

    private String safeDependency(String dependency) {
        if (dependency == null) {
            return "other";
        }
        return switch (dependency) {
            case "prometheus", "cls-logs", "dashscope-chat", "dashscope-embedding",
                    "milvus", "mcp-tavily", "mcp-dbhub" -> dependency;
            default -> "other";
        };
    }

    private String safeModelOperation(String operation) {
        if (operation == null) {
            return "other";
        }
        return switch (operation) {
            case "chatDirectCall", "chatAgentCall", "memoryExtraction",
                    "aiOpsPlannerInvoke", "aiOpsExecutorInvoke", "aiOpsFinalReportInvoke",
                    "agentModelCall" -> operation;
            default -> "other";
        };
    }

    private String safeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "unknown";
        }
        String normalized = provider.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "dashscope", "openai", "azure-openai" -> normalized;
            default -> "other";
        };
    }

    private String safeModel(String model) {
        if (model == null || model.isBlank()) {
            return "unknown";
        }
        String normalized = model.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private String safeCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = currency.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("[A-Z]{3}") ? normalized : "UNKNOWN";
    }

    private String safeTool(String toolName) {
        if (toolName == null) {
            return "other";
        }
        return switch (toolName) {
            case "queryMetricTrend", "queryLogs", "queryInternalDocs", "queryPrometheusAlerts",
                    "queryDateTime" -> toolName;
            default -> "other";
        };
    }

    private String safeGrade(String grade) {
        if (grade == null) {
            return "UNKNOWN";
        }
        return switch (grade) {
            case "HIGH", "MEDIUM", "LOW" -> grade;
            default -> "UNKNOWN";
        };
    }
}
