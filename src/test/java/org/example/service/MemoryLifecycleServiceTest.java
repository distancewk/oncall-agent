package org.example.service;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryLifecycleServiceTest {

    private final MilvusInsertHelper insertHelper = new MilvusInsertHelper();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AppMemoryProperties properties = new AppMemoryProperties();

    @Test
    void stableMemoryId_shouldBeSameForEquivalentFactsInSameSession() {
        MemoryLifecycleService service = new MemoryLifecycleService(insertHelper, objectMapper, properties);

        String first = service.stableMemoryId("session-1", "  用户偏好 Java 方案  ");
        String second = service.stableMemoryId("session-1", "用户偏好   Java\n方案");

        assertEquals(first, second);
    }

    @Test
    void buildMemoryMetadata_shouldIncludeSessionDocTypeHashAndTimestamps() {
        long now = 1_725_000_000_000L;
        MemoryLifecycleService service = new MemoryLifecycleService(insertHelper, objectMapper, properties);

        Map<String, Object> metadata = service.buildMemoryMetadata("session-1", "用户偏好 Java 方案", now);

        assertEquals("chat_memory", metadata.get("_source"));
        assertEquals("chat_memory", metadata.get("doc_type"));
        assertEquals("session-1", metadata.get("session_id"));
        assertEquals(now, metadata.get("created_at"));
        assertEquals(now, metadata.get("last_seen_at"));
        assertEquals(false, metadata.get("deleted"));
        assertNotNull(metadata.get("content_hash"));
    }

    @Test
    void deleteSessionMemories_shouldUseChatMemorySessionFilterAndSurfaceMilvusFailure() {
        MemoryLifecycleService service = new MemoryLifecycleService(insertHelper, objectMapper, properties);
        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        @SuppressWarnings("unchecked")
        R<MutationResult> failure = mock(R.class);
        when(failure.getStatus()).thenReturn(1);
        when(failure.getMessage()).thenReturn("delete failed");
        when(milvusClient.delete(any(DeleteParam.class))).thenReturn(failure);
        ReflectionTestUtils.setField(service, "milvusClient", milvusClient);

        assertThrows(IllegalStateException.class, () -> service.deleteSessionMemories("session\"1"));

        ArgumentCaptor<DeleteParam> captor = ArgumentCaptor.forClass(DeleteParam.class);
        verify(milvusClient).delete(captor.capture());
        assertEquals("metadata[\"doc_type\"] == \"chat_memory\" && metadata[\"session_id\"] == \"session\\\"1\"",
                captor.getValue().getExpr());
    }

    @Test
    void filterRecallResults_shouldDropDeletedAndRespectScoreAndPromptBudget() {
        properties.setPrivateRecallMinScore(0.5f);
        properties.setPrivateRecallMaxPromptChars(18);
        MemoryLifecycleService service = new MemoryLifecycleService(insertHelper, objectMapper, properties);

        VectorSearchService.SearchResult kept = result("1", "[用户私人记忆] Java", 0.9f,
                "{\"deleted\":false}");
        VectorSearchService.SearchResult lowScore = result("2", "[用户私人记忆] Python", 0.4f,
                "{\"deleted\":false}");
        VectorSearchService.SearchResult deleted = result("3", "[用户私人记忆] Go", 0.95f,
                "{\"deleted\":true}");
        VectorSearchService.SearchResult overBudget = result("4", "[用户私人记忆] Kubernetes", 0.8f,
                "{\"deleted\":false}");

        List<VectorSearchService.SearchResult> filtered = service.filterRecallResults(
                List.of(kept, lowScore, deleted, overBudget));

        assertEquals(1, filtered.size());
        assertSame(kept, filtered.get(0));
    }

    @Test
    void filterRecallResults_shouldDropMalformedMetadataFailClosed() {
        MemoryLifecycleService service = new MemoryLifecycleService(insertHelper, objectMapper, properties);

        List<VectorSearchService.SearchResult> filtered = service.filterRecallResults(List.of(
                result("malformed", "[用户私人记忆] Java", 0.9f, "{not-json")));

        assertEquals(0, filtered.size());
    }

    @Test
    void storeMemory_shouldUpsertNormalizedPrefixedMemoryWithoutDeletingFirst() {
        MemoryLifecycleService service = new MemoryLifecycleService(insertHelper, objectMapper, properties);
        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);

        @SuppressWarnings("unchecked")
        R<RpcStatus> loadResponse = mock(R.class);
        when(loadResponse.getStatus()).thenReturn(0);
        @SuppressWarnings("unchecked")
        R<MutationResult> mutationResponse = mock(R.class);
        when(mutationResponse.getStatus()).thenReturn(0);
        when(milvusClient.loadCollection(any(LoadCollectionParam.class))).thenReturn(loadResponse);
        when(milvusClient.delete(any(DeleteParam.class))).thenReturn(mutationResponse);
        when(milvusClient.upsert(any(UpsertParam.class))).thenReturn(mutationResponse);
        when(embeddingService.generateEmbedding("[用户私人记忆] 用户偏好 Java 方案"))
                .thenReturn(List.of(0.1f, 0.2f));
        SortedMap<Long, Float> sparseVector = new java.util.TreeMap<>();
        sparseVector.put(1L, 0.5f);
        when(embeddingService.generateSparseVector("[用户私人记忆] 用户偏好 Java 方案"))
                .thenReturn(sparseVector);
        ReflectionTestUtils.setField(service, "milvusClient", milvusClient);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);

        service.storeMemory("session-1", "  用户偏好   Java\n方案 ");

        String expectedId = service.stableMemoryId("session-1", "用户偏好 Java 方案");
        verify(milvusClient, never()).delete(any(DeleteParam.class));
        verify(milvusClient, never()).insert(any(InsertParam.class));

        ArgumentCaptor<UpsertParam> upsertCaptor = ArgumentCaptor.forClass(UpsertParam.class);
        verify(milvusClient).upsert(upsertCaptor.capture());
        UpsertParam upsertParam = upsertCaptor.getValue();
        assertEquals(List.of(expectedId), fieldValues(upsertParam, "id"));
        assertEquals(List.of("[用户私人记忆] 用户偏好 Java 方案"), fieldValues(upsertParam, "content"));
        assertEquals(1, fieldValues(upsertParam, "metadata").size());
    }

    @Test
    void storeMemory_shouldIgnoreBlankFacts() {
        MemoryLifecycleService service = new MemoryLifecycleService(insertHelper, objectMapper, properties);
        MilvusServiceClient milvusClient = mock(MilvusServiceClient.class);
        VectorEmbeddingService embeddingService = mock(VectorEmbeddingService.class);
        ReflectionTestUtils.setField(service, "milvusClient", milvusClient);
        ReflectionTestUtils.setField(service, "embeddingService", embeddingService);

        service.storeMemory("session-1", " \n\t ");

        verify(embeddingService, never()).generateEmbedding(any());
        verify(milvusClient, never()).loadCollection(any());
        verify(milvusClient, never()).delete(any(DeleteParam.class));
        verify(milvusClient, never()).insert(any(InsertParam.class));
        verify(milvusClient, never()).upsert(any(UpsertParam.class));
    }

    private VectorSearchService.SearchResult result(String id, String content, float score, String metadata) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(id);
        result.setContent(content);
        result.setScore(score);
        result.setMetadata(metadata);
        return result;
    }

    private List<?> fieldValues(InsertParam insertParam, String fieldName) {
        return insertParam.getFields().stream()
                .filter(field -> fieldName.equals(field.getName()))
                .findFirst()
                .orElseThrow()
                .getValues();
    }
}
