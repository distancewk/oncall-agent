package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.BackgroundJobRecord;
import org.springframework.stereotype.Service;

@Service
public class DocumentIndexJobHandler implements BackgroundJobHandler {

    private final ObjectMapper objectMapper;
    private final IndexTaskStatusService statusService;
    private final VectorIndexService vectorIndexService;
    private final BackgroundJobRepository jobRepository;

    public DocumentIndexJobHandler(ObjectMapper objectMapper,
                                   IndexTaskStatusService statusService,
                                   VectorIndexService vectorIndexService,
                                   BackgroundJobRepository jobRepository) {
        this.objectMapper = objectMapper;
        this.statusService = statusService;
        this.vectorIndexService = vectorIndexService;
        this.jobRepository = jobRepository;
    }

    @Override
    public String jobType() {
        return "DOCUMENT_INDEX";
    }

    @Override
    public void handle(BackgroundJobRecord job) throws Exception {
        if (jobRepository.isCancelRequested(job.getJobId())) {
            return;
        }
        JsonNode payload = objectMapper.readTree(job.getPayload());
        String taskId = required(payload, "taskId");
        String filePath = required(payload, "filePath");
        String documentId = payload.path("documentId").asText("");
        String contentHash = payload.path("contentHash").asText("");
        statusService.markRunning(taskId);
        try {
            if (documentId.isBlank() || contentHash.isBlank()) {
                vectorIndexService.indexSingleFile(filePath);
            } else {
                vectorIndexService.indexSingleFile(filePath, documentId, contentHash);
            }
            if (!jobRepository.isCancelRequested(job.getJobId())) {
                statusService.markCompleted(taskId);
            }
        } catch (Exception e) {
            if (job.getAttemptCount() >= job.getMaxAttempts()) {
                statusService.markFailed(taskId, "索引失败，请稍后重试");
            }
            throw e;
        }
    }

    private String required(JsonNode payload, String field) {
        String value = payload.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("后台任务缺少字段: " + field);
        }
        return value;
    }
}
