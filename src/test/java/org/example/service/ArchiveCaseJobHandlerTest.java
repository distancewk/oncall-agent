package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.BackgroundJobRecord;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveCaseJobHandlerTest {

    @Test
    void handle_shouldArchiveConfirmedRunAndUpdateRunState() throws Exception {
        IncidentCaseService incidentCaseService = mock(IncidentCaseService.class);
        IncidentService incidentService = mock(IncidentService.class);
        BackgroundJobRepository jobRepository = mock(BackgroundJobRepository.class);
        BackgroundJobRecord job = new BackgroundJobRecord();
        job.setJobId("job-archive-1");
        job.setPayload("{\"incidentId\":\"incident-1\",\"runId\":\"run-1\"}");
        job.setAttemptCount(1);
        job.setMaxAttempts(3);

        IncidentCaseService.ArchiveResult result = new IncidentCaseService.ArchiveResult();
        result.setSuccess(true);
        result.setDocumentId("case-doc-1");
        result.setMessage("历史案例已写入知识库");
        when(incidentCaseService.archiveCase("incident-1", "run-1")).thenReturn(result);

        ArchiveCaseJobHandler handler = new ArchiveCaseJobHandler(
                new ObjectMapper(), incidentCaseService, incidentService, jobRepository);

        handler.handle(job);

        verify(incidentCaseService).archiveCase("incident-1", "run-1");
        verify(incidentService).markRunCaseArchived(
                "incident-1", "run-1", true, "case-doc-1", "历史案例已写入知识库");
    }
}
