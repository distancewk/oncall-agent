package org.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppResilienceProperties;
import org.example.service.DependencyGuard;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;

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
        assertTrue(description.contains("at most 3 queryLogs calls"));
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

    @Test
    void queryLogs_shouldCallConfiguredHttpEndpointInRealMode() throws Exception {
        AtomicReference<URI> seenUri = new AtomicReference<>();
        QueryLogsTools tools = new QueryLogsTools();
        ReflectionTestUtils.setField(tools, "mockEnabled", false);
        ReflectionTestUtils.setField(tools, "baseUrl", "http://logs-gateway.example");
        ReflectionTestUtils.setField(tools, "httpClient", new FakeHttpClient(seenUri));

        JsonNode result = objectMapper.readTree(tools.queryLogs(
                "ap-guangzhou", "application-logs", "level:ERROR", 5));

        assertTrue(result.path("success").asBoolean());
        assertEquals(1, result.path("total").asInt());
        assertEquals("database timeout", result.path("logs").get(0).path("message").asText());
        assertTrue(seenUri.get().getRawQuery().contains("region=ap-guangzhou"));
        assertTrue(seenUri.get().getRawQuery().contains("logTopic=application-logs"));
        assertTrue(seenUri.get().getRawQuery().contains("limit=5"));
    }

    private DependencyGuard singleFailureGuard() {
        AppResilienceProperties properties = new AppResilienceProperties();
        properties.getDefaultConfig().setMinimumCalls(1);
        properties.getDefaultConfig().setFailureRateThreshold(1.0f);
        properties.getDefaultConfig().setOpenDuration(Duration.ofSeconds(30));
        return new DependencyGuard(properties);
    }

    private static class FakeHttpClient extends HttpClient {

        private final AtomicReference<URI> seenUri;

        FakeHttpClient(AtomicReference<URI> seenUri) {
            this.seenUri = seenUri;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> responseBodyHandler) {
            seenUri.set(request.uri());
            @SuppressWarnings("unchecked")
            T body = (T) """
                    {
                      "success": true,
                      "logs": [
                        {
                          "timestamp": "2026-06-17 10:00:00",
                          "level": "ERROR",
                          "service": "payment-service",
                          "instance": "pod-1",
                          "message": "database timeout",
                          "metrics": {"traceId": "abc-123"}
                        }
                      ],
                      "total": 1
                    }
                    """;
            return new FakeHttpResponse<>(request, body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("sendAsync is not used in this test");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("sendAsync is not used in this test");
        }

        @Override
        public WebSocket.Builder newWebSocketBuilder() {
            throw new UnsupportedOperationException("WebSocket is not used in this test");
        }
    }

    private record FakeHttpResponse<T>(HttpRequest request, T body) implements HttpResponse<T> {

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(java.util.Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
