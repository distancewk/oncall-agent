package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.dto.IndexTaskStatus;
import org.example.service.IndexTaskStatusService;
import org.example.service.VectorSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;

    private final VectorSearchService vectorSearchService;
    private final IndexTaskStatusService indexTaskStatusService;

    public KnowledgeController(VectorSearchService vectorSearchService,
                               IndexTaskStatusService indexTaskStatusService) {
        this.vectorSearchService = vectorSearchService;
        this.indexTaskStatusService = indexTaskStatusService;
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<VectorSearchService.SearchTrace>> searchKnowledge(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        int safeTopK = Math.max(MIN_TOP_K, Math.min(topK, MAX_TOP_K));
        return ResponseEntity.ok(ApiResponse.success(
                vectorSearchService.explainSimilarDocuments(query.trim(), safeTopK)
        ));
    }

    @GetMapping("/index-tasks")
    public ResponseEntity<ApiResponse<List<IndexTaskStatus>>> listIndexTasks() {
        return ResponseEntity.ok(ApiResponse.success(indexTaskStatusService.listStatuses()));
    }
}
