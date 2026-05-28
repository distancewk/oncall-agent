package org.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppResilienceProperties;
import org.example.service.DependencyGuard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryLogsToolsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void queryLogsToolDescription_shouldNotRequireTopicDiscoveryBeforeEveryQuery() throws Exception {
        Method method = QueryLogsTools.class.getMethod(
                "queryLogs", String.class, String.class, String.class, Integer.class);
        Tool annotation = method.getAnnotation(Tool.class);

        String description = annotation.description();
        assertFalse(description.contains("Before calling this tool"));
        assertFalse(description.contains("should call getAvailableLogTopics"));
        assertTrue(description.contains("Call getAvailableLogTopics only when"));
    }

    @Test
    void queryLogs_shouldReturnCircuitOpenAfterGuardedClsFailure() throws Exception {
        QueryLogsTools tools = new QueryLogsTools();
        ReflectionTestUtils.setField(tools, "mockEnabled", false);
        ReflectionTestUtils.setField(tools, "dependencyGuard", singleFailureGuard());

        JsonNode first = objectMapper.readTree(tools.queryLogs(
                "ap-guangzhou", "application-logs", "level:ERROR", 20));
        assertFalse(first.path("success").asBoolean());
        assertEquals("DEPENDENCY_ERROR", first.path("errorCode").asText());

        JsonNode second = objectMapper.readTree(tools.queryLogs(
                "ap-guangzhou", "application-logs", "level:ERROR", 20));
        assertFalse(second.path("success").asBoolean());
        assertEquals("CIRCUIT_OPEN", second.path("errorCode").asText());
    }

    private DependencyGuard singleFailureGuard() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(1);
        properties.getDefaultConfig().setFailureRateThreshold(1.0f);
        properties.getDefaultConfig().setOpenDuration(Duration.ofSeconds(30));
        return new DependencyGuard(properties);
    }
}
