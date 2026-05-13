package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class VectorRerankService {

    private static final Logger logger = LoggerFactory.getLogger(VectorRerankService.class);

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.rerank.model:gte-rerank}")
    private String model;

    @Value("${dashscope.rerank.url}")
    private String rerankUrl;

    @Autowired
    private OkHttpClient httpClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<VectorSearchService.SearchResult> rerank(String query, List<VectorSearchService.SearchResult> candidates, int topK) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }

        if (candidates.size() <= topK) {
            // 如果候选数量少于等于所需 topK，可能不需要重排，或者也可以重排。
            // 为了提升精度，只要有结果都重排，截取 topK。
        }

        logger.info("开始调用阿里 GTE-Rerank 重排, 候选文档数: {}, 目标返回数: {}", candidates.size(), topK);

        try {
            // 构建请求 JSON
            List<String> docs = new ArrayList<>();
            for (VectorSearchService.SearchResult res : candidates) {
                docs.add(res.getContent());
            }

            Map<String, Object> input = new HashMap<>();
            input.put("query", query);
            input.put("documents", docs);

            Map<String, Object> parameters = new HashMap<>();
            parameters.put("top_n", topK);
            parameters.put("return_documents", false); // 不返回原文本，依靠 index 匹配

            Map<String, Object> requestBodyMap = new HashMap<>();
            requestBodyMap.put("model", model);
            requestBodyMap.put("input", input);
            requestBodyMap.put("parameters", parameters);

            String jsonBody = objectMapper.writeValueAsString(requestBodyMap);

            RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(rerankUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute();
                 ResponseBody responseBody = response.body()) {
                if (!response.isSuccessful()) {
                    String errorMsg = responseBody != null ? responseBody.string() : "No response body";
                    logger.error("Rerank API 调用失败: HTTP {}, {}", response.code(), errorMsg);
                    // 降级：如果重排失败，直接按原样返回前 topK
                    return defensiveCopyTopK(candidates, topK);
                }

                if (responseBody == null) {
                    logger.warn("Rerank API 返回空响应体");
                    return defensiveCopyTopK(candidates, topK);
                }

                String responseStr = responseBody.string();
                JsonNode root = objectMapper.readTree(responseStr);
                
                JsonNode resultsNode = root.path("output").path("results");
                if (!resultsNode.isArray()) {
                    logger.warn("Rerank API 返回格式异常: {}", responseStr);
                    return defensiveCopyTopK(candidates, topK);
                }

                List<VectorSearchService.SearchResult> finalResults = new ArrayList<>();
                for (JsonNode node : resultsNode) {
                    int index = node.path("index").asInt();
                    double relevanceScore = node.path("relevance_score").asDouble();
                    
                    if (index >= 0 && index < candidates.size()) {
                        VectorSearchService.SearchResult original = candidates.get(index);
                        VectorSearchService.SearchResult copy = new VectorSearchService.SearchResult();
                        copy.setId(original.getId());
                        copy.setContent(original.getContent());
                        copy.setScore((float) relevanceScore);
                        copy.setMetadata(original.getMetadata());
                        finalResults.add(copy);
                    }
                }

                logger.info("重排完成, 实际返回 {} 篇文档", finalResults.size());
                return finalResults;
            }

        } catch (IOException e) {
            logger.error("Rerank API 网络请求异常", e);
            return defensiveCopyTopK(candidates, topK);
        } catch (Exception e) {
            logger.error("Rerank 处理失败", e);
            return defensiveCopyTopK(candidates, topK);
        }
    }

    private List<VectorSearchService.SearchResult> defensiveCopyTopK(
            List<VectorSearchService.SearchResult> candidates, int topK) {
        List<VectorSearchService.SearchResult> result = new ArrayList<>();
        int limit = Math.min(topK, candidates.size());
        for (int i = 0; i < limit; i++) {
            VectorSearchService.SearchResult original = candidates.get(i);
            VectorSearchService.SearchResult copy = new VectorSearchService.SearchResult();
            copy.setId(original.getId());
            copy.setContent(original.getContent());
            copy.setScore(original.getScore());
            copy.setMetadata(original.getMetadata());
            result.add(copy);
        }
        return result;
    }
}
