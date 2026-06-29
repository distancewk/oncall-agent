package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppJobProperties;
import org.example.dto.ApiResponse;
import org.example.dto.IndexTaskStatus;
import org.example.service.BackgroundJobRepository;
import org.example.service.IncidentSchemaMigrator;
import org.example.service.IndexTaskStatusService;
import org.example.service.VectorSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeControllerTest {

    @TempDir
    private Path tempDir;

    @Mock
    private VectorSearchService vectorSearchService;

    private IndexTaskStatusService indexTaskStatusService;
    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl("jdbc:h2:" + tempDir.resolve("index-db"));
        dataSource.setUsername("");
        dataSource.setPassword("");
        IncidentSchemaMigrator migrator = new IncidentSchemaMigrator(dataSource);
        indexTaskStatusService = new IndexTaskStatusService(
                dataSource,
                migrator,
                new BackgroundJobRepository(dataSource, migrator),
                new ObjectMapper(),
                new AppJobProperties()
        );
        controller = new KnowledgeController(vectorSearchService, indexTaskStatusService);
    }

    @Test
    void searchKnowledge_shouldReturnSearchTrace() {
        VectorSearchService.SearchTrace trace = new VectorSearchService.SearchTrace();
        trace.setQuery("disk alert");
        trace.setRequestedTopK(5);
        trace.setSearchK(10);
        trace.setSearchEf(64);
        trace.setFilterExpr("metadata[\"doc_type\"] == \"document\"");

        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId("doc-1");
        result.setContent("Disk alert runbook");
        result.setScore(0.91f);
        result.setMetadata("{\"_source\":\"runbook.md\",\"doc_type\":\"document\"}");
        trace.setResults(List.of(result));

        when(vectorSearchService.explainSimilarDocuments("disk alert", 5)).thenReturn(trace);

        ApiResponse<VectorSearchService.SearchTrace> body = controller.searchKnowledge("disk alert", 5).getBody();

        assertNotNull(body);
        assertEquals(200, body.getCode());
        assertEquals("disk alert", body.getData().getQuery());
        assertEquals(10, body.getData().getSearchK());
        assertEquals(64, body.getData().getSearchEf());
        assertEquals(1, body.getData().getResults().size());
    }

    @Test
    void searchKnowledge_shouldClampTopKToReasonableRange() {
        VectorSearchService.SearchTrace trace = new VectorSearchService.SearchTrace();
        trace.setRequestedTopK(20);
        when(vectorSearchService.explainSimilarDocuments("capacity", 20)).thenReturn(trace);

        ApiResponse<VectorSearchService.SearchTrace> body = controller.searchKnowledge("capacity", 99).getBody();

        assertNotNull(body);
        assertEquals(20, body.getData().getRequestedTopK());
    }

    @Test
    void searchKnowledge_shouldRejectBlankQuery() {
        assertThrows(IllegalArgumentException.class, () -> controller.searchKnowledge("   ", 5));
    }

    @Test
    void listIndexTasks_shouldReturnKnownTasks() {
        indexTaskStatusService.createTask("runbook.md", "/tmp/runbook.md");
        indexTaskStatusService.createTask("notes.txt", "/tmp/notes.txt");

        ApiResponse<List<IndexTaskStatus>> body = controller.listIndexTasks().getBody();

        assertNotNull(body);
        assertEquals(200, body.getCode());
        assertEquals(2, body.getData().size());
    }
}
