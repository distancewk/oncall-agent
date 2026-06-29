package org.example.service;

import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AgentToolSurfaceServiceTest {

    private DateTimeTools dateTimeTools;
    private InternalDocsTools internalDocsTools;
    private QueryMetricsTools queryMetricsTools;
    private QueryLogsTools queryLogsTools;
    private AgentToolSurfaceService service;

    @BeforeEach
    void setUp() {
        dateTimeTools = mock(DateTimeTools.class);
        internalDocsTools = mock(InternalDocsTools.class);
        queryMetricsTools = mock(QueryMetricsTools.class);
        queryLogsTools = mock(QueryLogsTools.class);
        service = new AgentToolSurfaceService(
                dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools, null);
    }

    @Test
    void plannerMethodTools_shouldExcludeExecutionTools() {
        Object[] tools = service.aiOpsPlannerMethodTools();

        assertThat(List.of(tools)).contains(dateTimeTools, internalDocsTools);
        assertThat(List.of(tools)).doesNotContain(queryMetricsTools, queryLogsTools);
    }

    @Test
    void executorMethodTools_shouldIncludeDiagnosticTools() {
        Object[] tools = service.aiOpsExecutorMethodTools();

        assertThat(List.of(tools)).contains(dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools);
    }

    @Test
    void generalChatMethodTools_shouldExposeSmallSurfaceOnly() {
        Object[] tools = service.generalChatMethodTools();

        assertThat(List.of(tools)).contains(dateTimeTools, internalDocsTools);
        assertThat(List.of(tools)).doesNotContain(queryMetricsTools, queryLogsTools);
    }

    @Test
    void aiOpsPlannerMcpTools_shouldExposeNoExecutionMcpTools() {
        ToolCallback tavily = tool("tavily_search", "public search");
        ToolCallback dbhub = tool("dbhub_execute_sql", "readonly sql");

        ToolCallback[] callbacks = service.aiOpsPlannerMcpTools(new ToolCallback[]{tavily, dbhub});

        assertThat(callbacks).isEmpty();
    }

    @Test
    void aiOpsExecutorMcpTools_shouldIncludeTavilyAndDbhubAliases() {
        ToolCallback tavily = tool("search", "Tavily public search");
        ToolCallback dbhubExecute = tool("execute_sql", "read-only sql");
        ToolCallback dbhubTables = tool("list_tables", "database schema");
        ToolCallback writableSql = tool("execute_sql", "write sql against customer database");
        ToolCallback writableDbhubSql = tool("dbhub_execute_sql", "write sql against customer database");
        ToolCallback dbhubMutation = tool("dbhub_drop_table", "drop database table");
        ToolCallback unrelated = tool("filesystem_read", "read files");

        ToolCallback[] callbacks = service.aiOpsExecutorMcpTools(
                new ToolCallback[]{
                        tavily, dbhubExecute, dbhubTables, writableSql, writableDbhubSql, dbhubMutation, unrelated});

        assertThat(List.of(callbacks)).containsExactly(tavily, dbhubExecute, dbhubTables);
    }

    @Test
    void generalChatMcpTools_shouldExposeOnlyTavily() {
        ToolCallback tavily = tool("search", "Tavily public search");
        ToolCallback dbhub = tool("dbhub_execute_sql", "readonly sql");
        ToolCallback unrelated = tool("filesystem_read", "read files");

        ToolCallback[] callbacks = service.generalChatMcpTools(new ToolCallback[]{tavily, dbhub, unrelated});

        assertThat(List.of(callbacks)).containsExactly(tavily);
    }

    @Test
    void generalChatMcpTools_shouldNotApplyDiagnosisGuard() {
        McpToolCallbackGuard guard = mock(McpToolCallbackGuard.class);
        AgentToolSurfaceService service = new AgentToolSurfaceService(
                dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools, guard);
        ToolCallback tavily = tool("search", "Tavily public search");

        ToolCallback[] callbacks = service.generalChatMcpTools(new ToolCallback[]{tavily});

        assertThat(List.of(callbacks)).containsExactly(tavily);
        verify(guard, never()).wrapCallbacks(any());
    }

    private ToolCallback tool(String name, String description) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(description)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return call(toolInput);
            }
        };
    }
}
