package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.exception.DependencyUnavailableException;
import org.example.service.DiagnosisEvidenceRecorder;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";
    
    private final VectorSearchService vectorSearchService;
    
    @Value("${rag.top-k:3}")
    private int topK = 3; // 默认值
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private DiagnosisEvidenceRecorder diagnosisEvidenceRecorder;
    
    /**
     * 构造函数注入依赖
     * Spring 会自动注入 VectorSearchService
     */
    @Autowired
    public InternalDocsTools(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }
    
    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") 
            String query) {
        if (diagnosisEvidenceRecorder != null) {
            return diagnosisEvidenceRecorder.recordToolCall(
                    TOOL_QUERY_INTERNAL_DOCS,
                    buildQueryParams(query),
                    "知识库相似文档",
                    () -> doQueryInternalDocs(query));
        }
        return doQueryInternalDocs(query);
    }

    private String doQueryInternalDocs(String query) {
        try {
            // 使用向量搜索服务检索相关文档
            List<VectorSearchService.SearchResult> searchResults = 
                    vectorSearchService.searchSimilarDocuments(query, topK);
            
            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }
            
            // 将搜索结果转换为 JSON 格式
            String resultJson = objectMapper.writeValueAsString(searchResults);
            

            return resultJson;
            
        } catch (DependencyUnavailableException e) {
            logger.error("[工具错误] queryInternalDocs 依赖熔断", e);
            return buildDependencyError(e);
        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}", 
                    e.getMessage());
        }
    }

    private String buildQueryParams(String query) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("query", query);
            params.put("topK", topK);
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return "{\"query\":\"" + (query == null ? "" : query.replace("\"", "\\\"")) + "\"}";
        }
    }

    private String buildDependencyError(DependencyUnavailableException e) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("status", "error");
            error.put("errorCode", e.getErrorCode());
            error.put("message", e.getMessage());
            return objectMapper.writeValueAsString(error);
        } catch (Exception jsonError) {
            return "{\"success\":false,\"status\":\"error\",\"errorCode\":\""
                    + escapeJson(e.getErrorCode()) + "\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
