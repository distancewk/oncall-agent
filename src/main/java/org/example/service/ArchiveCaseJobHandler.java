package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.BackgroundJobRecord;
import org.example.service.IncidentCaseService.ArchiveResult;
import org.springframework.stereotype.Service;

/** Persists confirmed diagnosis cases outside the user-facing confirmation request. */
@Service
public class ArchiveCaseJobHandler implements BackgroundJobHandler {

    private final ObjectMapper objectMapper;
    private final IncidentCaseService incidentCaseService;
    private final IncidentService incidentService;
    private final BackgroundJobRepository jobRepository;

    public ArchiveCaseJobHandler(ObjectMapper objectMapper,
                                 IncidentCaseService incidentCaseService,
                                 IncidentService incidentService,
                                 BackgroundJobRepository jobRepository) {
        this.objectMapper = objectMapper;
        this.incidentCaseService = incidentCaseService;
        this.incidentService = incidentService;
        this.jobRepository = jobRepository;
    }

    @Override
    public String jobType() {
        return "ARCHIVE_CASE";
    }

    @Override
    public void handle(BackgroundJobRecord job) throws Exception {
        if (jobRepository.isCancelRequested(job.getJobId())) {
            return;
        }
        JsonNode payload = objectMapper.readTree(job.getPayload());
        String incidentId = required(payload, "incidentId");
        String runId = required(payload, "runId");
        try {
            ArchiveResult result = incidentCaseService.archiveCase(incidentId, runId);
            incidentService.markRunCaseArchived(
                    incidentId, runId, result.isSuccess(), result.getDocumentId(), result.getMessage());
        } catch (Exception error) {
            if (job.getAttemptCount() >= job.getMaxAttempts()
                    && !jobRepository.isCancelRequested(job.getJobId())) {
                incidentService.markRunCaseArchived(
                        incidentId, runId, false, null, "历史案例写入失败，请稍后重试");
            }
            throw error;
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
