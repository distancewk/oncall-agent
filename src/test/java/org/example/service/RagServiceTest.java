package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import io.reactivex.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    private RagService ragService;

    @Mock
    private VectorSearchService vectorSearchService;

    @Mock
    private Generation generation;

    @BeforeEach
    void setUp() {
        ragService = new RagService();
        ReflectionTestUtils.setField(ragService, "vectorSearchService", vectorSearchService);
        ReflectionTestUtils.setField(ragService, "topK", 3);
        ReflectionTestUtils.setField(ragService, "apiKey", "test-api-key");
        ReflectionTestUtils.setField(ragService, "model", "qwen3-max");
        ReflectionTestUtils.setField(ragService, "generation", generation);
    }

    @Test
    void queryStream_shouldReturnNoResults_whenSearchReturnsEmpty() throws Exception {
        when(vectorSearchService.searchSimilarDocuments(anyString(), anyInt()))
            .thenReturn(new ArrayList<>());

        final boolean[] completed = {false};
        ragService.queryStream("test question", new RagService.StreamCallback() {
            @Override
            public void onSearchResults(List<VectorSearchService.SearchResult> results) {
                assertTrue(results.isEmpty());
            }
            @Override
            public void onReasoningChunk(String chunk) {}
            @Override
            public void onContentChunk(String chunk) {}
            @Override
            public void onComplete(String fullContent, String fullReasoning) {
                completed[0] = true;
            }
            @Override
            public void onError(Exception e) {
                fail("Should not error when search is empty");
            }
        });
    }

    @Test
    void queryStream_shouldPassSearchResultsToCallback_whenSearchReturnsResults() throws Exception {
        VectorSearchService.SearchResult searchResult = new VectorSearchService.SearchResult();
        searchResult.setId("doc1");
        searchResult.setContent("Test document content");
        searchResult.setScore(0.95f);
        searchResult.setMetadata("source:test");

        when(vectorSearchService.searchSimilarDocuments(anyString(), anyInt()))
            .thenReturn(List.of(searchResult));
        when(generation.streamCall(any())).thenReturn(Flowable.empty());

        final boolean[] searchResultsReceived = {false};
        ragService.queryStream("test question", new RagService.StreamCallback() {
            @Override
            public void onSearchResults(List<VectorSearchService.SearchResult> results) {
                assertEquals(1, results.size());
                assertEquals("doc1", results.get(0).getId());
                searchResultsReceived[0] = true;
            }
            @Override
            public void onReasoningChunk(String chunk) {}
            @Override
            public void onContentChunk(String chunk) {}
            @Override
            public void onComplete(String fullContent, String fullReasoning) {}
            @Override
            public void onError(Exception e) {
                fail("Should not error: " + e.getMessage());
            }
        });

        assertTrue(searchResultsReceived[0]);
    }

    @Test
    void queryStream_shouldHandleHistoryMessages() throws Exception {
        when(vectorSearchService.searchSimilarDocuments(anyString(), anyInt()))
            .thenReturn(new ArrayList<>());

        List<Map<String, String>> history = new ArrayList<>();
        history.add(Map.of("role", "user", "content", "previous question"));
        history.add(Map.of("role", "assistant", "content", "previous answer"));

        final boolean[] completed = {false};
        ragService.queryStream("new question", history, new RagService.StreamCallback() {
            @Override
            public void onSearchResults(List<VectorSearchService.SearchResult> results) {}
            @Override
            public void onReasoningChunk(String chunk) {}
            @Override
            public void onContentChunk(String chunk) {}
            @Override
            public void onComplete(String fullContent, String fullReasoning) {
                completed[0] = true;
            }
            @Override
            public void onError(Exception e) {
                fail("Should not error with history: " + e.getMessage());
            }
        });
    }
}
