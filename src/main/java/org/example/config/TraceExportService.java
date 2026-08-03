package org.example.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.MediaType;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exports sampled HTTP spans as Zipkin v2 JSON without exporting request bodies.
 * The exporter is disabled by default and has a bounded in-flight queue.
 */
@Component
public class TraceExportService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TraceExportService.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    static final String START_NANOS_ATTRIBUTE = "APP_TRACE_START_NANOS";
    static final String TRACE_ID_ATTRIBUTE = "APP_TRACE_ID";
    static final String SPAN_ID_ATTRIBUTE = "APP_SPAN_ID";

    private final OkHttpClient exportClient;
    private final ObjectMapper objectMapper;
    private final AppTraceExportProperties properties;
    private final AtomicInteger pending = new AtomicInteger();

    public TraceExportService(OkHttpClient httpClient,
                              ObjectMapper objectMapper,
                              AppTraceExportProperties properties) {
        this.exportClient = httpClient.newBuilder()
                .callTimeout(Math.max(1L, properties.getTimeoutMillis()), TimeUnit.MILLISECONDS)
                .build();
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void export(HttpServletRequest request, HttpServletResponse response, Exception error) {
        submitSpan(buildSpan(request, response, error));
    }

    /** Exports an internal model/tool span without prompt, arguments, or results. */
    void exportInternalSpan(String name, Map<String, String> tags,
                            long startNanos, Throwable error) {
        long durationMicros = Math.max(1L,
                TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startNanos));
        TraceContext.Context traceContext = currentTraceContext();
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("traceId", traceContext.traceId());
        span.put("id", traceContext.spanId());
        span.put("name", safeSpanName(name));
        span.put("timestamp", Math.max(0L, System.currentTimeMillis() * 1000L - durationMicros));
        span.put("duration", durationMicros);
        span.put("localEndpoint", Map.of("serviceName", safeServiceName()));
        span.put("tags", safeTags(tags, error));
        submitSpan(span);
    }

    private void submitSpan(Map<String, Object> span) {
        if (!tryAcquire()) {
            return;
        }
        try {
            byte[] payload = objectMapper.writeValueAsBytes(List.of(span));
            Request.Builder builder = new Request.Builder()
                    .url(properties.getEndpoint().trim())
                    .post(RequestBody.create(payload, JSON))
                    .header("Content-Type", "application/json");
            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + properties.getApiKey().trim());
            }
            exportClient.newCall(builder.build()).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException exception) {
                    pending.decrementAndGet();
                    LOGGER.debug("trace span export failed");
                }

                @Override
                public void onResponse(Call call, Response exportedResponse) {
                    try (Response ignored = exportedResponse) {
                        if (!exportedResponse.isSuccessful()) {
                            LOGGER.debug("trace span export returned HTTP {}", exportedResponse.code());
                        }
                    } finally {
                        pending.decrementAndGet();
                    }
                }
            });
        } catch (JsonProcessingException | RuntimeException exception) {
            pending.decrementAndGet();
            LOGGER.debug("trace span export could not be created");
        }
    }

    private boolean tryAcquire() {
        if (!isExportable() || !sampled()) {
            return false;
        }
        int current = pending.incrementAndGet();
        if (current > properties.getMaxPending()) {
            pending.decrementAndGet();
            return false;
        }
        return true;
    }

    Map<String, Object> buildSpan(HttpServletRequest request,
                                  HttpServletResponse response,
                                  Exception error) {
        long endMicros = System.currentTimeMillis() * 1000L;
        Object startValue = request.getAttribute(START_NANOS_ATTRIBUTE);
        long durationMicros = startValue instanceof Long startNanos
                ? Math.max(1L, TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - startNanos))
                : 1L;
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("traceId", request.getAttribute(TRACE_ID_ATTRIBUTE));
        span.put("id", request.getAttribute(SPAN_ID_ATTRIBUTE));
        span.put("name", "http.request");
        span.put("timestamp", Math.max(0L, endMicros - durationMicros));
        span.put("duration", durationMicros);
        span.put("localEndpoint", Map.of("serviceName", safeServiceName()));
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("http.method", safeMethod(request.getMethod()));
        tags.put("http.status_code", Integer.toString(response.getStatus()));
        if (error != null) {
            tags.put("error.type", error.getClass().getSimpleName());
        }
        span.put("tags", tags);
        return span;
    }

    private boolean isExportable() {
        return properties.isEnabled() && properties.getEndpoint() != null
                && !properties.getEndpoint().isBlank()
                && properties.getMaxPending() > 0
                && properties.getTimeoutMillis() > 0
                && properties.getSamplingProbability() > 0.0d
                && properties.getSamplingProbability() <= 1.0d;
    }

    private boolean sampled() {
        return properties.getSamplingProbability() >= 1.0d
                || ThreadLocalRandom.current().nextDouble() < properties.getSamplingProbability();
    }

    private String safeServiceName() {
        String serviceName = properties.getServiceName();
        return safeSpanName(serviceName == null || serviceName.isBlank()
                ? "superbizagent" : serviceName);
    }

    private String safeSpanName(String value) {
        String normalized = value == null || value.isBlank() ? "internal" : value;
        normalized = normalized.replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private Map<String, String> safeTags(Map<String, String> tags, Throwable error) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (tags != null) {
            tags.entrySet().stream().limit(16).forEach(entry ->
                    safe.put(safeSpanName(entry.getKey()), safeSpanName(entry.getValue())));
        }
        if (error != null) {
            safe.put("error.type", safeSpanName(error.getClass().getSimpleName()));
        }
        return safe;
    }

    private TraceContext.Context currentTraceContext() {
        String traceId = MDC.get("trace_id");
        if (traceId != null && traceId.matches("[0-9a-fA-F]{32}")
                && !traceId.matches("0{32}")) {
            return TraceContext.from("00-" + traceId + "-0123456789abcdef-01");
        }
        return TraceContext.from(null);
    }

    private String safeMethod(String method) {
        if (method == null) {
            return "OTHER";
        }
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS" -> method.toUpperCase(Locale.ROOT);
            default -> "OTHER";
        };
    }
}
