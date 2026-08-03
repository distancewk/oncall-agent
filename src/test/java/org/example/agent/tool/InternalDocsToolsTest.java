package org.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.exception.DependencyUnavailableException;
import org.example.service.RagCitationRegistry;
import org.example.service.VectorSearchService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InternalDocsToolsTest {

    @org.junit.jupiter.api.AfterEach
    void clearCitationRegistry() {
        RagCitationRegistry.clearCurrent();
    }

    @Test
    void queryInternalDocs_shouldReturnStructuredCircuitOpenError_whenVectorSearchBreakerOpen() throws Exception {
        VectorSearchService vectorSearchService = mock(VectorSearchService.class);
        when(vectorSearchService.searchSimilarDocuments(anyString(), eq(3)))
                .thenThrow(new DependencyUnavailableException("milvus", "hybridSearch", "CIRCUIT_OPEN", null));

        InternalDocsTools tools = new InternalDocsTools(vectorSearchService);

        JsonNode response = new ObjectMapper().readTree(tools.queryInternalDocs("cpu runbook"));

        assertEquals(false, response.path("success").asBoolean());
        assertEquals("error", response.path("status").asText());
        assertEquals("CIRCUIT_OPEN", response.path("errorCode").asText());
        assertEquals(true, response.path("message").asText().contains("Dependency unavailable"));
    }

    @Test
    void queryInternalDocs_shouldRecordReturnedIdsAndExpandRetrievalQuery() throws Exception {
        VectorSearchService vectorSearchService = mock(VectorSearchService.class);
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId("doc-release");
        result.setContent("发布流程");
        when(vectorSearchService.searchSimilarDocuments(anyString(), eq(3)))
                .thenReturn(java.util.List.of(result));

        InternalDocsTools tools = new InternalDocsTools(vectorSearchService);
        JsonNode response = new ObjectMapper().readTree(tools.queryInternalDocs("发布流程"));

        assertEquals("doc-release", response.get(0).path("id").asText());
        assertTrue(RagCitationRegistry.consumeCurrent().contains("doc-release"));
        org.mockito.ArgumentCaptor<String> query = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(vectorSearchService).searchSimilarDocuments(query.capture(), eq(3));
        assertTrue(query.getValue().contains("deploy rollback release"));
    }
}
