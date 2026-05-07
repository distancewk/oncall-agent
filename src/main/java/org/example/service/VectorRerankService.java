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
                    .url("https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank")
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorMsg = response.body() != null ? response.body().string() : "No response body";
                    logger.error("Rerank API 调用失败: HTTP {}, {}", response.code(), errorMsg);
                    // 降级：如果重排失败，直接按原样返回前 topK
                    return candidates.subList(0, Math.min(topK, candidates.size()));
                }

                String responseStr = response.body().string();
                JsonNode root = objectMapper.readTree(responseStr);
                
                JsonNode resultsNode = root.path("output").path("results");
                if (!resultsNode.isArray()) {
                    logger.warn("Rerank API 返回格式异常: {}", responseStr);
                    return candidates.subList(0, Math.min(topK, candidates.size()));
                }

                List<VectorSearchService.SearchResult> finalResults = new ArrayList<>();
                for (JsonNode node : resultsNode) {
                    int index = node.path("index").asInt();
                    double relevanceScore = node.path("relevance_score").asDouble();
                    
                    if (index >= 0 && index < candidates.size()) {
                        VectorSearchService.SearchResult originalResult = candidates.get(index);
                        // 更新为 Rerank 后的精准得分
                        originalResult.setScore((float) relevanceScore);
                        finalResults.add(originalResult);
                    }
                }

                logger.info("重排完成, 实际返回 {} 篇文档", finalResults.size());
                return finalResults;
            }

        } catch (IOException e) {
            logger.error("Rerank API 网络请求异常", e);
            return candidates.subList(0, Math.min(topK, candidates.size()));
        } catch (Exception e) {
            logger.error("Rerank 处理失败", e);
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }
}
