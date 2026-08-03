package org.example.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.example.service.ObservabilityMetrics;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentObservationHandlerTest {

    @Test
    void modelObservation_shouldRecordProviderUsageWithoutPromptContent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentObservationHandler handler = handler(registry);
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("answer"))),
                ChatResponseMetadata.builder()
                        .model("qwen3-max")
                        .usage(new DefaultUsage(1_000, 250, 1_250))
                        .build());
        ChatModelObservationContext context = ChatModelObservationContext.builder()
                .prompt(new Prompt(new UserMessage("test")))
                .provider("dashscope")
                .build();
        context.setResponse(response);

        MDC.put(AgentObservationHandler.AI_OPERATION_MDC_KEY, "aiOpsPlannerInvoke");
        try {
            handler.onStart(context);
            handler.onStop(context);
        } finally {
            MDC.remove(AgentObservationHandler.AI_OPERATION_MDC_KEY);
        }

        assertEquals(1.0, registry.get("superbizagent.agent.model.calls")
                .tag("operation", "aiOpsPlannerInvoke")
                .tag("provider", "dashscope")
                .tag("model", "qwen3-max")
                .tag("outcome", "SUCCESS")
                .counter().count());
        assertEquals(1_000.0, registry.get("superbizagent.agent.model.tokens")
                .tag("operation", "aiOpsPlannerInvoke")
                .tag("provider", "dashscope")
                .tag("model", "qwen3-max")
                .tag("direction", "prompt")
                .counter().count());
        assertEquals(250.0, registry.get("superbizagent.agent.model.tokens")
                .tag("operation", "aiOpsPlannerInvoke")
                .tag("provider", "dashscope")
                .tag("model", "qwen3-max")
                .tag("direction", "completion")
                .counter().count());
    }

    @Test
    void toolObservation_shouldRecordOnlyToolNameAndOutcome() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentObservationHandler handler = handler(registry);
        ToolDefinition definition = ToolDefinition.builder()
                .name("queryLogs")
                .description("query logs")
                .inputSchema("{\"type\":\"object\"}")
                .build();
        ToolCallingObservationContext context = ToolCallingObservationContext.builder()
                .toolDefinition(definition)
                .toolCallArguments("{\"secret\":\"must-not-be-recorded\"}")
                .toolCallResult("private result")
                .build();

        handler.onStart(context);
        handler.onStop(context);

        assertEquals(1.0, registry.get("superbizagent.agent.tool.calls")
                .tag("tool", "queryLogs")
                .tag("outcome", "SUCCESS")
                .counter().count());
        assertEquals(1, registry.get("superbizagent.agent.tool.duration")
                .tag("tool", "queryLogs").timer().count());
        assertEquals(0, registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().contains("secret"))
                .count());
    }

    @Test
    void observationCustomizer_shouldRegisterHandlerOnSpringRegistry() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ObservationRegistry observations = ObservationRegistry.create();
        AgentObservationHandler handler = handler(meters);
        new AgentObservationConfig().agentObservationCustomizer(handler).customize(observations);
        ChatModelObservationContext context = ChatModelObservationContext.builder()
                .prompt(new Prompt(new UserMessage("test")))
                .provider("dashscope")
                .build();
        context.setResponse(new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok"))),
                ChatResponseMetadata.builder().model("qwen3-max").build()));

        Observation observation = Observation.createNotStarted(
                "gen_ai.chat", () -> context, observations).start();
        observation.stop();

        assertEquals(1.0, meters.get("superbizagent.agent.model.calls")
                .tag("operation", "agentModelCall")
                .tag("provider", "dashscope")
                .tag("model", "qwen3-max")
                .tag("outcome", "SUCCESS")
                .counter().count());
    }

    private AgentObservationHandler handler(SimpleMeterRegistry registry) {
        return new AgentObservationHandler(
                new ObservabilityMetrics(registry),
                new TraceExportService(new okhttp3.OkHttpClient(),
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        new AppTraceExportProperties()));
    }
}
