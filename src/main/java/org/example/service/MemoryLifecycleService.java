package org.example.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.UpsertParam;
import org.example.config.AppMemoryProperties;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.UUID;

@Service
public class MemoryLifecycleService {

    private static final Logger logger = LoggerFactory.getLogger(MemoryLifecycleService.class);
    private static final String MEMORY_PREFIX = "[用户私人记忆] ";
    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final MilvusInsertHelper insertHelper;
    private final ObjectMapper objectMapper;
    private final AppMemoryProperties properties;

    @Autowired
    @Lazy
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired(required = false)
    private DependencyGuard dependencyGuard;

    public MemoryLifecycleService(MilvusInsertHelper insertHelper,
                                  ObjectMapper objectMapper,
                                  AppMemoryProperties properties) {
        this.insertHelper = insertHelper;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String stableMemoryId(String sessionId, String fact) {
        String normalizedFact = normalizeFact(fact);
        return UUID.nameUUIDFromBytes(("chat_memory:" + sessionId + ":" + normalizedFact)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    public Map<String, Object> buildMemoryMetadata(String sessionId, String fact, long now) {
        String normalizedFact = normalizeFact(fact);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("_source", MilvusConstants.DOC_TYPE_CHAT_MEMORY);
        metadata.put("doc_type", MilvusConstants.DOC_TYPE_CHAT_MEMORY);
        metadata.put("session_id", sessionId);
        metadata.put("created_at", now);
        metadata.put("last_seen_at", now);
        metadata.put("timestamp", now);
        metadata.put("deleted", false);
        metadata.put("content_hash", contentHash(normalizedFact));
        return metadata;
    }

    public void storeMemory(String sessionId, String fact) {
        String normalizedFact = normalizeFact(fact);
        if (normalizedFact.isEmpty()) {
            return;
        }

        try {
            String content = MEMORY_PREFIX + normalizedFact;
            List<Float> vector = embeddingService.generateEmbedding(content);
            SortedMap<Long, Float> sparseVector = embeddingService.generateSparseVector(content);
            Map<String, Object> metadata = buildMemoryMetadata(sessionId, normalizedFact, System.currentTimeMillis());

            R<RpcStatus> loadResponse = DependencyGuardExecutor.executeMilvus(
                    dependencyGuard,
                    "storeMemoryLoadCollection",
                    () -> milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                            .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                            .build()));

            if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
                logger.error("加载 collection 失败: {}", loadResponse.getMessage());
                return;
            }

            String id = stableMemoryId(sessionId, normalizedFact);
            InsertParam insertParam = insertHelper.buildInsertParam(
                    List.of(id),
                    List.of(content),
                    List.of(vector),
                    List.of(sparseVector),
                    List.of(metadata));
            UpsertParam upsertParam = UpsertParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withFields(insertParam.getFields())
                    .build();

            R<MutationResult> insertResponse = DependencyGuardExecutor.executeMilvus(
                    dependencyGuard, "storeMemoryUpsert", () -> milvusClient.upsert(upsertParam));

            if (insertResponse.getStatus() != 0) {
                logger.error("长期记忆写入 Milvus 失败: {}", insertResponse.getMessage());
            } else {
                logger.debug("长期记忆成功写入 Milvus，ID: {}", id);
            }
        } catch (Exception e) {
            logger.error("长期记忆写入 Milvus 时发生异常", e);
        }
    }

    public void deleteSessionMemories(String sessionId) {
        String expr = "metadata[\"doc_type\"] == \"" + MilvusConstants.DOC_TYPE_CHAT_MEMORY
                + "\" && metadata[\"session_id\"] == \"" + escapeExprValue(sessionId) + "\"";
        try {
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = DependencyGuardExecutor.executeMilvus(
                    dependencyGuard, "deleteSessionMemories", () -> milvusClient.delete(deleteParam));

            if (response.getStatus() != 0) {
                throw new IllegalStateException("删除会话私人记忆失败: " + response.getMessage());
            }
        } catch (Exception e) {
            throw new IllegalStateException("删除会话私人记忆异常: " + sessionId, e);
        }
    }

    public List<VectorSearchService.SearchResult> filterRecallResults(List<VectorSearchService.SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }

        List<VectorSearchService.SearchResult> filtered = new ArrayList<>();
        int maxPromptChars = properties.getPrivateRecallMaxPromptChars();
        int usedPromptChars = 0;
        for (VectorSearchService.SearchResult result : results) {
            if (result == null || result.getScore() < properties.getPrivateRecallMinScore() || isDeleted(result)) {
                continue;
            }
            String content = result.getContent() == null ? "" : result.getContent();
            if (usedPromptChars + content.length() > maxPromptChars) {
                continue;
            }
            filtered.add(result);
            usedPromptChars += content.length();
        }
        return filtered;
    }

    private String normalizeFact(String fact) {
        return fact == null ? "" : fact.trim().replaceAll("\\s+", " ");
    }

    private boolean isDeleted(VectorSearchService.SearchResult result) {
        String metadata = result.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(metadata, METADATA_TYPE);
            return Boolean.TRUE.equals(parsed.get("deleted"));
        } catch (Exception e) {
            logger.debug("解析私人记忆 metadata 失败，按已删除跳过: id={}", result.getId(), e);
            return true;
        }
    }

    private String contentHash(String normalizedFact) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalizedFact.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    private String escapeExprValue(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
