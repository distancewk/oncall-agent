package org.example.service;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.config.AppResilienceProperties;
import org.example.exception.DependencyUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private ChatService chatService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        chatService = new ChatService();
        ReflectionTestUtils.setField(chatService, "resourceLoader", new DefaultResourceLoader());
    }

    @Test
    void buildSystemPrompt_shouldIncludePrivateMemoriesWhenProvided() {
        VectorSearchService.SearchResult memory = new VectorSearchService.SearchResult();
        memory.setContent("[用户私人记忆] 用户偏好使用中文回答");

        String prompt = chatService.buildSystemPrompt(
                List.of(Map.of("role", "user", "content", "之前的问题")),
                List.of(memory)
        );

        assertTrue(prompt.contains("--- 私人记忆 ---"));
        assertTrue(prompt.contains("用户偏好使用中文回答"));
        assertTrue(prompt.contains("--- 对话历史 ---"));
    }

    @Test
    void buildSystemPrompt_shouldDescribeGeneralChatToolBoundary() {
        String prompt = chatService.buildSystemPrompt(List.of());

        assertTrue(prompt.contains("天气类公开信息也通过 Tavily 联网检索"));
        assertTrue(prompt.contains("普通聊天不直接查询业务数据库"));
        assertFalse(prompt.contains("可以获取当前时间、查询天气信息"));
        assertFalse(prompt.contains("当用户需要查询业务系统数据库结构或数据时"));
    }

    @Test
    void buildMethodToolsArray_shouldExposeOnlyGeneralChatTools() {
        DateTimeTools dateTimeTools = mock(DateTimeTools.class);
        InternalDocsTools internalDocsTools = mock(InternalDocsTools.class);
        QueryMetricsTools queryMetricsTools = mock(QueryMetricsTools.class);
        QueryLogsTools queryLogsTools = mock(QueryLogsTools.class);
        ReflectionTestUtils.setField(chatService, "dateTimeTools", dateTimeTools);
        ReflectionTestUtils.setField(chatService, "internalDocsTools", internalDocsTools);

        Object[] methodTools = chatService.buildMethodToolsArray();

        assertEquals(2, methodTools.length);
        assertSame(dateTimeTools, methodTools[0]);
        assertSame(internalDocsTools, methodTools[1]);
        assertFalse(Arrays.asList(methodTools).contains(queryMetricsTools));
        assertFalse(Arrays.asList(methodTools).contains(queryLogsTools));
    }

    @Test
    void getToolCallbacks_shouldExposeOnlyTavilyMcpToolsForGeneralChat() {
        ToolCallback tavily = failingTool("tavily_search", new AtomicInteger());
        ToolCallback dbhub = failingTool("dbhub_execute_sql", new AtomicInteger());
        ReflectionTestUtils.setField(chatService, "tools",
                (ToolCallbackProvider) () -> new ToolCallback[]{tavily, dbhub});

        ToolCallback[] callbacks = chatService.getToolCallbacks();

        assertEquals(1, callbacks.length);
        assertEquals("tavily_search", callbacks[0].getToolDefinition().name());
    }

    @Test
    void executeChat_shouldUseDashscopeChatGuardAndThrowCircuitOpenWithoutCallingAgentAgain() throws Exception {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(1);
        properties.getDefaultConfig().setFailureRateThreshold(50.0f);
        properties.getDefaultConfig().setOpenDuration(Duration.ofSeconds(30));
        ReflectionTestUtils.setField(chatService, "dependencyGuard", new DependencyGuard(properties));

        ReactAgent agent = mock(ReactAgent.class);
        when(agent.call("hello")).thenThrow(new IllegalStateException("dashscope unavailable"));

        assertThrows(RuntimeException.class, () -> chatService.executeChat(agent, "hello"));

        DependencyUnavailableException open = assertThrows(DependencyUnavailableException.class,
                () -> chatService.executeChat(agent, "hello"));

        assertEquals("dashscope-chat", open.getDependency());
        assertEquals("chatAgentCall", open.getOperation());
        assertEquals("CIRCUIT_OPEN", open.getErrorCode());
        verify(agent, times(1)).call("hello");
    }

    @Test
    void executeChat_shouldExposeFirstDashscopeFailureAsDependencyError() throws Exception {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(2);
        ReflectionTestUtils.setField(chatService, "dependencyGuard", new DependencyGuard(properties));

        ReactAgent agent = mock(ReactAgent.class);
        when(agent.call("hello")).thenThrow(new IllegalStateException("dashscope unavailable"));

        DependencyUnavailableException thrown = assertThrows(DependencyUnavailableException.class,
                () -> chatService.executeChat(agent, "hello"));

        assertEquals("dashscope-chat", thrown.getDependency());
        assertEquals("chatAgentCall", thrown.getOperation());
        assertEquals("DEPENDENCY_ERROR", thrown.getErrorCode());
    }

    @Test
    void getToolCallbacks_shouldReturnDependencyErrorWhenMcpCallbackFails() throws Exception {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(2);
        DependencyGuard dependencyGuard = new DependencyGuard(properties);
        ReflectionTestUtils.setField(chatService, "mcpToolCallbackGuard",
                new McpToolCallbackGuard(dependencyGuard, objectMapper));
        ReflectionTestUtils.setField(chatService, "tools",
                (ToolCallbackProvider) () -> new ToolCallback[]{failingTool("tavily_search", new AtomicInteger())});

        String result = chatService.getToolCallbacks()[0].call("{\"query\":\"spring\"}");

        JsonNode json = objectMapper.readTree(result);
        assertEquals(false, json.path("success").asBoolean());
        assertEquals("DEPENDENCY_ERROR", json.path("errorCode").asText());
        assertEquals("mcp-tavily", json.path("dependency").asText());
    }

    @Test
    void getToolCallbacks_shouldReturnCircuitOpenWhenAllowedMcpCircuitIsOpen() throws Exception {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(1);
        properties.getDefaultConfig().setFailureRateThreshold(50.0f);
        properties.getDefaultConfig().setOpenDuration(Duration.ofSeconds(30));
        DependencyGuard dependencyGuard = new DependencyGuard(properties);
        ReflectionTestUtils.setField(chatService, "mcpToolCallbackGuard",
                new McpToolCallbackGuard(dependencyGuard, objectMapper));

        AtomicInteger calls = new AtomicInteger();
        ReflectionTestUtils.setField(chatService, "tools",
                (ToolCallbackProvider) () -> new ToolCallback[]{failingTool("tavily_search", calls)});
        ToolCallback guarded = chatService.getToolCallbacks()[0];

        JsonNode first = objectMapper.readTree(guarded.call("{\"query\":\"spring\"}"));
        JsonNode second = objectMapper.readTree(guarded.call("{\"query\":\"spring\"}"));

        assertEquals("DEPENDENCY_ERROR", first.path("errorCode").asText());
        assertEquals("CIRCUIT_OPEN", second.path("errorCode").asText());
        assertEquals("mcp-tavily", second.path("dependency").asText());
        assertEquals(1, calls.get());
    }

    private ToolCallback failingTool(String name, AtomicInteger calls) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                calls.incrementAndGet();
                throw new IllegalStateException("mcp unavailable");
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }
}
