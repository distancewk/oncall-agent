package org.example.config;

import org.example.service.TenantContext;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Captures request/run MDC across executor and CompletableFuture boundaries. */
public final class MdcContext {

    private static final Pattern TRACEPARENT_IN_JSON = Pattern.compile(
            "\\\"traceparent\\\"\\s*:\\s*\\\"(00-[0-9a-fA-F]{32}-[0-9a-fA-F]{16}-[0-9a-fA-F]{2})\\\"");

    private MdcContext() {
    }

    public static Runnable wrapRunnable(Runnable delegate) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        String capturedTenant = TenantContext.currentTenant();
        return () -> withTenant(capturedTenant, () -> withContext(captured, delegate));
    }

    public static <T> Callable<T> wrapCallable(Callable<T> delegate) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        String capturedTenant = TenantContext.currentTenant();
        return () -> {
            Holder<T> result = new Holder<>();
            withTenant(capturedTenant, () -> withContext(captured, () -> {
                try {
                    result.value = delegate.call();
                } catch (Exception e) {
                    result.failure = e;
                }
            }));
            if (result.failure != null) {
                if (result.failure instanceof Exception exception) {
                    throw exception;
                }
                if (result.failure instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("异步任务失败", result.failure);
            }
            return result.value;
        };
    }

    public static <T> Supplier<T> wrapSupplier(Supplier<T> delegate) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        String capturedTenant = TenantContext.currentTenant();
        return () -> {
            Holder<T> result = new Holder<>();
            withTenant(capturedTenant, () -> withContext(captured, () -> {
                try {
                    result.value = delegate.get();
                } catch (RuntimeException e) {
                    result.failure = e;
                }
            }));
            if (result.failure != null) {
                if (result.failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (result.failure instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("异步任务失败", result.failure);
            }
            return result.value;
        };
    }

    /**
     * Installs a persisted W3C parent for a durable job while preserving any
     * non-trace MDC fields captured by the worker thread.
     */
    public static void withTraceparent(String traceparent, Runnable delegate) {
        Map<String, String> context = new HashMap<>();
        Map<String, String> current = MDC.getCopyOfContextMap();
        if (current != null) {
            context.putAll(current);
        }
        TraceContext.Context parsed = TraceContext.from(traceparent);
        context.put(TraceContext.TRACEPARENT_HEADER, parsed.traceparent());
        context.put("trace_id", parsed.traceId());
        context.put("span_id", parsed.spanId());
        String capturedTenant = TenantContext.currentTenant();
        withTenant(capturedTenant, () -> withContext(context, delegate));
    }

    /** Extracts only a syntactically valid traceparent from a JSON job payload. */
    public static String extractTraceparent(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        Matcher matcher = TRACEPARENT_IN_JSON.matcher(payload);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static void withContext(Map<String, String> captured, Runnable delegate) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
            if (captured == null || captured.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(captured);
            }
            delegate.run();
        } finally {
            if (previous == null || previous.isEmpty()) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    private static void withTenant(String tenantId, Runnable delegate) {
        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            delegate.run();
        }
    }

    private static final class Holder<T> {
        private T value;
        private Throwable failure;
    }
}
