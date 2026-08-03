package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.CheckHealthResponse;
import io.milvus.grpc.GetVersionResponse;
import io.milvus.param.R;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.example.config.AppDependencyProbeProperties;
import org.example.dto.DependencyProbeSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Explicit connectivity probes for dependencies that otherwise only expose
 * passive circuit-breaker state. Probes are bounded and never run on startup
 * or as part of liveness, so an operator can distinguish reachability from
 * application readiness without creating an outage feedback loop.
 */
@Service
public class DependencyProbeService {

    private static final Pattern PROMETHEUS_SUCCESS = Pattern.compile(
            "\\\"status\\\"\\s*:\\s*\\\"success\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMETHEUS_VERSION = Pattern.compile(
            "\\\"version\\\"\\s*:", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OkHttpClient httpClient;
    private final ClsClient clsClient;
    private final AppDependencyProbeProperties properties;
    private final ObservabilityMetrics observabilityMetrics;

    @Autowired(required = false)
    @Lazy
    private MilvusServiceClient milvusClient;

    @Autowired(required = false)
    @Lazy
    private DependencyGuard dependencyGuard;

    @Autowired
    public DependencyProbeService(OkHttpClient httpClient,
                                  AppDependencyProbeProperties properties,
                                  ObservabilityMetrics observabilityMetrics,
                                  ClsClient clsClient) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.observabilityMetrics = observabilityMetrics;
        this.clsClient = clsClient;
    }

    public DependencyProbeService(OkHttpClient httpClient,
                                  AppDependencyProbeProperties properties,
                                  ObservabilityMetrics observabilityMetrics) {
        this(httpClient, properties, observabilityMetrics, new ClsClient(httpClient));
    }

    public List<DependencyProbeSnapshot> probeAll() {
        if (!properties.isEnabled()) {
            return List.of(
                    state("prometheus", "DISABLED", "probe disabled"),
                    state("cls-logs", "DISABLED", "probe disabled"),
                    state("dashscope-chat", "DISABLED", "probe disabled"),
                    state("milvus", "DISABLED", "probe disabled"),
                    state("mcp", "DISABLED", "probe disabled"));
        }
        return List.of(
                probeHttp("prometheus", properties.getPrometheusUrl(),
                        "/api/v1/status/buildinfo", properties.getPrometheusApiKey(),
                        "Authorization", PayloadSemantic.PROMETHEUS),
                probeCls(),
                probeHttp("dashscope-chat", properties.getDashscopeUrl(),
                        properties.getDashscopeProbePath(), properties.getDashscopeApiKey(),
                        "Authorization", PayloadSemantic.DASHSCOPE_DEPLOYABLE_MODELS),
                probeMilvus(),
                probeMcp());
    }

    private DependencyProbeSnapshot probeCls() {
        if (properties.isClsNativeSigningEnabled()) {
            return probeNativeCls();
        }
        return probeHttp("cls-logs", properties.getClsUrl(), properties.getClsProbePath(),
                properties.getClsApiKey(), "X-API-Key", PayloadSemantic.CLS, false);
    }

    private DependencyProbeSnapshot probeNativeCls() {
        if (properties.getClsUrl() == null || properties.getClsUrl().isBlank()) {
            return state("cls-logs", "NOT_CONFIGURED", "endpoint not configured");
        }
        if (properties.getClsProbeTopicId() == null || properties.getClsProbeTopicId().isBlank()) {
            return state("cls-logs", "NOT_CONFIGURED", "read-only probe topic not configured");
        }
        long started = System.nanoTime();
        String path = properties.getClsProbePath();
        if (path == null || path.isBlank() || "/api/v1/logs/query".equals(path)) {
            path = "/";
        }
        String probePath = path;
        return guardedProbe("cls-logs", "nativeProbe", started,
                () -> probeNativeClsDirect(probePath, started));
    }

    private DependencyProbeSnapshot probeNativeClsDirect(String path, long started) {
        try {
            long now = Instant.now().toEpochMilli();
            ClsClient.SearchRequest search = new ClsClient.SearchRequest(
                    properties.getClsUrl(), path, properties.getClsProbeTopicId(),
                    Math.max(0L, now - 300_000L), now, "*", 1,
                    properties.getClsSecretId(), properties.getClsSecretKey(),
                    properties.getClsRegion(), properties.getClsService(), properties.getTimeoutMillis());
            try (Response response = clsClient.execute(search)) {
                int code = response.code();
                String state = httpStateForCode(code);
                if (code < 400) {
                    String responseBody;
                    try (ResponseBody responseBodyValue = response.body()) {
                        responseBody = responseBodyValue == null ? "" : responseBodyValue.string();
                    }
                    if (!clsPayloadIsHealthy(responseBody)) {
                        return snapshot("cls-logs", "DEGRADED", elapsedMillis(started), code,
                                "reachable but CLS response semantic check failed");
                    }
                }
                return snapshot("cls-logs", state, elapsedMillis(started), code,
                        code < 400 ? "reachable" : "reachable but returned HTTP " + code);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new ProbeFailure(e);
        }
    }

    private DependencyProbeSnapshot probeHttp(String name, String baseUrl, String path,
                                              String apiKey, String apiKeyHeader,
                                              PayloadSemantic payloadSemantic) {
        return probeHttp(name, baseUrl, path, apiKey, apiKeyHeader, payloadSemantic, false);
    }

    private DependencyProbeSnapshot probeHttp(String name, String baseUrl, String path,
                                              String apiKey, String apiKeyHeader,
                                              PayloadSemantic payloadSemantic,
                                              boolean nativeClsSigning) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return state(name, "NOT_CONFIGURED", "endpoint not configured");
        }
        if (path == null || path.isBlank()) {
            return state(name, "NOT_CONFIGURED", "probe path not configured");
        }
        long started = System.nanoTime();
        return guardedProbe(name, "httpProbe", started,
                () -> probeHttpDirect(name, baseUrl, path, apiKey, apiKeyHeader,
                        payloadSemantic, nativeClsSigning, started));
    }

    private DependencyProbeSnapshot probeHttpDirect(String name, String baseUrl, String path,
                                                    String apiKey, String apiKeyHeader,
                                                    PayloadSemantic payloadSemantic,
                                                    boolean nativeClsSigning,
                                                    long started) {
        try {
            Request.Builder requestBuilder = new Request.Builder().url(joinUrl(baseUrl, path)).get();
            if (nativeClsSigning) {
                ClsRequestSigner.sign(requestBuilder, URI.create(joinUrl(baseUrl, path)), "GET", "",
                        properties.getClsSecretId(), properties.getClsSecretKey(),
                        properties.getClsRegion(), properties.getClsService(), Instant.now());
            }
            if (apiKey != null && !apiKey.isBlank()) {
                String value = "Authorization".equalsIgnoreCase(apiKeyHeader)
                        ? "Bearer " + apiKey.trim() : apiKey.trim();
                requestBuilder.header(apiKeyHeader, value);
            }
            Request request = requestBuilder.build();
            OkHttpClient client = httpClient.newBuilder()
                    .callTimeout(properties.getTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .build();
            try (Response response = client.newCall(request).execute()) {
                int code = response.code();
                String state = httpStateForCode(code);
                if (code < 400 && payloadSemantic != PayloadSemantic.NONE) {
                    String body;
                    try (ResponseBody responseBody = response.body()) {
                        body = responseBody == null ? "" : responseBody.string();
                    }
                    if (!payloadIsHealthy(body, payloadSemantic)) {
                        state = "DEGRADED";
                        return snapshot(name, state, elapsedMillis(started), code, semanticFailureMessage(payloadSemantic));
                    }
                }
                return snapshot(name, state, elapsedMillis(started), code,
                        code < 400 ? "reachable" : "reachable but returned HTTP " + code);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new ProbeFailure(e);
        }
    }

    private DependencyProbeSnapshot guardedProbe(String dependency, String operation, long started,
                                                 Supplier<DependencyProbeSnapshot> probe) {
        if (dependencyGuard == null) {
            try {
                return probe.get();
            } catch (ProbeFailure failure) {
                return snapshot(dependency, "DOWN", elapsedMillis(started), null,
                        safeMessage(failure.getCause() == null ? failure : failure.getCause()));
            }
        }
        return dependencyGuard.execute(dependency, operation, probe,
                error -> snapshot(dependency, "DOWN", elapsedMillis(started), null, safeMessage(error)));
    }

    static boolean prometheusPayloadIsHealthy(String body) {
        return body != null && PROMETHEUS_SUCCESS.matcher(body).find()
                && PROMETHEUS_VERSION.matcher(body).find();
    }

    static boolean dashscopeDeployableModelsPayloadIsHealthy(String body) {
        if (body == null) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(body);
            return root != null && root.path("output").path("models").isArray();
        } catch (IOException e) {
            return false;
        }
    }

    private boolean payloadIsHealthy(String body, PayloadSemantic payloadSemantic) {
        return switch (payloadSemantic) {
            case PROMETHEUS -> prometheusPayloadIsHealthy(body);
            case DASHSCOPE_DEPLOYABLE_MODELS -> dashscopeDeployableModelsPayloadIsHealthy(body);
            case CLS -> clsPayloadIsHealthy(body);
            case NONE -> true;
        };
    }

    static boolean clsPayloadIsHealthy(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode response = root != null && root.path("Response").isObject()
                    ? root.path("Response") : root;
            if (response == null || !response.isObject()) {
                return false;
            }
            // CLS legitimately returns null for an empty result set. The
            // documented result field proves the request reached CLS and
            // passed the read/query contract; empty data is not a format error.
            return response.has("RequestId")
                    && (response.has("Results")
                    || response.has("AnalysisResults")
                    || response.has("AnalysisRecords")
                    || response.has("logs"));
        } catch (IOException e) {
            return false;
        }
    }

    static String httpStateForCode(int code) {
        if (code == 401 || code == 403) {
            return "AUTH_FAILED";
        }
        return code < 400 ? "UP" : code < 500 ? "DEGRADED" : "DOWN";
    }

    private String semanticFailureMessage(PayloadSemantic payloadSemantic) {
        return switch (payloadSemantic) {
            case PROMETHEUS -> "reachable but Prometheus semantic check failed";
            case DASHSCOPE_DEPLOYABLE_MODELS ->
                    "reachable but DashScope deployable-models semantic check failed";
            case CLS -> "reachable but CLS response semantic check failed";
            case NONE -> "reachable";
        };
    }

    private DependencyProbeSnapshot probeTcp(String name, String host, int port) {
        if (host == null || host.isBlank() || port <= 0 || port > 65_535) {
            return state(name, "NOT_CONFIGURED", "host or port not configured");
        }
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), properties.getTimeoutMillis());
            return snapshot(name, "UP", elapsedMillis(started), null, "TCP reachable");
        } catch (IOException | IllegalArgumentException e) {
            return snapshot(name, "DOWN", elapsedMillis(started), null, safeMessage(e));
        }
    }

    private DependencyProbeSnapshot probeMilvus() {
        if (milvusClient == null) {
            return probeTcp("milvus", properties.getMilvusHost(), properties.getMilvusPort());
        }
        long started = System.nanoTime();
        try {
            R<CheckHealthResponse> health = milvusClient.checkHealth();
            if (health == null || health.getStatus() == null || health.getStatus() != 0
                    || health.getData() == null || !health.getData().getIsHealthy()) {
                return snapshot("milvus", "DEGRADED", elapsedMillis(started), null,
                        "Milvus health check failed");
            }
            R<GetVersionResponse> version = milvusClient.getVersion();
            if (version == null || version.getStatus() == null || version.getStatus() != 0
                    || version.getData() == null || version.getData().getVersion().isBlank()) {
                return snapshot("milvus", "DEGRADED", elapsedMillis(started), null,
                        "Milvus version check failed");
            }
            return snapshot("milvus", "UP", elapsedMillis(started), null,
                    "Milvus health and version check passed");
        } catch (RuntimeException e) {
            return snapshot("milvus", "DOWN", elapsedMillis(started), null, safeMessage(e));
        }
    }

    private DependencyProbeSnapshot probeMcp() {
        return properties.isMcpEnabled()
                ? state("mcp", "NOT_CONFIGURED", "MCP enabled without a probe endpoint")
                : state("mcp", "NOT_CONFIGURED", "MCP client disabled");
    }

    private DependencyProbeSnapshot state(String name, String state, String message) {
        return snapshot(name, state, 0L, null, message);
    }

    private DependencyProbeSnapshot snapshot(String name, String state, long latencyMs,
                                              Integer statusCode, String message) {
        DependencyProbeSnapshot snapshot = new DependencyProbeSnapshot();
        snapshot.setName(name);
        snapshot.setState(state);
        snapshot.setLatencyMs(latencyMs);
        snapshot.setStatusCode(statusCode);
        snapshot.setMessage(message);
        snapshot.setCheckedAt(System.currentTimeMillis());
        if (observabilityMetrics != null) {
            observabilityMetrics.recordDependencyProbe(name, state, latencyMs);
        }
        return snapshot;
    }

    private String joinUrl(String baseUrl, String path) {
        String base = baseUrl.trim();
        if (base.endsWith("/") && path.startsWith("/")) {
            return base.substring(0, base.length() - 1) + path;
        }
        if (!base.endsWith("/") && !path.startsWith("/")) {
            return base + "/" + path;
        }
        return base + path;
    }

    private long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - started));
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static final class ProbeFailure extends RuntimeException {

        private ProbeFailure(Exception cause) {
            super(cause.getMessage(), cause);
        }
    }

    private enum PayloadSemantic {
        NONE,
        CLS,
        PROMETHEUS,
        DASHSCOPE_DEPLOYABLE_MODELS
    }
}
