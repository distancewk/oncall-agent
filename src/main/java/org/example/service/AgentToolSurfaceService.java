package org.example.service;

import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;

@Service
public class AgentToolSurfaceService {

    private final DateTimeTools dateTimeTools;
    private final InternalDocsTools internalDocsTools;
    private final QueryMetricsTools queryMetricsTools;
    private final QueryLogsTools queryLogsTools;
    private final McpToolCallbackGuard mcpToolCallbackGuard;

    public AgentToolSurfaceService(DateTimeTools dateTimeTools,
                                   InternalDocsTools internalDocsTools,
                                   QueryMetricsTools queryMetricsTools,
                                   @Nullable QueryLogsTools queryLogsTools,
                                   @Nullable McpToolCallbackGuard mcpToolCallbackGuard) {
        this.dateTimeTools = dateTimeTools;
        this.internalDocsTools = internalDocsTools;
        this.queryMetricsTools = queryMetricsTools;
        this.queryLogsTools = queryLogsTools;
        this.mcpToolCallbackGuard = mcpToolCallbackGuard;
    }

    public Object[] generalChatMethodTools() {
        return new Object[]{dateTimeTools, internalDocsTools};
    }

    public Object[] aiOpsPlannerMethodTools() {
        // Planner only plans and replans from the incident context plus executor
        // feedback. Giving it executable diagnostic tools creates a second caller for
        // the same query and makes duplicate/budget policy checks depend on model
        // routing. Executor remains the single owner of diagnostic tool execution.
        return new Object[0];
    }

    public Object[] aiOpsExecutorMethodTools() {
        if (queryLogsTools == null) {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
        return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
    }

    public ToolCallback[] generalChatMcpTools(ToolCallback[] callbacks) {
        return filterCallbacks(callbacks, this::isTavilyTool);
    }

    public ToolCallback[] aiOpsPlannerMcpTools(ToolCallback[] callbacks) {
        return new ToolCallback[0];
    }

    public ToolCallback[] aiOpsExecutorMcpTools(ToolCallback[] callbacks) {
        return guard(filterCallbacks(callbacks, callback -> isTavilyTool(callback) || isDbHubTool(callback)));
    }

    private ToolCallback[] filterCallbacks(ToolCallback[] callbacks, ToolCallbackPredicate predicate) {
        if (callbacks == null || callbacks.length == 0) {
            return new ToolCallback[0];
        }
        return Arrays.stream(callbacks)
                .filter(callback -> callback != null && predicate.test(callback))
                .toArray(ToolCallback[]::new);
    }

    private ToolCallback[] guard(ToolCallback[] callbacks) {
        return mcpToolCallbackGuard == null ? callbacks : mcpToolCallbackGuard.wrapCallbacks(callbacks);
    }

    private boolean isTavilyTool(ToolCallback callback) {
        return toolHaystack(callback).contains("tavily");
    }

    private boolean isDbHubTool(ToolCallback callback) {
        String haystack = toolHaystack(callback);
        boolean readonly = haystack.contains("readonly") || haystack.contains("read-only");
        boolean sql = haystack.contains("execute_sql") || haystack.contains("execute sql");
        boolean schemaRead = haystack.contains("database schema")
                || haystack.contains("list_tables")
                || haystack.contains("describe_table");
        if (sql) {
            return readonly;
        }
        return schemaRead;
    }

    private String toolHaystack(ToolCallback callback) {
        ToolDefinition definition = toolDefinition(callback);
        return String.join(" ",
                        value(definition == null ? null : definition.name()),
                        value(definition == null ? null : definition.description()),
                        value(callback.getToolMetadata()),
                        callback.getClass().getName())
                .toLowerCase(Locale.ROOT);
    }

    @Nullable
    private ToolDefinition toolDefinition(ToolCallback callback) {
        return callback.getToolDefinition();
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @FunctionalInterface
    private interface ToolCallbackPredicate {
        boolean test(ToolCallback callback);
    }
}
