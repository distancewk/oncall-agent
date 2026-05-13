package org.example.service;

import org.example.dto.IndexTaskStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class IndexTaskStatusService {

    private final ConcurrentMap<String, IndexTaskStatus> taskStatuses = new ConcurrentHashMap<>();

    public IndexTaskStatus createTask(String fileName, String filePath) {
        long now = System.currentTimeMillis();
        IndexTaskStatus status = new IndexTaskStatus();
        status.setTaskId(UUID.randomUUID().toString());
        status.setFileName(fileName);
        status.setFilePath(filePath);
        status.setStatus("INDEXING");
        status.setMessage("文件已接收，索引处理中");
        status.setCreatedAt(now);
        status.setUpdatedAt(now);
        taskStatuses.put(status.getTaskId(), status);
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
        return Optional.ofNullable(taskStatuses.get(taskId));
    }

    public List<IndexTaskStatus> listStatuses() {
        return taskStatuses.values().stream()
                .sorted(Comparator.comparingLong(IndexTaskStatus::getUpdatedAt).reversed())
                .toList();
    }

    private void update(String taskId, String statusValue, String message, String errorMessage) {
        IndexTaskStatus status = taskStatuses.get(taskId);
        if (status == null) {
            return;
        }
        status.setStatus(statusValue);
        status.setMessage(message);
        status.setErrorMessage(errorMessage);
        status.setUpdatedAt(System.currentTimeMillis());
    }
}
