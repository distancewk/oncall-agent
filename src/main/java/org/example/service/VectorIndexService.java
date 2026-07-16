package org.example.service;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 向量索引服务
 * 负责读取文件、生成向量、存储到 Milvus
 */
@Service
public class VectorIndexService {

    private static final Logger logger = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired
    @Lazy
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Autowired
    private DocumentChunkService chunkService;

    @Autowired
    private MilvusInsertHelper insertHelper = new MilvusInsertHelper();

    @Autowired(required = false)
    private DependencyGuard dependencyGuard;

    @Value("${file.upload.path}")
    private String uploadPath;

    /**
     * 索引指定目录下的所有文件
     * 
     * @param directoryPath 目录路径（可选，默认使用配置的上传目录）
     * @return 索引结果  这里可以优化：定时重建目录下所有文件的索引
     */
    public IndexingResult indexDirectory(String directoryPath) {
        IndexingResult result = new IndexingResult();
        result.setStartTime(LocalDateTime.now());

        try {
            // 使用指定目录或默认上传目录
            String targetPath = (directoryPath != null && !directoryPath.trim().isEmpty()) 
                    ? directoryPath : uploadPath;
                    
            Path dirPath = Paths.get(targetPath).normalize();
            File directory = dirPath.toFile();
            
            if (!directory.exists() || !directory.isDirectory()) {
                throw new IllegalArgumentException("目录不存在或不是有效目录: " + targetPath);
            }

            result.setDirectoryPath(directory.getAbsolutePath());

            // 获取所有支持的文件
            File[] files = directory.listFiles((dir, name) -> 
                name.endsWith(".txt") || name.endsWith(".md")
            );

            if (files == null || files.length == 0) {
                logger.warn("目录中没有找到支持的文件: {}", targetPath);
                result.setTotalFiles(0);
                result.setSuccess(true);
                result.setEndTime(LocalDateTime.now());
                return result;
            }

            result.setTotalFiles(files.length);
            logger.info("开始索引目录: {}, 找到 {} 个文件", targetPath, files.length);

            // 遍历并索引每个文件
            for (File file : files) {
                try {
                    indexSingleFile(file.getAbsolutePath());
                    result.incrementSuccessCount();
                    logger.info("✓ 文件索引成功: {}", file.getName());
                } catch (Exception e) {
                    result.incrementFailCount();
                    result.addFailedFile(file.getAbsolutePath(), e.getMessage());
                    logger.error("✗ 文件索引失败: {}", file.getName(), e);
                }
            }

            result.setSuccess(result.getFailCount() == 0);
            result.setEndTime(LocalDateTime.now());

            logger.info("目录索引完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalFiles(), result.getSuccessCount(), result.getFailCount());

            return result;

        } catch (Exception e) {
            logger.error("索引目录失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setEndTime(LocalDateTime.now());
            return result;
        }
    }

    /**
     * 索引单个文件
     * 
     * @param filePath 文件路径
     * @throws Exception 索引失败时抛出异常
     */
    public void indexSingleFile(String filePath) throws Exception {
        Path file = Paths.get(filePath).normalize();
        Path fileName = file.getFileName();
        String documentId = DocumentIdentity.documentId(fileName == null ? filePath : fileName.toString());
        indexSingleFile(filePath, documentId, DocumentIdentity.contentHash(file));
    }

    public void indexSingleFile(String filePath, String documentId) throws Exception {
        Path file = Paths.get(filePath).normalize();
        indexSingleFile(filePath, documentId, DocumentIdentity.contentHash(file));
    }

    public void indexSingleFile(String filePath, String documentId, String contentHash) throws Exception {
        Path path = Paths.get(filePath).normalize();
        File file = path.toFile();
        
        if (!file.exists() || !file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }
        if (documentId == null || !documentId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("文档 ID 格式非法");
        }
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("文档内容哈希格式非法");
        }

        logger.info("开始索引文件: {}", path);

        // 1. 读取文件内容
        String content = Files.readString(path);
        logger.info("读取文件: {}, 内容长度: {} 字符", path, content.length());

        // 2. 任务开始时加载 collection；旧版本清理延迟到新版本写入成功之后。
        ensureCollectionLoaded();

        // 3. 文档分片
        List<DocumentChunk> chunks = chunkService.chunkDocument(content, path.toString());
        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());
        if (chunks.isEmpty()) {
            logger.warn("文件未产生可索引分片: {}", filePath);
            return;
        }

        // 5. 批量生成 dense embedding，稀疏向量仍按分片生成
        List<String> contents = new ArrayList<>(chunks.size());
        for (DocumentChunk chunk : chunks) {
            contents.add(chunk.getContent());
        }

        List<List<Float>> vectors = embeddingService.generateEmbeddings(contents);
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException("批量 embedding 返回数量与分片数量不一致");
        }

        List<java.util.SortedMap<Long, Float>> sparseVectors = new ArrayList<>(chunks.size());
        List<Map<String, Object>> metadataList = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            try {
                sparseVectors.add(embeddingService.generateSparseVector(chunk.getContent()));
                metadataList.add(buildMetadata(path.toString(), documentId, contentHash, chunk, chunks.size()));
            } catch (Exception e) {
                logger.error("✗ 分片 {}/{} 准备失败", i + 1, chunks.size(), e);
                throw new RuntimeException("分片准备失败: " + e.getMessage(), e);
            }
        }

        // 6. 批量写入 Milvus
        insertBatchToMilvus(contents, vectors, sparseVectors, metadataList);
        deletePreviousVersions(documentId, contentHash);

        logger.info("文件索引完成: {}, 共 {} 个分片", filePath, chunks.size());
    }

    private void ensureCollectionLoaded() {
        R<RpcStatus> loadResponse = DependencyGuardExecutor.executeMilvus(
                dependencyGuard,
                "ensureCollectionLoaded",
                () -> milvusClient.loadCollection(LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                        .build()));

        // 状态码 65535 表示集合已经加载，这不是错误
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            throw new RuntimeException("加载 collection 失败: " + loadResponse.getMessage());
        }
    }

    /**
     * 删除文件的旧数据（根据 metadata._source）
     */
    private void deletePreviousVersions(String documentId, String contentHash) {
        try {
            // 两个值都由服务端生成且只包含十六进制字符，不接受用户输入。
            // 仅清理已成功写入新内容之外的旧版本，索引失败时不会触碰旧向量。
            String expr = String.format("metadata[\"_document_id\"] == \"%s\" "
                    + "&& metadata[\"_content_hash\"] != \"%s\"", documentId, contentHash);
            
            logger.info("准备删除文档旧数据, documentId: {}", documentId);

            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .withExpr(expr)
                    .build();

            R<MutationResult> response = DependencyGuardExecutor.executeMilvus(
                    dependencyGuard, "deletePreviousVersions", () -> milvusClient.delete(deleteParam));

            if (response.getStatus() != 0) {
                throw new RuntimeException("删除旧数据失败: " + response.getMessage());
            } else {
                long deletedCount = response.getData().getDeleteCnt();
                logger.info("✓ 已删除文档旧数据, 删除记录数: {}", deletedCount);
            }

        } catch (RuntimeException e) {
            logger.error("删除旧数据失败", e);
            throw e;
        } catch (Exception e) {
            logger.error("删除旧数据失败", e);
            throw new RuntimeException("删除旧数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建元数据（包含文件信息）
     */
    Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int totalChunks) {
        Path path = Paths.get(filePath).normalize();
        Path fileName = path.getFileName();
        String filename = fileName == null ? filePath : fileName.toString();
        return buildMetadata(filePath, DocumentIdentity.documentId(filename), chunk, totalChunks);
    }

    private Map<String, Object> buildMetadata(String filePath, String documentId,
                                              DocumentChunk chunk, int totalChunks) {
        return buildMetadata(filePath, documentId, "", chunk, totalChunks);
    }

    private Map<String, Object> buildMetadata(String filePath, String documentId, String contentHash,
                                              DocumentChunk chunk, int totalChunks) {
        Map<String, Object> metadata = new HashMap<>();
        
        // 标准化路径：使用统一的路径分隔符（正斜杠）用于存储，确保跨平台一致性
        Path path = Paths.get(filePath).normalize();
        String normalizedPath = path.toString().replace(File.separator, "/");
        
        // 文件信息
        Path fileName = path.getFileName();
        String fileNameStr = fileName != null ? fileName.toString() : "";
        String extension = "";
        int dotIndex = fileNameStr.lastIndexOf('.');
        if (dotIndex > 0) {
            extension = fileNameStr.substring(dotIndex);
        }
        
        metadata.put("_source", normalizedPath);
        metadata.put("_document_id", documentId);
        if (contentHash != null && !contentHash.isBlank()) {
            metadata.put("_content_hash", contentHash);
        }
        metadata.put("doc_type", MilvusConstants.DOC_TYPE_DOCUMENT);
        metadata.put("_extension", extension);
        metadata.put("_file_name", fileNameStr);
        
        // 分片信息
        metadata.put("chunkIndex", chunk.getChunkIndex());
        metadata.put("totalChunks", totalChunks);
        
        // 标题信息
        if (chunk.getTitle() != null && !chunk.getTitle().isEmpty()) {
            metadata.put("title", chunk.getTitle());
        }
        
        return metadata;
    }

    /**
     * 插入向量到 Milvus
     */
    private void insertBatchToMilvus(List<String> contents,
                                     List<List<Float>> vectors,
                                     List<java.util.SortedMap<Long, Float>> sparseVectors,
                                     List<Map<String, Object>> metadataList) throws Exception {
        try {
            List<String> ids = new ArrayList<>(metadataList.size());
            for (Map<String, Object> metadata : metadataList) {
                String source = (String) metadata.get("_document_id");
                int chunkIndex = ((Number) metadata.get("chunkIndex")).intValue();
                ids.add(UUID.nameUUIDFromBytes((source + "_" + chunkIndex).getBytes(StandardCharsets.UTF_8)).toString());
            }

            InsertParam insertParam = insertHelper.buildInsertParam(ids, contents, vectors, sparseVectors, metadataList);
            R<MutationResult> insertResponse = DependencyGuardExecutor.executeMilvus(
                    dependencyGuard, "insertBatchToMilvus",
                    () -> milvusClient.insert(insertParam));

            if (insertResponse.getStatus() != 0) {
                throw new RuntimeException("插入向量失败: " + insertResponse.getMessage());
            }

            logger.info("✓ 批量向量插入成功: {} 条", contents.size());

        } catch (Exception e) {
            logger.error("批量插入向量到 Milvus 失败", e);
            throw e;
        }
    }

    /**
     * 索引结果类
     */
    @Getter
    public static class IndexingResult {
        @Setter
        private boolean success;
        @Setter
        private String directoryPath;
        @Setter
        private int totalFiles;
        private int successCount;
        private int failCount;
        @Setter
        private LocalDateTime startTime;
        @Setter
        private LocalDateTime endTime;
        @Setter
        private String errorMessage;
        private Map<String, String> failedFiles = new HashMap<>();

        public void incrementSuccessCount() {
            this.successCount++;
        }

        public void incrementFailCount() {
            this.failCount++;
        }

        public long getDurationMs() {
            if (startTime != null && endTime != null) {
                return java.time.Duration.between(startTime, endTime).toMillis();
            }
            return 0;
        }

        public void addFailedFile(String filePath, String error) {
            this.failedFiles.put(filePath, error);
        }
    }
}
