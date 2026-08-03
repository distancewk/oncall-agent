package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import org.example.constant.MilvusConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Read-only inventory for identifying tagged and legacy untagged vectors. */
@Service
public class MilvusVectorInventoryService {

    private final ObjectMapper objectMapper;
    private final MilvusServiceClient milvusClient;

    @Autowired
    public MilvusVectorInventoryService(ObjectMapper objectMapper,
                                        @Lazy MilvusServiceClient milvusClient) {
        this.objectMapper = objectMapper;
        this.milvusClient = milvusClient;
    }

    public Inventory inventory(int requestedLimit, long requestedOffset) {
        int limit = Math.max(1, Math.min(1_000, requestedLimit));
        long offset = Math.max(0L, requestedOffset);
        QueryParam query = QueryParam.newBuilder()
                .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .withExpr("id != \"\"")
                .withOffset(offset)
                .withLimit((long) limit)
                .withOutFields(List.of("id", "metadata"))
                .build();
        R<QueryResults> response = milvusClient.query(query);
        if (response.getStatus() != 0) {
            throw new IllegalStateException("读取 Milvus 向量清单失败: " + response.getMessage());
        }
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        List<VectorEntry> entries = new ArrayList<>();
        int tagged = 0;
        int quarantined = 0;
        for (QueryResultsWrapper.RowRecord row : wrapper.getRowRecords()) {
            String id = stringValue(row.get("id"));
            JsonNode metadata = parseMetadata(row.get("metadata"));
            String tenantId = metadata.path("tenant_id").asText("");
            boolean isolated = tenantId.isBlank();
            if (isolated) {
                quarantined++;
            } else {
                tagged++;
            }
            entries.add(new VectorEntry(id,
                    metadata.path("tenant_id").asText(null),
                    metadata.path("_document_id").asText(null),
                    metadata.path("_content_hash").asText(null),
                    metadata.path("_source").asText(null), isolated));
        }
        return new Inventory(offset, limit, entries.size(), tagged, quarantined, List.copyOf(entries));
    }

    private JsonNode parseMetadata(Object value) {
        if (value == null) {
            return objectMapper.createObjectNode();
        }
        if (value instanceof String text) {
            try {
                return objectMapper.readTree(text);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return objectMapper.createObjectNode();
            }
        }
        try {
            return objectMapper.valueToTree(value);
        } catch (IllegalArgumentException e) {
            return objectMapper.createObjectNode();
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public record Inventory(long offset, int limit, int returned, int tagged,
                           int quarantined, List<VectorEntry> entries) {
    }

    public record VectorEntry(String id, String tenantId, String documentId,
                              String contentHash, String source, boolean quarantined) {
    }
}
