package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.dto.BackgroundJobRecord;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DocumentIndexJobHandlerTest {

    @Test
    void handle_shouldIndexFileAndMarkTaskCompleted() throws Exception {
        IndexTaskStatusService statusService = mock(IndexTaskStatusService.class);
        VectorIndexService vectorIndexService = mock(VectorIndexService.class);
        BackgroundJobRepository jobRepository = mock(BackgroundJobRepository.class);
        BackgroundJobRecord job = new BackgroundJobRecord();
        job.setJobId("job-1");
        job.setJobType("DOCUMENT_INDEX");
        job.setBusinessKey("task-1");
        job.setPayload("{\"taskId\":\"task-1\",\"filePath\":\"/tmp/runbook.md\"}");
        job.setAttemptCount(1);
        job.setMaxAttempts(3);

        DocumentIndexJobHandler handler = new DocumentIndexJobHandler(
                new ObjectMapper(), statusService, vectorIndexService, jobRepository);

        handler.handle(job);

        verify(statusService).markRunning("task-1");
        verify(vectorIndexService).indexSingleFile("/tmp/runbook.md");
        verify(statusService).markCompleted("task-1");
    }
}
