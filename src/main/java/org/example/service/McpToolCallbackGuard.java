package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.exception.DependencyUnavailableException;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class McpToolCallbackGuard {

    private final DependencyGuard dependencyGuard;
    private final ObjectMapper objectMapper;

    public McpToolCallbackGuard(DependencyGuard dependencyGuard, ObjectMapper objectMapper) {
        this.dependencyGuard = dependencyGuard;
        this.objectMapper = objectMapper;
    }

    public ToolCallback[] wrapCallbacks(ToolCallback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) {
            return new ToolCallback[0];
        }
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = wrapCallback(callbacks[i]);
        }
        return wrapped;
    }

    private ToolCallback wrapCallback(ToolCallback callback) {
        if (callback == null) {
            return null;
        }
        String dependency = dependencyName(callback);
        if (dependency == null) {
            return callback;
        }
        return new GuardedMcpToolCallback(callback, dependency);
    }

    @Nullable
    private String dependencyName(ToolCallback callback) {
        ToolDefinition definition = toolDefinition(callback);
        String haystack = String.join(" ",
                        value(definition == null ? null : definition.name()),
                        value(definition == null ? null : definition.description()),
                        value(callback.getToolMetadata()),
                        callback.getClass().getName())
                .toLowerCase(Locale.ROOT);
        if (haystack.contains("tavily")) {
            return "mcp-tavily";
        }
        if (haystack.contains("dbhub")
                || haystack.contains("bytebase")
                || haystack.contains("execute_sql")
                || haystack.contains("execute sql")
                || haystack.contains("readonly sql")
                || haystack.contains("read-only sql")
                || haystack.contains("database schema")
                || haystack.contains("list_tables")
                || haystack.contains("describe_table")) {
            return "mcp-dbhub";
        }
        return null;
    }

    @Nullable
    private ToolDefinition toolDefinition(ToolCallback callback) {
        return callback.getToolDefinition();
    }

    private String guardedCall(ToolCallback delegate, String dependency, String operation, ToolCall call) {
        return dependencyGuard.execute(dependency, operation,
                () -> {
                    try {
                        return call.get();
                    } catch (DependencyUnavailableException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                error -> dependencyErrorJson(dependency, operation, error));
    }

    private String dependencyErrorJson(String dependency, String operation, Throwable error) {
        String errorCode = error instanceof DependencyUnavailableException unavailable
                ? unavailable.getErrorCode()
                : "DEPENDENCY_ERROR";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("errorCode", errorCode);
        payload.put("dependency", dependency);
        payload.put("tool", operation);
        payload.put("message", errorMessage(error));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"success\":false,\"errorCode\":\"" + errorCode + "\",\"dependency\":\""
                    + dependency + "\",\"tool\":\"" + operation + "\"}";
        }
    }

    private String errorMessage(Throwable error) {
        if (error == null) {
            return "MCP tool dependency failed";
        }
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @FunctionalInterface
    private interface ToolCall {
        String get() throws Exception;
    }

    private class GuardedMcpToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final String dependency;

        private GuardedMcpToolCallback(ToolCallback delegate, String dependency) {
            this.delegate = delegate;
            this.dependency = dependency;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return guardedCall(delegate, dependency, toolName(), () -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return guardedCall(delegate, dependency, toolName(), () -> delegate.call(toolInput, toolContext));
        }

        private String toolName() {
            ToolDefinition definition = toolDefinition(delegate);
            if (definition == null || definition.name() == null || definition.name().isBlank()) {
                return dependency;
            }
            return definition.name();
        }
    }
}
