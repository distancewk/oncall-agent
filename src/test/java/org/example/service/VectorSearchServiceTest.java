package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.AnnSearchParam;
import io.milvus.param.dml.HybridSearchParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VectorSearchServiceTest {

    private VectorSearchService vectorSearchService;

    @Mock
    private MilvusServiceClient milvusClient;

    @Mock
    private VectorEmbeddingService embeddingService;

    @Mock
    private VectorRerankService rerankService;

    @BeforeEach
    void setUp() {
        vectorSearchService = new VectorSearchService();
        ReflectionTestUtils.setField(vectorSearchService, "milvusClient", milvusClient);
        ReflectionTestUtils.setField(vectorSearchService, "embeddingService", embeddingService);
        ReflectionTestUtils.setField(vectorSearchService, "rerankService", rerankService);
        ReflectionTestUtils.setField(vectorSearchService, "candidateK", 10);
        ReflectionTestUtils.setField(vectorSearchService, "searchEf", 64);
    }

    @Test
    void searchSimilarDocuments_shouldThrowException_whenMilvusSearchFails() {
        when(embeddingService.generateQueryVector("test query")).thenReturn(List.of(0.1f, 0.2f));
        TreeMap<Long, Float> sparseVector = new TreeMap<>();
        sparseVector.put(1L, 0.5f);
        when(embeddingService.generateSparseVector("test query")).thenReturn(sparseVector);

        R<SearchResults> errorResponse = R.failed(R.Status.UnexpectedError, "search failed");
        when(milvusClient.hybridSearch(any(HybridSearchParam.class))).thenReturn(errorResponse);

        assertThrows(RuntimeException.class, () ->
            vectorSearchService.searchSimilarDocuments("test query", 5));
    }

    @Test
    void searchSimilarDocuments_shouldThrowException_whenQueryIsEmpty() {
        when(embeddingService.generateQueryVector("")).thenThrow(new IllegalArgumentException("内容不能为空"));

        assertThrows(RuntimeException.class, () ->
            vectorSearchService.searchSimilarDocuments("", 5));
    }

    @Test
    void searchSimilarDocuments_shouldThrowException_whenSearchResultIsEmpty() {
        when(embeddingService.generateQueryVector("query")).thenReturn(List.of(0.1f, 0.2f, 0.3f));
        TreeMap<Long, Float> sparseVector = new TreeMap<>();
        sparseVector.put(1L, 0.5f);
        when(embeddingService.generateSparseVector("query")).thenReturn(sparseVector);

        R<SearchResults> successResponse = R.success(SearchResults.getDefaultInstance());
        when(milvusClient.hybridSearch(any(HybridSearchParam.class))).thenReturn(successResponse);

        // SearchResults.getDefaultInstance() has empty results which causes issues in the wrapper
        assertThrows(Exception.class, () ->
            vectorSearchService.searchSimilarDocuments("query", 3));
    }

    @Test
    void searchSimilarDocuments_shouldExcludeChatMemoryAndUseHnswEf() {
        when(embeddingService.generateQueryVector("disk alert")).thenReturn(List.of(0.1f, 0.2f));
        TreeMap<Long, Float> sparseVector = new TreeMap<>();
        sparseVector.put(1L, 0.5f);
        when(embeddingService.generateSparseVector("disk alert")).thenReturn(sparseVector);

        R<SearchResults> errorResponse = R.failed(R.Status.UnexpectedError, "search failed");
        when(milvusClient.hybridSearch(any(HybridSearchParam.class))).thenReturn(errorResponse);

        assertThrows(RuntimeException.class, () ->
            vectorSearchService.searchSimilarDocuments("disk alert", 5));

        org.mockito.ArgumentCaptor<HybridSearchParam> captor =
                org.mockito.ArgumentCaptor.forClass(HybridSearchParam.class);
        verify(milvusClient).hybridSearch(captor.capture());

        HybridSearchParam param = captor.getValue();
        assertEquals(2, param.getSearchRequests().size());
        for (AnnSearchParam request : param.getSearchRequests()) {
            assertEquals("metadata[\"doc_type\"] == \"document\"", request.getExpr());
        }
        assertTrue(param.getSearchRequests().get(0).getParams().contains("\"ef\":64"));
    }

    @Test
    void searchSessionMemories_shouldRequireChatMemoryDocTypeAndSessionIdFilter() {
        when(embeddingService.generateQueryVector("preference")).thenReturn(List.of(0.1f, 0.2f));
        TreeMap<Long, Float> sparseVector = new TreeMap<>();
        sparseVector.put(1L, 0.5f);
        when(embeddingService.generateSparseVector("preference")).thenReturn(sparseVector);

        R<SearchResults> errorResponse = R.failed(R.Status.UnexpectedError, "search failed");
        when(milvusClient.hybridSearch(any(HybridSearchParam.class))).thenReturn(errorResponse);

        assertThrows(RuntimeException.class, () ->
                vectorSearchService.searchSessionMemories("preference", "session-123", 5));

        org.mockito.ArgumentCaptor<HybridSearchParam> captor =
                org.mockito.ArgumentCaptor.forClass(HybridSearchParam.class);
        verify(milvusClient).hybridSearch(captor.capture());

        HybridSearchParam param = captor.getValue();
        for (AnnSearchParam request : param.getSearchRequests()) {
            assertEquals("metadata[\"doc_type\"] == \"chat_memory\" && metadata[\"session_id\"] == \"session-123\"",
                    request.getExpr());
        }
    }
}
