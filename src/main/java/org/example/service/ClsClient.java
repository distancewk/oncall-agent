package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Shared native CLS request construction and execution for probes and tools. */
@Service
public class ClsClient {

    private static final MediaType JSON = MediaType.get("application/json");
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ClsClient(OkHttpClient httpClient) {
        this(httpClient, new ObjectMapper());
    }

    @Autowired
    public ClsClient(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public Response execute(SearchRequest search) throws IOException {
        if (search == null || search.baseUrl() == null || search.baseUrl().isBlank()) {
            throw new IllegalArgumentException("CLS base-url 未配置");
        }
        if (search.topicId() == null || search.topicId().isBlank()) {
            throw new IllegalArgumentException("CLS TopicId 未配置");
        }
        String path = normalizePath(search.path());
        URI uri = URI.create(joinUrl(search.baseUrl(), path));
        String body = buildBody(search);
        Request.Builder requestBuilder = new Request.Builder()
                .url(uri.toString())
                .post(RequestBody.create(body, JSON));
        ClsRequestSigner.sign(requestBuilder, uri, "POST", body,
                search.secretId(), search.secretKey(), search.region(), search.service(), Instant.now());
        OkHttpClient client = httpClient.newBuilder()
                .callTimeout(Math.max(1, search.timeoutMillis()), TimeUnit.MILLISECONDS)
                .build();
        return client.newCall(requestBuilder.build()).execute();
    }

    String buildBody(SearchRequest search) throws JsonProcessingException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("TopicId", search.topicId().trim());
        payload.put("From", search.fromMillis());
        payload.put("To", search.toMillis());
        payload.put("QueryString", search.query() == null || search.query().isBlank()
                ? "*" : search.query());
        payload.put("QuerySyntax", 1);
        payload.put("Limit", Math.max(1, Math.min(1000, search.limit())));
        payload.put("Sort", "desc");
        payload.put("UseNewAnalysis", true);
        return objectMapper.writeValueAsString(payload);
    }

    private String normalizePath(String path) {
        return path == null || path.isBlank() || "/api/v1/logs/query".equals(path) ? "/" : path;
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

    public record SearchRequest(String baseUrl, String path, String topicId,
                                long fromMillis, long toMillis, String query, int limit,
                                String secretId, String secretKey, String region,
                                String service, int timeoutMillis) {
    }
}
