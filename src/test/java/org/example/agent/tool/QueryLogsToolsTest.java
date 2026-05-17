package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryLogsToolsTest {

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
}
