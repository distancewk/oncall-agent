package org.example.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.exception.DependencyUnavailableException;
import org.example.service.VectorSearchService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalDocsToolsTest {

    @Test
    void queryInternalDocs_shouldReturnStructuredCircuitOpenError_whenVectorSearchBreakerOpen() throws Exception {
        VectorSearchService vectorSearchService = mock(VectorSearchService.class);
        when(vectorSearchService.searchSimilarDocuments(eq("cpu runbook"), eq(3)))
                .thenThrow(new DependencyUnavailableException("milvus", "hybridSearch", "CIRCUIT_OPEN", null));

        InternalDocsTools tools = new InternalDocsTools(vectorSearchService);

        JsonNode response = new ObjectMapper().readTree(tools.queryInternalDocs("cpu runbook"));

        assertEquals(false, response.path("success").asBoolean());
        assertEquals("error", response.path("status").asText());
        assertEquals("CIRCUIT_OPEN", response.path("errorCode").asText());
        assertEquals(true, response.path("message").asText().contains("Dependency unavailable"));
    }
}
