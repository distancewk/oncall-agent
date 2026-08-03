package org.example.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.example.config.AppObservabilityProperties;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObservabilityMetricsTest {

    @Test
    void metrics_shouldRecordLowCardinalityDiagnosisAndJobOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservabilityMetrics metrics = new ObservabilityMetrics(registry);

        metrics.recordDiagnosisStarted();
        metrics.recordDiagnosisOutcome("COMPLETED", 5_000_000L);
        metrics.recordJobStarted("DIAGNOSIS");
        metrics.recordJobOutcome("DIAGNOSIS", "COMPLETED");
        metrics.recordJobOutcome("untrusted-user-value", "untrusted-outcome");
        metrics.recordToolCall("queryLogs", "SUCCESS");
        metrics.recordToolCall("untrusted-tool", "untrusted-outcome");
        metrics.recordDiagnosisQuality("LOW", true);
        metrics.recordJobQueueDepth("DIAGNOSIS", 3L);
        metrics.recordJobQueueDepth("DIAGNOSIS", 1L);
        metrics.recordJobClaimDelay("DIAGNOSIS", 25L);
        metrics.recordJobOutcome("DIAGNOSIS", "COMPLETED", 10_000L);

        assertEquals(1.0, registry.get("superbizagent.diagnosis.started").counter().count());
        assertEquals(1.0, registry.get("superbizagent.diagnosis.outcomes")
                .tag("outcome", "COMPLETED").counter().count());
        assertEquals(1.0, registry.get("superbizagent.jobs.started")
                .tag("job_type", "DIAGNOSIS").counter().count());
        assertEquals(1.0, registry.get("superbizagent.jobs.outcomes")
                .tag("job_type", "OTHER")
                .tag("outcome", "OTHER")
                .counter().count());
        assertEquals(1.0, registry.get("superbizagent.diagnosis.tool_calls")
                .tag("tool", "queryLogs")
                .tag("outcome", "SUCCESS")
                .counter().count());
        assertEquals(1.0, registry.get("superbizagent.diagnosis.tool_calls")
                .tag("tool", "other")
                .tag("outcome", "OTHER")
                .counter().count());
        assertEquals(1.0, registry.get("superbizagent.diagnosis.quality")
                .tag("grade", "LOW")
                .tag("evidence_gap", "true")
                .counter().count());
        assertEquals(1.0, registry.get("superbizagent.jobs.queue.depth")
                .tag("job_type", "DIAGNOSIS").gauge().value());
        assertEquals(1, registry.get("superbizagent.jobs.claim.delay")
                .tag("job_type", "DIAGNOSIS").timer().count());
        assertEquals(1, registry.get("superbizagent.jobs.duration")
                .timer().count());
        assertEquals(1, registry.get("superbizagent.diagnosis.duration").timer().count());
    }

    @Test
    void metrics_shouldRecordModelUsageAndEstimatedCostWithoutExposingPromptContent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AppObservabilityProperties properties = new AppObservabilityProperties();
        properties.setInputCostPer1kTokens(2.0d);
        properties.setOutputCostPer1kTokens(4.0d);
        ObservabilityMetrics metrics = new ObservabilityMetrics(registry, properties);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok"))),
                ChatResponseMetadata.builder()
                        .model("qwen3-max")
                        .usage(new DefaultUsage(1_000, 500, 1_500))
                        .build());

        metrics.recordModelCall("chatDirectCall", null, "SUCCESS", 2_000_000L, response);
        metrics.recordModelInvocation("aiOpsPlannerInvoke", "qwen3-max", "SUCCESS", 3_000_000L);

        assertEquals(1.0, registry.get("superbizagent.model.calls")
                .tag("operation", "chatDirectCall")
                .tag("model", "qwen3-max")
                .tag("outcome", "SUCCESS")
                .counter().count());
        assertEquals(1_000.0, registry.get("superbizagent.model.tokens")
                .tag("operation", "chatDirectCall")
                .tag("model", "qwen3-max")
                .tag("direction", "prompt")
                .counter().count());
        assertEquals(500.0, registry.get("superbizagent.model.tokens")
                .tag("operation", "chatDirectCall")
                .tag("model", "qwen3-max")
                .tag("direction", "completion")
                .counter().count());
        assertEquals(4.0, registry.get("superbizagent.model.estimated_cost")
                .tag("operation", "chatDirectCall")
                .tag("model", "qwen3-max")
                .tag("currency", "CNY")
                .counter().count(), 0.0001);
        assertEquals(1.0, registry.get("superbizagent.model.usage.unknown")
                .tag("operation", "aiOpsPlannerInvoke")
                .tag("model", "qwen3-max")
                .counter().count());
    }
}
