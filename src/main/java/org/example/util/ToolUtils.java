package org.example.util;

import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;

/**
 * Shared utility for building agent tool arrays.
 * Extracted to eliminate duplication between ChatService and AiOpsService.
 */
public class ToolUtils {

    /**
     * Build method tools array, conditionally including QueryLogsTools.
     * When queryLogsTools is null (real mode), log querying is handled by MCP;
     * when present (mock mode), the local QueryLogsTools bean is included.
     */
    public static Object[] buildAiOpsMethodToolsArray(
            DateTimeTools dateTimeTools,
            InternalDocsTools internalDocsTools,
            QueryMetricsTools queryMetricsTools,
            QueryLogsTools queryLogsTools) {
        if (queryLogsTools != null) {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * Build the deliberately small tool surface for ordinary chat.
     * Diagnostic tools stay in the AI Ops agent chain.
     */
    public static Object[] buildGeneralChatMethodToolsArray(
            DateTimeTools dateTimeTools,
            InternalDocsTools internalDocsTools) {
        return new Object[]{dateTimeTools, internalDocsTools};
    }
}
