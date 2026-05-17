package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.response.SearchResultsWrapper;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 向量搜索服务
 * 负责从 Milvus 中搜索相似向量
 */
@Service
public class VectorSearchService {

    private static final Logger logger = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private VectorRerankService rerankService;

    @org.springframework.beans.factory.annotation.Value("${rag.candidate-k:10}")
    private int candidateK;

    @org.springframework.beans.factory.annotation.Value("${rag.search-ef:64}")
    private int searchEf;

    /**
     * 搜索相似文档 (粗排 + 精排)
     * 
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        return searchWithFilter(query, topK, MilvusConstants.DOCUMENT_FILTER_EXPR, "相似文档").getResults();
    }

    /**
     * 搜索普通知识库并返回检索解释信息，用于排查 RAG 命中质量。
     *
     * @param query 查询文本
     * @param topK 返回最相似的K个结果
     * @return 包含粗排候选、精排结果和检索参数的追踪信息
     */
    public SearchTrace explainSimilarDocuments(String query, int topK) {
        return searchWithFilter(query, topK, MilvusConstants.DOCUMENT_FILTER_EXPR, "相似文档");
    }

    /**
     * 搜索当前会话的私人记忆。
     *
     * @param query 查询文本
     * @param sessionId 当前会话 ID
     * @param topK 返回最相似的K个结果
     * @return 搜索结果列表
     */
    public List<SearchResult> searchSessionMemories(String query, String sessionId, int topK) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return searchWithFilter(query, topK, MilvusConstants.chatMemoryFilterExpr(sessionId), "会话私人记忆")
                .getResults();
    }

    public List<SearchResult> searchIncidentCases(String query, int topK) {
        return searchWithFilter(query, topK, MilvusConstants.INCIDENT_CASE_FILTER_EXPR, "相似历史故障案例")
                .getResults();
    }

    private SearchTrace searchWithFilter(String query, int topK, String filterExpr, String searchLabel) {
        try {
            int searchK = Math.max(topK, candidateK);
            SearchTrace trace = new SearchTrace();
            trace.setQuery(query);
            trace.setSearchLabel(searchLabel);
            trace.setRequestedTopK(topK);
            trace.setSearchK(searchK);
            trace.setSearchEf(searchEf);
            trace.setFilterExpr(filterExpr);
            logger.info("开始搜索{}, 查询: {}, 粗排获取 {} 条, 精排截取 {} 条", searchLabel, query, searchK, topK);

            // 1. 将查询文本向量化 (稠密和稀疏)
            List<Float> denseVector = embeddingService.generateQueryVector(query);
            java.util.SortedMap<Long, Float> sparseVector = embeddingService.generateSparseVector(query);
            logger.debug("查询向量生成成功, 稠密维度: {}, 稀疏维度: {}", denseVector.size(), sparseVector.size());

            // 2. 构建混合搜索参数
            io.milvus.param.dml.AnnSearchParam denseParam = io.milvus.param.dml.AnnSearchParam.newBuilder()
                    .withVectorFieldName("vector")
                    .withFloatVectors(Collections.singletonList(denseVector))
                    .withMetricType(io.milvus.param.MetricType.COSINE)
                    .withExpr(filterExpr)
                    .withParams("{\"ef\":" + searchEf + "}")
                    .withTopK(searchK)
                    .build();

            io.milvus.param.dml.AnnSearchParam sparseParam = io.milvus.param.dml.AnnSearchParam.newBuilder()
                    .withVectorFieldName("sparse_vector")
                    .withSparseFloatVectors(Collections.singletonList(sparseVector))
                    .withMetricType(io.milvus.param.MetricType.IP)
                    .withExpr(filterExpr)
                    .withTopK(searchK)
                    .build();

            io.milvus.param.dml.HybridSearchParam hybridSearchParam = io.milvus.param.dml.HybridSearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .addSearchRequest(denseParam)
                    .addSearchRequest(sparseParam)
                    .withRanker(io.milvus.param.dml.ranker.RRFRanker.newBuilder().withK(60).build())
                    .withTopK(searchK)
                    .withOutFields(List.of("id", "content", "metadata"))
                    .build();

            // 3. 执行混合搜索 (粗排)
            R<SearchResults> searchResponse = milvusClient.hybridSearch(hybridSearchParam);

            if (searchResponse.getStatus() != 0) {
                throw new RuntimeException("向量搜索失败: " + searchResponse.getMessage());
            }

            // 4. 解析粗排搜索结果
            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SearchResult> candidates = new ArrayList<>();

            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                SearchResult result = new SearchResult();
                result.setId((String) wrapper.getIDScore(0).get(i).get("id"));
                result.setContent((String) wrapper.getFieldData("content", 0).get(i));
                result.setScore(wrapper.getIDScore(0).get(i).getScore());
                
                // 解析 metadata
                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                if (metadataObj != null) {
                    result.setMetadata(metadataObj.toString());
                }
                
                candidates.add(result);
            }

            logger.info("混合检索粗排完成, 找到 {} 个候选文档", candidates.size());

            // 5. 执行重排 (精排)
            List<SearchResult> finalResults = rerankService.rerank(query, candidates, topK);

            trace.setCandidates(candidates);
            trace.setResults(finalResults);
            return trace;

        } catch (RuntimeException e) {
            logger.error("搜索相似文档失败", e);
            throw new RuntimeException("搜索失败: " + e.getMessage(), e);
        }
    }

    /**
     * 检索解释信息
     */
    @Setter
    @Getter
    public static class SearchTrace {
        private String query;
        private String searchLabel;
        private int requestedTopK;
        private int searchK;
        private int searchEf;
        private String filterExpr;
        private List<SearchResult> candidates = new ArrayList<>();
        private List<SearchResult> results = new ArrayList<>();
    }

    /**
     * 搜索结果类
     */
    @Setter
    @Getter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;

    }
}
