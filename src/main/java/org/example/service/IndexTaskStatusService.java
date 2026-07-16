package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.AppJobProperties;
import org.example.dto.IndexTaskStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IndexTaskStatusService {

    private final DataSource dataSource;
    private final IndexTaskRepository repository;
    private final BackgroundJobRepository backgroundJobRepository;
    private final ObjectMapper objectMapper;
    private final AppJobProperties jobProperties;

    @Autowired
    public IndexTaskStatusService(DataSource dataSource,
                                  IncidentSchemaMigrator schemaMigrator,
                                  BackgroundJobRepository backgroundJobRepository,
                                  ObjectMapper objectMapper,
                                  AppJobProperties jobProperties) {
        schemaMigrator.migrate();
        this.dataSource = dataSource;
        this.repository = new IndexTaskRepository(dataSource);
        this.backgroundJobRepository = backgroundJobRepository;
        this.objectMapper = objectMapper;
        this.jobProperties = jobProperties;
    }

    public IndexTaskStatus createTask(String fileName, String filePath) {
        IndexTaskStatus status = newQueuedTask(fileName, filePath,
                DocumentIdentity.documentId(fileName), null);
        repository.insert(status);
        return status;
    }

    public IndexTaskStatus createTaskAndEnqueue(String fileName, String filePath) {
        return createTaskAndEnqueue(fileName, filePath,
                DocumentIdentity.documentId(fileName), null);
    }

    public IndexTaskStatus createTaskAndEnqueue(String fileName, String filePath,
                                                String documentId, String contentHash) {
        IndexTaskStatus status = newQueuedTask(fileName, filePath, documentId, contentHash);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String payload = objectMapper.writeValueAsString(java.util.Map.of(
                        "taskId", status.getTaskId(),
                        "fileName", fileName,
                        "filePath", filePath,
                        "documentId", documentId,
                        "contentHash", contentHash == null ? "" : contentHash
                ));
                repository.insert(connection, status);
                backgroundJobRepository.enqueue(
                        connection,
                        "DOCUMENT_INDEX",
                        status.getTaskId(),
                        payload,
                        jobProperties.getIndexMaxAttempts(),
                        status.getCreatedAt()
                );
                connection.commit();
                return status;
            } catch (Exception e) {
                rollback(connection, e);
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "创建文档索引后台任务失败: " + status.getTaskId(), e);
        }
    }

    private void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            failure.addSuppressed(rollbackError);
        }
    }

    private IndexTaskStatus newQueuedTask(String fileName, String filePath,
                                          String documentId, String contentHash) {
        long now = System.currentTimeMillis();
        IndexTaskStatus status = new IndexTaskStatus();
        status.setTaskId(UUID.randomUUID().toString());
        status.setFileName(fileName);
        status.setFilePath(filePath);
        status.setDocumentId(documentId);
        status.setContentHash(contentHash);
        status.setStatus("INDEXING");
        status.setMessage("文件已接收，索引处理中");
        status.setCreatedAt(now);
        status.setUpdatedAt(now);
        return status;
    }

    public void markRunning(String taskId) {
        update(taskId, "INDEXING", "索引处理中", null);
    }

    public void markCompleted(String taskId) {
        update(taskId, "COMPLETED", "索引完成", null);
    }

    public void markFailed(String taskId, String errorMessage) {
        update(taskId, "FAILED", "索引失败", errorMessage);
    }

    public Optional<IndexTaskStatus> getStatus(String taskId) {
        return repository.find(taskId);
    }

    public List<IndexTaskStatus> listStatuses() {
        return repository.list();
    }

    private void update(String taskId, String statusValue, String message, String errorMessage) {
        repository.update(taskId, statusValue, message, errorMessage, System.currentTimeMillis());
    }
}
